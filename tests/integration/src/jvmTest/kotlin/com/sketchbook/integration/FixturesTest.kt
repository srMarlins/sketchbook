package com.sketchbook.integration

import com.sketchbook.als.AlsParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixturesTest {
    private val tmp: Path = createTempDirectory("fixtures-test-")

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun cleanFixtureParsesOk() {
        val projectDir = Fixtures.writeCleanProject(tmp.resolve("clean"))
        val md = AlsParser.parse(projectDir.resolve("clean.als"))
        assertEquals(0, md.macPathsCount)
        assertTrue(md.sampleRefs.size >= 1)
    }

    @Test
    fun missingSamplesFixtureHasOneMissing() {
        val projectDir = Fixtures.writeMissingSamplesProject(tmp.resolve("ms"))
        val samplesDir = projectDir.resolve("Samples")
        // Exactly one sample present on disk, but the .als references two.
        val onDisk = Files.list(samplesDir).use { it.count() }
        val md = AlsParser.parse(projectDir.resolve("missing_samples.als"))
        assertEquals(1L, onDisk)
        assertEquals(2, md.sampleRefs.size)
    }

    @Test
    fun macPathsFixtureHasMacPaths() {
        val projectDir = Fixtures.writeMacPathsProject(tmp.resolve("mac"))
        val md = AlsParser.parse(projectDir.resolve("mac_paths.als"))
        assertTrue(md.macPathsCount > 0, "expected mac_paths_count > 0, got ${md.macPathsCount}")
    }

    @Test
    fun parseFailFixtureThrowsOnParse() {
        val alsPath = Fixtures.writeParseFailProject(tmp.resolve("bad"))
        // Garbage bytes, not gzipped XML — parser should throw.
        runCatching { AlsParser.parse(alsPath) }
            .also { assertTrue(it.isFailure, "expected parser to fail") }
    }

    @Test
    fun sampleCorpusContainsWavs() {
        val corpusDir = Fixtures.writeSampleCorpus(tmp.resolve("corpus"))
        val wavs = Files.walk(corpusDir).use { s ->
            s.filter { it.fileName.toString().endsWith(".wav") }.count()
        }
        assertTrue(wavs >= 2, "expected at least 2 wavs, got $wavs")
    }
}
