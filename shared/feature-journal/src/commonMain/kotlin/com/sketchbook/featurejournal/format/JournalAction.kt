package com.sketchbook.featurejournal.format

import com.sketchbook.repo.ActionRecord

/**
 * Render an [ActionRecord] as a one-line human label for journal rows. The accompanying
 * `.als`-patch outcome is appended in parens for the missing-sample / mac-path-repair entries so
 * the user can see when an apply skipped Live or failed I/O. Empty for the happy path.
 *
 * Returning `String` (not AnnotatedString) — the screen layer already renders this with a single
 * Text style, and pulling compose.ui into this module just for a styled wrapper isn't worth it.
 */
fun humanReadable(action: ActionRecord): String = when (action) {
    is ActionRecord.Move ->
        "Moved ${filenameOf(action.pathAfter)} — ${parentDirOf(action.pathBefore)}/ → ${parentDirOf(action.pathAfter)}/"
    is ActionRecord.Rename ->
        "Renamed ${action.nameBefore} → ${action.nameAfter}"
    is ActionRecord.Archive ->
        if (action.isArchived) "Archived" else "Unarchived"
    is ActionRecord.SetTags ->
        "Tags: ${action.before.joinToString(", ").ifEmpty { "(none)" }} → ${action.after.joinToString(", ").ifEmpty { "(none)" }}"
    is ActionRecord.ForceTakeLock ->
        "Force-took lock from ${action.priorOwnerHostName ?: "unknown host"}"
    is ActionRecord.PushConflict ->
        "Push conflict (ours rev ${action.ourRev} vs theirs rev ${action.theirRev})"
    is ActionRecord.MissingSampleMapped ->
        "Relink ${filenameOf(action.missingPath)} → ${parentDirOf(action.candidatePath)}/" +
            alsOutcomeSuffix(action.alsOutcome)
    is ActionRecord.MissingSampleUnmapped ->
        "Undo relink ${filenameOf(action.missingPath)}" +
            alsOutcomeSuffix(action.alsOutcome)
    is ActionRecord.MacPathRepaired ->
        "Repair Mac paths (${action.mappingCount})" +
            alsOutcomeSuffix(action.alsOutcome)
    is ActionRecord.SnapshotRelabeled -> {
        val before = action.labelBefore?.takeIf { it.isNotBlank() } ?: "(unlabeled)"
        val after = action.labelAfter?.takeIf { it.isNotBlank() } ?: "(unlabeled)"
        "Relabel snapshot $before → $after"
    }
}

private fun filenameOf(path: String): String =
    path.substringAfterLast('/').ifEmpty { path }

private fun parentDirOf(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx <= 0) "" else path.substring(0, idx)
}

private fun alsOutcomeSuffix(outcome: String): String = when (outcome) {
    "Patched", "" -> ""
    "NoChange" -> " (no .als change)"
    "SkippedBusy" -> " (.als open in Live — skipped)"
    "Failed" -> " (.als write failed)"
    "NoUndoBytes" -> " (no pre-patch snapshot — catalog only)"
    else -> " ($outcome)"
}
