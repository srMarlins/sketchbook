package com.sketchbook.syncio

/**
 * The User Library exclusion list. Live writes content + auto-generated metadata + OS junk
 * into the same root; Sketchbook syncs only the *content*. Skip-set is exact-name matching
 * for directories (any path component) and pattern matching for files.
 *
 * **Bias.** Conservative — under-skipping just sends a few KB of cache files we'd rather not
 * have, never breaks sync. Over-skipping silently drops user content, which is much worse.
 * If you're unsure whether to add a pattern here, leave it out.
 *
 * See `docs/plans/2026-05-07-backend-generalization-design.md` § "UserLibrary skip-set" for
 * the full rationale.
 */
internal object UserLibrarySkipSet {
    /** Directories whose entire subtree is excluded (matched by exact name on any path component). */
    val SKIP_DIRS: Set<String> =
        setOf(
            "Ableton Project Info", // Live's auto-generated UL metadata; same as in projects.
            "Cache", // Historical — Live ≤10. Defensive for users who upgraded.
            ".fseventsd", // macOS Spotlight indexer.
            ".Spotlight-V100", // macOS Spotlight indexer.
            ".Trashes", // macOS trash on external volumes.
            "\$RECYCLE.BIN", // Windows recycle bin if user library on a separate drive.
        )

    /** File-name patterns excluded everywhere. */
    val SKIP_FILE_NAMES: Set<String> = setOf(".DS_Store", "Thumbs.db", "desktop.ini")

    val SKIP_FILE_PREFIXES: List<String> =
        listOf(
            ".", // Dotfiles — macOS resource forks, transient editor temps.
            "~$", // Office-style locks (some plugin browsers create these).
        )

    val SKIP_FILE_SUFFIXES: List<String> =
        listOf(
            ".als.bak", // Live autosave.
            ".tmp",
        )

    /**
     * True when [components] (project-relative path split on `/`) should be excluded.
     * Centralizes the policy so callers — both the working tree walker (commit 11) and the
     * future activity dashboard's "what was filtered" diagnostic — agree on the answer.
     */
    fun isSkipped(components: List<String>): Boolean {
        if (components.isEmpty()) return false
        if (components.dropLast(1).any { it in SKIP_DIRS }) return true
        val last = components.last()
        if (last in SKIP_FILE_NAMES) return true
        if (SKIP_FILE_PREFIXES.any { last.startsWith(it) }) return true
        if (SKIP_FILE_SUFFIXES.any { last.endsWith(it, ignoreCase = true) }) return true
        return false
    }
}
