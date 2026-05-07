package com.sketchbook.als

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList
import kotlin.test.Test

/**
 * Diagnostic-only sweep: walks an entire library of `.als` files and prints a tabulated report
 * (Live version distribution, OriginalFileRef sibling presence, missing metadata, Mac-path counts,
 * parse failures). Always passes — the goal is to inform follow-up work, not gate the build.
 *
 * **Disabled by default.** The test is gated on the `SKETCHBOOK_LIBRARY_ROOT` environment variable;
 * unset means the body short-circuits with a stdout note (still passes). Set
 * `SKETCHBOOK_LIBRARY_ROOT=/path/to/Ableton` to enable the sweep.
 *
 * Stays within the parser-als test classpath: only stdlib `java.nio.file.Files.walk` + the existing
 * [AlsParser]. No new dependencies.
 */
class LibraryStatTest {
    @Test
    fun reportLibraryDistribution() {
        val root = System.getenv("SKETCHBOOK_LIBRARY_ROOT")
        if (root.isNullOrBlank()) {
            // Soft-skip: kotlin("test") on JVM defaults to JUnit 4 in this build, so we don't pull
            // in junit-jupiter just for `assumeTrue`. Diagnostic test always passes; the env var
            // is the on-switch.
            println("LibraryStatTest disabled: SKETCHBOOK_LIBRARY_ROOT is not set.")
            return
        }
        val rootPath: Path = File(root).toPath()
        if (!Files.isDirectory(rootPath)) {
            println("LibraryStatTest disabled: SKETCHBOOK_LIBRARY_ROOT is not a directory: $rootPath")
            return
        }

        val alsFiles: List<Path> =
            Files.walk(rootPath).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".als") }
                    .toList()
            }

        var hasSibling = 0
        var noSibling = 0
        var missingSize = 0
        var missingCrc = 0
        var hasMacPaths = 0
        val versionCounts = HashMap<String, Int>()
        val parseFailures = ArrayList<Pair<Path, String>>()

        for (p in alsFiles) {
            try {
                val md = Files.newInputStream(p).use { AlsParser.parse(it) }
                val v = md.lastSavedLiveVersion ?: "(unknown)"
                versionCounts[v] = (versionCounts[v] ?: 0) + 1
                if (md.macPathsCount > 0) hasMacPaths++
                for (s in md.sampleRefs) {
                    if (s.hasOriginalFileRefSibling) hasSibling++ else noSibling++
                    if (s.originalFileSize == null) missingSize++
                    if (s.originalCrc == null) missingCrc++
                }
            } catch (t: Throwable) {
                parseFailures += p to (t.message ?: t::class.java.simpleName)
            }
        }

        // Print to stdout so a `--info` test invocation surfaces it. The diagnostic always passes.
        println("=== Sketchbook Library Stat ===")
        println("Root: $rootPath")
        println("Total .als found: ${alsFiles.size}")
        println()
        println("Live version distribution (top 10):")
        versionCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .forEach { (v, c) -> println("  $v -> $c") }
        println()
        println("hasOriginalFileRefSibling=true SampleRefs: $hasSibling")
        println("hasOriginalFileRefSibling=false SampleRefs: $noSibling")
        println("SampleRefs with originalFileSize == null: $missingSize")
        println("SampleRefs with originalCrc == null: $missingCrc")
        println("Projects with macPathsCount > 0: $hasMacPaths")
        println()
        println("Parse failures: ${parseFailures.size}")
        parseFailures.take(5).forEach { (p, msg) -> println("  $p :: $msg") }
        println("===============================")
    }
}
