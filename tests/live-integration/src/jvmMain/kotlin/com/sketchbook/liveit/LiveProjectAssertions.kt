package com.sketchbook.liveit

import com.sketchbook.als.AlsParser
import com.sketchbook.core.Manifest
import com.sketchbook.core.SampleRef
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.walk

private const val HASH_BUFFER_BYTES = 64 * 1024
private const val HEX_BYTE_MASK = 0xff
private const val HEX_RADIX = 16
private const val HEX_PAD = 2
private val ABS_PATH_REGEX = Regex("^[A-Za-z]:[\\\\/]")
private const val PREVIEW_MISSING = 10
private const val PREVIEW_MISMATCHES = 10
private const val PREVIEW_SAMPLE_MISSES = 20
private const val PREVIEW_REF_INFO = 5
private const val HASH_DISPLAY_PREFIX = 12

/**
 * Three independent checks the pull-side script runs after materializing a manifest:
 *
 *  1. **Manifest bytes** — re-hash every file under [root] and compare to the manifest entry.
 *     Catches partial materialization, blob corruption, atomic-rename bugs.
 *  2. **`.als` reparse** — walk every `.als` file and feed it through [AlsParser.parse]. Catches
 *     gzip corruption, XML truncation, encoding loss.
 *  3. **Sample-ref resolve** — for each parsed `.als`'s [SampleRef]s, classify by relativePathType
 *     and ensure intra-project refs resolve to real files under [root]. This is the cross-OS
 *     assertion: backslashes in stored paths, NFC/NFD filename divergence, lost separators all
 *     surface here.
 *
 * Returns a [Report] rather than throwing — the caller decides exit code from the
 * `success` flag so it can print a complete summary.
 */
object LiveProjectAssertions {
    fun checkAll(
        root: Path,
        manifest: Manifest,
    ): Report {
        val manifestReport = checkManifestBytes(root, manifest)
        val alsReport = checkAlsReparse(root)
        val sampleReport = checkSampleRefsResolve(root, alsReport.parsed)
        val success =
            manifestReport.mismatches.isEmpty() &&
                manifestReport.missing.isEmpty() &&
                alsReport.failures.isEmpty() &&
                sampleReport.intraProjectMisses.isEmpty()
        return Report(
            success = success,
            manifest = manifestReport,
            als = alsReport,
            samples = sampleReport,
        )
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun checkManifestBytes(
        root: Path,
        manifest: Manifest,
    ): ManifestReport {
        val missing = mutableListOf<String>()
        val mismatches = mutableListOf<HashMismatch>()
        val verified = mutableListOf<String>()
        for ((rel, mf) in manifest.files) {
            val path = root.resolve(rel)
            if (!Files.isRegularFile(path)) {
                missing += rel
                continue
            }
            val actualHex = sha256Hex(path)
            val expectedHex = mf.hash.value.removePrefix("sha256:")
            if (actualHex != expectedHex) {
                mismatches += HashMismatch(rel = rel, expectedHex = expectedHex, actualHex = actualHex)
            } else {
                verified += rel
            }
        }
        // Extra files not in the manifest aren't a failure (LIVE writes .asd analysis files
        // on first open; some workflows leave .DS_Store on macOS). The manifest is the
        // ground truth; we only fail on missing or corrupted entries.
        return ManifestReport(verified = verified, missing = missing, mismatches = mismatches)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun checkAlsReparse(root: Path): AlsReport {
        val parsed = mutableMapOf<Path, com.sketchbook.core.ProjectMetadata>()
        val failures = mutableListOf<AlsFailure>()
        Files
            .walk(root)
            .use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.extension.equals("als", ignoreCase = true) }
                    .forEach { als ->
                        runCatching { AlsParser.parse(als) }
                            .onSuccess { parsed[als] = it }
                            .onFailure { failures += AlsFailure(als, it.message ?: it::class.simpleName ?: "unknown") }
                    }
            }
        return AlsReport(parsed = parsed, failures = failures)
    }

