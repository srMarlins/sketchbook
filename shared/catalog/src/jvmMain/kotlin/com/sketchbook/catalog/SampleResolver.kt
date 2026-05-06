package com.sketchbook.catalog

import com.sketchbook.core.SampleRef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves `<SampleRef>` paths against the on-disk project. Mirrors
 * `packages/core/audio_core/db/projects.py::_sample_exists_with_size`.
 *
 * Live writes sample paths in two flavors:
 *  - **Absolute** (Live's *Collected and Saved* mode, or a sample picked from elsewhere on
 *    disk). The path is checked verbatim. Mac-style paths (`/Volumes/...`, `/Users/...`) on
 *    Windows obviously don't resolve; they get counted as missing here, which feeds the
 *    Needs-Attention macOS-path repair flow.
 *  - **Relative** to the project directory (older Live versions, or samples in `Samples/`).
 *    The path is resolved against [projectDir] before checking.
 */
object SampleResolver {

    data class Resolution(val foundCount: Int, val missingCount: Int) {
        val total: Int get() = foundCount + missingCount
    }

    /** Run the resolver across [refs] for a project rooted at [projectDir]. */
    fun resolve(refs: List<SampleRef>, projectDir: Path): Resolution {
        var found = 0
        var missing = 0
        for (ref in refs) {
            if (existsForRef(ref.rawPath, projectDir)) found++ else missing++
        }
        return Resolution(found, missing)
    }

    /** Per-ref miss detection. Returns true if the file exists on disk. */
    fun exists(rawPath: String, projectDir: Path): Boolean = existsForRef(rawPath, projectDir)

    private fun existsForRef(rawPath: String, projectDir: Path): Boolean {
        if (rawPath.isBlank()) return false
        return try {
            val p = Paths.get(rawPath)
            val resolved = if (p.isAbsolute) p else projectDir.resolve(p)
            Files.isRegularFile(resolved)
        } catch (_: Throwable) {
            // Malformed path (invalid Windows characters, etc.) → treat as missing rather
            // than crashing the parse; the row still records the raw path so the user can
            // see what Live wrote.
            false
        }
    }
}
