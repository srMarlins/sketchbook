package com.sketchbook.catalog

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmScannerTest {
    private fun writeAls(
        target: Path,
        xml: String,
    ) {
        Files.createDirectories(target.parent)
        Files.newOutputStream(target).use { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun scansDirectoryAndPopulatesCatalog() =
        runTest {
            val root = createTempDirectory("scanner-test-")
            try {
                writeAls(
                    root.resolve("Projects/Foo/Foo.als"),
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="124.0"/></Tempo></Mixer></DeviceChain></MainTrack>
                  <Tracks><AudioTrack><Name><EffectiveName Value="Drums"/></Name>
                    <DeviceChain><Devices><Eq8/></Devices></DeviceChain>
                  </AudioTrack></Tracks>
                </LiveSet></Ableton>""",
                )
                writeAls(
                    root.resolve("Projects/Bar/Bar.als"),
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 11.3.21"><LiveSet>
                  <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="90.0"/></Tempo></Mixer></DeviceChain></MainTrack>
                  <Tracks>
                    <AudioTrack/><MidiTrack/>
                  </Tracks>
                </LiveSet></Ableton>""",
                )
                // Backup directory contents must be skipped.
                writeAls(
                    root.resolve("Projects/Bar/Backup/Bar [old].als"),
                    """<?xml version="1.0"?><Ableton><LiveSet/></Ableton>""",
                )
                // Empty/broken project — should appear as ProjectFailed.
                writeAls(
                    root.resolve("Projects/Broken/Broken.als"),
                    "not actually xml",
                )

                val handle = CatalogDb.openInMemory()
                val catalog = handle.catalog
                val scanner = JvmScanner(catalog, CatalogFts(handle.driver), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                val events = scanner.scan(root).toList()

                val started = events.filterIsInstance<ScanProgress.Started>().single()
                // Three projects (Backup excluded).
                assertEquals(3, started.total)

                val indexed = events.filterIsInstance<ScanProgress.ProjectIndexed>()
                val failed = events.filterIsInstance<ScanProgress.ProjectFailed>()
                val finished = events.filterIsInstance<ScanProgress.Finished>().single()

                assertEquals(2, indexed.size)
                assertEquals(1, failed.size)
                assertEquals(3, finished.done)
                assertEquals(3, finished.total)
                assertTrue(finished.durationMillis >= 0)

                val rows = catalog.catalogQueries.selectAllProjects().executeAsList()
                // Three rows: two ok + one parse_status='failed' for Broken (so it surfaces on the
                // Broken shelf with the parse error captured in `parse_error`).
                assertEquals(3, rows.size)
                assertEquals(setOf("Foo", "Bar", "Broken"), rows.map { it.name }.toSet())
                val brokenRow = rows.first { it.name == "Broken" }
                assertEquals("failed", brokenRow.parse_status)
                assertTrue(brokenRow.parse_error?.isNotBlank() == true)

                // Plugins from Foo: one native Eq8.
                val fooId = rows.first { it.name == "Foo" }.id
                val fooPlugins = catalog.catalogQueries.selectPluginsForProject(fooId).executeAsList()
                assertEquals(1, fooPlugins.size)
                assertEquals("Eq8", fooPlugins[0].plugin_name)
                assertEquals("abletonnative", fooPlugins[0].plugin_type)
                assertEquals("Drums", fooPlugins[0].track_name)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun persistsKeySignatureFromScaleInformation() =
        runTest {
            val root = createTempDirectory("scanner-key-")
            try {
                // F# Major: RootNote=6 (semitones from C), Name=Major.
                writeAls(
                    root.resolve("Projects/Keyed/Keyed.als"),
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <ScaleInformation>
                    <RootNote Value="6"/>
                    <Name Value="Major"/>
                  </ScaleInformation>
                </LiveSet></Ableton>""",
                )
                // No ScaleInformation — key column should remain NULL.
                writeAls(
                    root.resolve("Projects/Keyless/Keyless.als"),
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="120"/></Tempo></Mixer></DeviceChain></MainTrack>
                </LiveSet></Ableton>""",
                )

                val handle = CatalogDb.openInMemory()
                val catalog = handle.catalog
                val scanner = JvmScanner(catalog, CatalogFts(handle.driver), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                scanner.scan(root).toList()

                val rows = catalog.catalogQueries.selectAllProjects().executeAsList()
                val keyed = rows.first { it.name == "Keyed" }
                val keyless = rows.first { it.name == "Keyless" }
                assertEquals("F# Major", keyed.key)
                assertEquals(null, keyless.key)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun keyIndexIsPresent() {
        val handle = CatalogDb.openInMemory()
        val out = mutableSetOf<String>()
        handle.driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'index'",
            mapper = { c ->
                while (c.next().value) c.getString(0)?.let { out += it }
                app.cash.sqldelight.db.QueryResult.Unit
            },
            parameters = 0,
        )
        assertTrue("idx_projects_key" in out, "missing idx_projects_key — got $out")
    }

    // ------------------------------------------------------------------------
    // PR-R R1: stage inference + override preservation
    // ------------------------------------------------------------------------

    @Test
    fun persistsStageInferredFromHeuristic() =
        runTest {
            val root = createTempDirectory("scanner-stage-")
            try {
                // Mixing rule: mastering chain plug-in + edited within 14d. Pro-L 2 in plugin
                // names triggers the "pro-l" mastering needle.
                val alsPath = root.resolve("Projects/Mixed/Mixed.als")
                writeAls(
                    alsPath,
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <Tracks><AudioTrack>
                    <Name><EffectiveName Value="Master"/></Name>
                    <DeviceChain><Devices>
                      <PluginDevice><PluginDesc><Vst3PluginInfo>
                        <Name Value="Pro-L 2"/>
                      </Vst3PluginInfo></PluginDesc></PluginDevice>
                    </Devices></DeviceChain>
                  </AudioTrack></Tracks>
                </LiveSet></Ableton>""",
                )
                // mtime = now - 1 day → comfortably inside the 14d Mixing window. Live mtime in
                // seconds; FileTime accepts millis.
                val recent = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                Files.setLastModifiedTime(alsPath, FileTime.fromMillis(recent))

                val handle = CatalogDb.openInMemory()
                val catalog = handle.catalog
                val scanner = JvmScanner(catalog, CatalogFts(handle.driver), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                scanner.scan(root).toList()

                val row =
                    catalog.catalogQueries
                        .selectAllProjects()
                        .executeAsList()
                        .single()
                assertEquals("Mixing", row.stage_inferred)
                // No bounce dropped beside the project file.
                assertEquals(0L, row.has_local_bounce)
                // No prior override.
                assertNull(row.stage_override)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun preservesStageOverrideAcrossRescan() =
        runTest {
            val root = createTempDirectory("scanner-override-")
            try {
                val alsPath = root.resolve("Projects/User/User.als")
                writeAls(
                    alsPath,
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="120"/></Tempo></Mixer></DeviceChain></MainTrack>
                </LiveSet></Ableton>""",
                )

                val handle = CatalogDb.openInMemory()
                val catalog = handle.catalog
                val scanner = JvmScanner(catalog, CatalogFts(handle.driver), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

                // First scan populates the row.
                scanner.scan(root).toList()
                val firstRow =
                    catalog.catalogQueries
                        .selectAllProjects()
                        .executeAsList()
                        .single()
                assertNull(firstRow.stage_override)

                // User clicks the chip and overrides to "Done".
                catalog.catalogQueries.updateStageOverride(
                    stage_override = "Done",
                    id = firstRow.id,
                )
                assertEquals(
                    "Done",
                    catalog.catalogQueries
                        .selectProjectById(firstRow.id)
                        .executeAsOne()
                        .stage_override,
                )

                // Bump the .als mtime so the incremental-skip optimization re-parses (otherwise the
                // scanner takes the Skipped path and never goes through persistOk).
                Files.setLastModifiedTime(alsPath, FileTime.fromMillis(System.currentTimeMillis() + 5_000))

                scanner.scan(root).toList()
                val rescanned =
                    catalog.catalogQueries
                        .selectAllProjects()
                        .executeAsList()
                        .single()
                // Override survived the INSERT OR REPLACE round-trip.
                assertEquals("Done", rescanned.stage_override)
                // Inferred is whatever the heuristic computed (may be null for this minimal .als);
                // important assertion is that the override write wasn't clobbered.
                assertNotNull(rescanned)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun populatesSampleSizeBytesFromDisk() =
        runTest {
            val root = createTempDirectory("scanner-sample-size-")
            try {
                // Drop a real sample next to the project so the parser's relative-path resolves.
                val sampleBytes = ByteArray(2048) { (it and 0xFF).toByte() }
                Files.createDirectories(root.resolve("Projects/Sized/Samples"))
                Files.write(root.resolve("Projects/Sized/Samples/loop.wav"), sampleBytes)
                writeAls(
                    root.resolve("Projects/Sized/Sized.als"),
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <Tracks><AudioTrack><DeviceChain>
                    <SampleRef>
                      <FileRef>
                        <Path Value="Samples/loop.wav"/>
                      </FileRef>
                    </SampleRef>
                  </DeviceChain></AudioTrack></Tracks>
                </LiveSet></Ableton>""",
                )
                val handle = CatalogDb.openInMemory()
                val catalog = handle.catalog
                val scanner = JvmScanner(catalog, CatalogFts(handle.driver), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                scanner.scan(root).toList()

                val rows = catalog.catalogQueries.selectAllProjects().executeAsList()
                val sized = rows.first { it.name == "Sized" }
                val samples = catalog.catalogQueries.selectSampleEntriesForProject(sized.id).executeAsList()
                assertEquals(1, samples.size)
                val sample = samples.single()
                assertEquals(0L, sample.is_missing)
                assertEquals(sampleBytes.size.toLong(), sample.size_bytes)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