    private fun checkSampleRefsResolve(
        root: Path,
        parsed: Map<Path, com.sketchbook.core.ProjectMetadata>,
    ): SampleReport {
        val intraProjectHits = mutableListOf<SampleHit>()
        val intraProjectMisses = mutableListOf<SampleMiss>()
        val libraryRefs = mutableListOf<SampleHit>()
        val absoluteRefs = mutableListOf<SampleHit>()
        for ((alsPath, meta) in parsed) {
            val alsDir = alsPath.parent ?: continue
            for (ref in meta.sampleRefs) {
                val classification = classify(ref)
                when (classification) {
                    is RefClass.Absolute -> {
                        absoluteRefs += SampleHit(alsPath, ref, classification.raw)
                    }

                    is RefClass.Library -> {
                        libraryRefs += SampleHit(alsPath, ref, classification.raw)
                    }

                    is RefClass.IntraProject -> {
                        // Try multiple separator normalizations so a Windows-stored path
                        // (backslashes) still resolves on macOS, and vice versa.
                        val resolved = resolveAgainst(alsDir, root, classification.relPath)
                        if (resolved != null) {
                            intraProjectHits += SampleHit(alsPath, ref, resolved.toString())
                        } else {
                            intraProjectMisses += SampleMiss(alsPath, ref)
                        }
                    }
                }
            }
        }
        return SampleReport(
            intraProjectHits = intraProjectHits,
            intraProjectMisses = intraProjectMisses,
            libraryRefs = libraryRefs,
            absoluteRefs = absoluteRefs,
        )
    }

    private fun classify(ref: SampleRef): RefClass {
        val raw = ref.rawPath
        // Live encodes relativePathType: 0 = absolute, 1 = relative-to-project,
        // 3 = relative-to-library (per the AlsRewriter rewrite invariants). When the
        // parser can't determine type we infer from the raw path shape.
        return when (ref.relativePathType) {
            RelativePathType.ABSOLUTE -> {
                RefClass.Absolute(raw)
            }

            RelativePathType.LIBRARY -> {
                RefClass.Library(raw)
            }

            RelativePathType.PROJECT -> {
                RefClass.IntraProject(raw)
            }

            else -> {
                when {
                    raw.startsWith("/") || ABS_PATH_REGEX.containsMatchIn(raw) -> {
                        RefClass.Absolute(raw)
                    }

                    else -> {
                        RefClass.IntraProject(raw)
                    }
                }
            }
        }
    }

