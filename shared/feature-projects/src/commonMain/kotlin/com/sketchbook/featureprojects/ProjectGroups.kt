package com.sketchbook.featureprojects

import com.sketchbook.core.ParseStatus
import com.sketchbook.core.ProjectRow

/**
 * One folder of `.als` files = one [ProjectGroup]. The [representative] is the most recently
 * touched variant (the one a user is most likely to want to open); [variants] is the full list
 * sorted newest-first. Grouping mirrors the web's `deriveProjectGroups` — projects in the
 * same project root with similar base names are folded together so the dashboard surfaces one
 * row per song instead of one row per `vN.als`.
 *
 * The group exposes derived fields the home shelves need:
 *  - [effortScore] = max across variants (matching `web/src/lib/project-groups.ts` behavior).
 *  - [updatedAtMs] = newest variant's `updatedAt` so a single old representative can't hide a
 *    fresh sibling.
 *  - [parseStatusBest] = `Ok` if any variant parsed cleanly, else `Failed` if any failed,
 *    else `Pending`.
 *  - [missingSampleCount] = max across variants (one truly broken sibling marks the group).
 */
data class ProjectGroup(
    val id: String,
    val representative: ProjectRow,
    val variants: List<ProjectRow>,
    val effortScore: Int?,
    val updatedAtMs: Long,
    val parseStatusBest: ParseStatus,
    val missingSampleCount: Int,
) {
    val variantCount: Int get() = variants.size
}

/**
 * Group rows by project root (the folder containing the `.als`, walking past Live's
 * `Backup/Samples/Ableton Project Info` subfolders). Within a group rows are sorted by
 * `updatedAt` descending; the head is the [representative]. Group-level fields are derived
 * via min/max across variants per the rules documented on [ProjectGroup].
 */
fun deriveProjectGroups(rows: List<ProjectRow>): List<ProjectGroup> {
    val byRoot = rows.groupBy { projectRootDir(it.path.value) }
    return byRoot
        .map { (rootDir, group) ->
            val sorted = group.sortedByDescending { it.updatedAt }
            val rep = sorted.first()
            val maxEffort = sorted.mapNotNull { it.effortScore }.maxOrNull()
            val newestMs = sorted.maxOf { it.updatedAt.toEpochMilliseconds() }
            val anyOk = sorted.any { it.parseStatus == ParseStatus.Ok }
            val anyFailed = sorted.any { it.parseStatus == ParseStatus.Failed }
            val bestStatus =
                when {
                    anyOk -> ParseStatus.Ok
                    anyFailed -> ParseStatus.Failed
                    else -> ParseStatus.Pending
                }
            val maxMissing = sorted.maxOf { it.missingSampleCount }
            ProjectGroup(
                id = rootDir,
                representative = rep,
                variants = sorted,
                effortScore = maxEffort,
                updatedAtMs = newestMs,
                parseStatusBest = bestStatus,
                missingSampleCount = maxMissing,
            )
        }.sortedByDescending { it.updatedAtMs }
}

/**
 * Walk up from the `.als` file collapsing Live's auto-generated folder names so the displayed
 * root is the *song* folder, not whatever subdir Live placed the file into. Skips `Backup`,
 * `Samples`, and `Ableton Project Info`. Mirrors `RootContent.projectRootDir` so list and
 * detail pane agree.
 */
fun projectRootDir(absPath: String): String {
    val normalized = absPath.replace('\\', '/')
    val skip = setOf("Backup", "Samples", "Ableton Project Info")
    var dir = parentDir(normalized)
    while (true) {
        val name = dir.substringAfterLast('/', dir)
        if (name !in skip) return dir
        val above = parentDir(dir)
        if (above == dir) return dir
        dir = above
    }
}

internal fun parentDir(absPath: String): String {
    val idx = absPath.lastIndexOf('/')
    return if (idx <= 0) absPath else absPath.substring(0, idx)
}
