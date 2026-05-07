@file:Suppress("MatchingDeclarationName")

package com.sketchbook.repo

import com.sketchbook.core.ProjectId

/**
 * Hints carried alongside a `project_id` so [resolveProjectDisplay] can survive an orphaned id —
 * e.g. the project row was removed by a catalog rescan or replaced by an upsert that minted a new
 * autoincrement id, but the action payload or denormalized column on the row still carries enough
 * to reconstruct a friendly name.
 */
data class ProjectDisplayHints(
    /** Denormalized name from the row (e.g. `journal_entries.project_name`) or action payload. */
    val denormName: String? = null,
    /** Path hint from the same source. Basename minus `.als` is used if catalog lookup fails. */
    val pathHint: String? = null,
)

/**
 * Single source of truth for resolving a `project_id` to a user-facing display name when the id
 * may be orphaned (catalog rescan, deleted/recreated `.als`, etc.). Surfaces should never inline
 * their own fallback chain — call this.
 *
 * Fallback order:
 *   1. [ProjectDisplayHints.denormName] (captured at write time; survives rescans).
 *   2. [nameById] entry (live catalog map).
 *   3. Basename of [ProjectDisplayHints.pathHint], with a single trailing `.als` stripped.
 *   4. `"project #${id.value}"` sentinel.
 *
 * Always returns a non-null string; the sentinel is reachable but not desirable.
 */
fun resolveProjectDisplay(
    id: ProjectId,
    hints: ProjectDisplayHints = ProjectDisplayHints(),
    nameById: Map<ProjectId, String> = emptyMap(),
): String {
    hints.denormName?.takeUnless { it.isBlank() }?.let { return it }
    nameById[id]?.takeUnless { it.isBlank() }?.let { return it }
    hints.pathHint?.let(::basenameWithoutAls)?.let { return it }
    return "project #${id.value}"
}

/**
 * Strip everything before the last `/` or `\`, then a single trailing `.als`. Returns null if the
 * resulting string is blank — the caller treats that as "no usable basename" and falls through.
 *
 * Mirrors the previous `JournalViewModel.basenameWithoutAls` behavior so the resolver swap is a
 * pure refactor for the journal call sites.
 */
internal fun basenameWithoutAls(path: String): String? {
    val tail = path.substringAfterLast('/').substringAfterLast('\\')
    if (tail.isEmpty()) return null
    return tail.removeSuffix(".als").takeUnless { it.isBlank() }
}
