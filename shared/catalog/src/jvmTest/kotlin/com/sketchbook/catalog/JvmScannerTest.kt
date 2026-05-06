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