    private fun resolveAgainst(
        alsDir: Path,
        root: Path,
        relPath: String,
    ): Path? {
        // Project-relative refs are conventionally relative to the .als's parent. We try
        // that first; some edge cases (Live re-saving from a subfolder) store paths relative
        // to the project root, so we fall back to that.
        val normalized = relPath.replace('\\', '/').removePrefix("./")
        // Try resolution against the .als's parent first (the standard project-relative
        // convention), then fall back to the project root. Try the normalized (forward-slash)
        // form first; on macOS that's required to interpret a Windows-stored path, on Windows
        // it's harmless because `Path.resolve` accepts forward slashes too.
        val candidates =
            listOf(
                alsDir.resolve(normalized).normalize(),
                root.resolve(normalized).normalize(),
                alsDir.resolve(relPath).normalize(),
                root.resolve(relPath).normalize(),
            )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    private fun sha256Hex(path: Path): String = sha256HexFile(path)

    /**
     * Live's `<RelativePathType>` field values, per `AlsRewriter`. `0` is an absolute path
     * (usually a sample outside any project); `1` is project-relative (intra-project, the
     * cross-OS-portable kind); `3` is library-relative (Ableton library — resolvable only
     * on a machine with that library installed).
     */
    private object RelativePathType {
        const val ABSOLUTE = 0
        const val PROJECT = 1
        const val LIBRARY = 3
    }

    private sealed interface RefClass {
        data class IntraProject(
            val relPath: String,
        ) : RefClass

        data class Library(
            val raw: String,
        ) : RefClass

        data class Absolute(
            val raw: String,
        ) : RefClass
    }

    data class HashMismatch(
        val rel: String,
        val expectedHex: String,
        val actualHex: String,
    )

    data class ManifestReport(
        val verified: List<String>,
        val missing: List<String>,
        val mismatches: List<HashMismatch>,
    )

    data class AlsFailure(
        val path: Path,
        val message: String,
    )

    data class AlsReport(
        val parsed: Map<Path, com.sketchbook.core.ProjectMetadata>,
        val failures: List<AlsFailure>,
    )

    data class SampleHit(
        val als: Path,
        val ref: SampleRef,
        val resolvedTo: String,
    )

    data class SampleMiss(
        val als: Path,
        val ref: SampleRef,
    )

    data class SampleReport(
        val intraProjectHits: List<SampleHit>,
        val intraProjectMisses: List<SampleMiss>,
        val libraryRefs: List<SampleHit>,
        val absoluteRefs: List<SampleHit>,
    )

    data class Report(
        val success: Boolean,
        val manifest: ManifestReport,
        val als: AlsReport,
        val samples: SampleReport,
    ) {
        fun printSummary() {
            println()
            println("=== Live integration assertions ===")
            println(
                "[manifest] verified=${manifest.verified.size} " +
                    "missing=${manifest.missing.size} mismatched=${manifest.mismatches.size}",
            )
            manifest.missing.take(PREVIEW_MISSING).forEach { println("  MISSING $it") }
            if (manifest.missing.size > PREVIEW_MISSING) {
                println("  … and ${manifest.missing.size - PREVIEW_MISSING} more")
            }
            manifest.mismatches.take(PREVIEW_MISMATCHES).forEach {
                println(
                    "  HASH    ${it.rel}: " +
                        "expected=${it.expectedHex.take(HASH_DISPLAY_PREFIX)}… " +
                        "actual=${it.actualHex.take(HASH_DISPLAY_PREFIX)}…",
                )
            }
            println("[als]      parsed=${als.parsed.size} failures=${als.failures.size}")
            als.failures.forEach { println("  PARSE   ${it.path}: ${it.message}") }
            println(
                "[samples]  intraProject hit=${samples.intraProjectHits.size} miss=${samples.intraProjectMisses.size}" +
                    "  library=${samples.libraryRefs.size}  absolute=${samples.absoluteRefs.size}",
            )
            samples.intraProjectMisses.take(PREVIEW_SAMPLE_MISSES).forEach { miss ->
                println("  SAMPLE  ${miss.als.fileName}: '${miss.ref.rawPath}' (relType=${miss.ref.relativePathType})")
            }
            if (samples.intraProjectMisses.size > PREVIEW_SAMPLE_MISSES) {
                println("  … and ${samples.intraProjectMisses.size - PREVIEW_SAMPLE_MISSES} more")
            }
            // Absolute and library refs are reported but not failed — they're machine-dependent
            // by design (a sample under /Users/.../Ableton library is only resolvable on a
            // machine where that library is installed). The cross-OS round-trip we care about
            // is intra-project resolution.
            samples.absoluteRefs.take(PREVIEW_REF_INFO).forEach { hit ->
                println("  (info) absolute ref: '${hit.ref.rawPath}'")
            }
            samples.libraryRefs.take(PREVIEW_REF_INFO).forEach { hit ->
                println("  (info) library ref:  '${hit.ref.rawPath}'")
            }
            println()
            println(if (success) "RESULT: PASS — open the project in Live to confirm." else "RESULT: FAIL")
        }
    }
}

internal fun sha256HexFile(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") {
        (it.toInt() and HEX_BYTE_MASK).toString(HEX_RADIX).padStart(HEX_PAD, '0')
    }
}
