package com.sketchbook.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmSampleScannerTest {
    @Test
    fun scansAudioFilesAndIgnoresOthers() =
        runTest {
            val root = createTempDirectory("sample-scanner-test-")
            try {
                // Three audio files at varying depths/extensions; one .txt that must NOT land in the
                // corpus; one dotfile resource fork that must be skipped.
                Files.createDirectories(root.resolve("loops/drums"))
                Files.write(root.resolve("kick.wav"), ByteArray(1024) { 0x1 })
                Files.write(root.resolve("loops/snare.aif"), ByteArray(512) { 0x2 })
                Files.write(root.resolve("loops/drums/hat.flac"), ByteArray(2048) { 0x3 })
                Files.write(root.resolve("readme.txt"), "not audio".toByteArray())
                Files.write(root.resolve("loops/._snare.aif"), ByteArray(64) { 0x4 })

                val handle = CatalogDb.openInMemory()
                val scanner = JvmSampleScanner(handle.catalog, ioDispatcher = Dispatchers.Unconfined)

                val inserted = scanner.scan(root.toString())
                assertEquals(3, inserted, "should index 3 audio files")

                val total =
                    handle.catalog.catalogQueries
                        .countSamples()
                        .executeAsOne()
                assertEquals(3L, total)

                // Filename + size lookup should resolve a single candidate for each.
                val kicks =
                    handle.catalog.catalogQueries
                        .selectSamplesByFilenameAndSize(filename = "kick.wav", size_bytes = 1024L)
                        .executeAsList()
                assertEquals(1, kicks.size)
                assertTrue(kicks.first().path.endsWith("kick.wav"))
            } finally {
                // Best-effort cleanup; @TempDir would be cleaner but kotlin.test on JVM doesn't ship
                // it, and createTempDirectory matches the JvmScannerTest pattern already in this
                // module.
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun rescanIsIdempotent() =
        runTest {
            val root = createTempDirectory("sample-scanner-idem-")
            try {
                Files.write(root.resolve("clap.wav"), ByteArray(256))
                val handle = CatalogDb.openInMemory()
                val scanner = JvmSampleScanner(handle.catalog, ioDispatcher = Dispatchers.Unconfined)

                scanner.scan(root.toString())
                scanner.scan(root.toString())

                val total =
                    handle.catalog.catalogQueries
                        .countSamples()
                        .executeAsOne()
                assertEquals(1L, total, "INSERT OR REPLACE should keep one row per path")
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
