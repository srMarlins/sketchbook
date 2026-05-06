package com.sketchbook.catalog

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmScannerTest {

    private fun writeAls(target: Path, xml: String) {
        Files.createDirectories(target.parent)
        Files.newOutputStream(target).use { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun scansDirectoryAndPopulatesCatalog() = runTest {
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
                </LiveSet></Ableton>"""
            )
            writeAls(
                root.resolve("Projects/Bar/Bar.als"),
                """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 11.3.21"><LiveSet>
                  <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="90.0"/></Tempo></Mixer></DeviceChain></MainTrack>
                  <Tracks>
                    <AudioTrack/><MidiTrack/>
                  </Tracks>
                </LiveSet></Ableton>"""
            )
            // Backup directory contents must be skipped.
            writeAls(
                root.resolve("Projects/Bar/Backup/Bar [old].als"),
                """<?xml version="1.0"?><Ableton><LiveSet/></Ableton>"""
            )
            // Empty/broken project — should appear as ProjectFailed.
            writeAls(
                root.resolve("Projects/Broken/Broken.als"),
                "not actually xml"
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
    fun persistsKeySignatureFromScaleInformation() = runTest {
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
                </LiveSet></Ableton>"""
            )
            // No ScaleInformation — key column should remain NULL.
            writeAls(
                root.resolve("Projects/Keyless/Keyless.als"),
                """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="120"/></Tempo></Mixer></DeviceChain></MainTrack>
                </LiveSet></Ableton>"""
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

    @Test
    fun persistsStageInferredFromHeuristic() = runTest {
        // Project with a Limiter and a recent mtime: hits the Mixing branch.
        val root = createTempDirectory("scanner-stage-")
        try {
            writeAls(
                root.resolve("Projects/Mixed/Mixed.als"),
                """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <Tracks>
                    <AudioTrack/><AudioTrack/><AudioTrack/><AudioTrack/><AudioTrack/>
                    <AudioTrack/><AudioTrack/><AudioTrack/><AudioTrack/><AudioTrack/>
                    <AudioTrack><DeviceChain><Devices>
                      <PluginDevice>
                        <PluginDesc>
                          <VstPluginInfo><PlugName Value="FabFilter Pro-L 2"/></VstPluginInfo>
                        </PluginDesc>
                      </PluginDevice>
                    </Devices></DeviceChain></AudioTrack>
                  </Tracks>
                </LiveSet></Ableton>"""
            )
            // Sketchy project — small + just touched, no mastering chain.
            writeAls(
                root.resolve("Projects/Sketchy/Sketchy.als"),
                """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <Tracks>
                    <AudioTrack/><AudioTrack/>
                  </Tracks>
                </LiveSet></Ableton>"""
            )

            val handle = CatalogDb.openInMemory()
            val catalog = handle.catalog
            val scanner = JvmScanner(catalog, CatalogFts(handle.driver), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            scanner.scan(root).toList()

            val rows = catalog.catalogQueries.selectAllProjects().executeAsList()
            val mixed = rows.first { it.name == "Mixed" }
            val sketchy = rows.first { it.name == "Sketchy" }
            // The mtime is `now` (we just wrote the file) so daysSinceEdit ≈ 0; with a Limiter
            // present the heuristic must return Mixing. Sketchy hits the Sketch branch.
            assertEquals("Mixing", mixed.stage_inferred)
            assertEquals("Sketch", sketchy.stage_inferred)
            assertEquals(null, mixed.stage_override)
            assertEquals(0L, mixed.has_local_bounce)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun preservesStageOverrideAcrossRescan() = runTest {
        val root = createTempDirectory("scanner-stage-override-")
        try {
            writeAls(
                root.resolve("Projects/Pinned/Pinned.als"),
                """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <Tracks><AudioTrack/></Tracks>
                </LiveSet></Ableton>"""
            )
            val handle = CatalogDb.openInMemory()
            val catalog = handle.catalog
            val scanner = JvmScanner(catalog, CatalogFts(handle.driver), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            scanner.scan(root).toList()

            // Manually pin an override (as the user would via the detail panel context menu).
            val firstId = catalog.catalogQueries.selectAllProjects().executeAsList()
                .first { it.name == "Pinned" }.id
            catalog.catalogQueries.updateStageOverride(stage_override = "Done", id = firstId)

            // Re-scan — touch mtime to force a re-parse rather than the skip path.
            java.nio.file.Files.setLastModifiedTime(
                root.resolve("Projects/Pinned/Pinned.als"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000),
            )
            scanner.scan(root).toList()

            val row = catalog.catalogQueries.selectAllProjects().executeAsList()
                .first { it.name == "Pinned" }
            // Override survives the INSERT OR REPLACE.
            assertEquals("Done", row.stage_override)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun populatesSampleSizeBytesFromDisk() = runTest {
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
                </LiveSet></Ableton>"""
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
