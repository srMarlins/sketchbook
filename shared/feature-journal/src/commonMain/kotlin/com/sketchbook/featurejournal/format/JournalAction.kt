package com.sketchbook.featurejournal.format

import com.sketchbook.repo.ActionRecord
import com.sketchbook.uishared.components.VerbTint

/**
 * Structured row label for a journal entry — mirrors the proposals queue's `ProposalLabel` so
 * both surfaces render the same `[VERB pill] [bold target] [caption detail]` layout. Returning
 * a structured value lets the screen color the pill via [tintHint] and ellipsis the target
 * independently from the trailing detail.
 */
data class JournalLabel(
    val verb: String,
    val target: String,
    val detail: String? = null,
    val tintHint: VerbTint = VerbTint.Neutral,
)

/**
 * Maps an [ActionRecord] to the verb/target/detail shape the row UI consumes. The `.als`-patch
 * outcome (when present) flows into [detail] so users see at a glance whether an apply skipped
 * Live or failed I/O.
 */
fun journalLabel(action: ActionRecord): JournalLabel = when (action) {
    is ActionRecord.Move -> JournalLabel(
        verb = "Move",
        target = filenameOf(action.pathAfter),
        detail = "${parentDirOf(action.pathBefore)}/ → ${parentDirOf(action.pathAfter)}/",
        tintHint = VerbTint.Action,
    )
    is ActionRecord.Rename -> JournalLabel(
        verb = "Rename",
        target = action.nameAfter,
        detail = "← ${action.nameBefore}",
        tintHint = VerbTint.Action,
    )
    is ActionRecord.Archive -> JournalLabel(
        verb = if (action.isArchived) "Archive" else "Unarchive",
        target = "",
        tintHint = if (action.isArchived) VerbTint.Remove else VerbTint.Add,
    )
    is ActionRecord.SetTags -> JournalLabel(
        verb = "Tag",
        target = action.after.joinToString(", ").ifEmpty { "(none)" },
        detail = "← ${action.before.joinToString(", ").ifEmpty { "(none)" }}",
        tintHint = if (action.after.isEmpty()) VerbTint.Remove else VerbTint.Add,
    )
    is ActionRecord.ForceTakeLock -> JournalLabel(
        verb = "Lock",
        target = "force-take",
        detail = action.priorOwnerHostName?.let { "from $it" },
        tintHint = VerbTint.Neutral,
    )
    is ActionRecord.PushConflict -> JournalLabel(
        verb = "Conflict",
        target = "push diverged",
        detail = "ours rev ${action.ourRev} vs theirs rev ${action.theirRev}",
        tintHint = VerbTint.Remove,
    )
    is ActionRecord.MissingSampleMapped -> JournalLabel(
        verb = "Relink",
        target = filenameOf(action.missingPath),
        detail = "→ ${parentDirOf(action.candidatePath)}/${alsOutcomeSuffix(action.alsOutcome)}",
        tintHint = VerbTint.Repair,
    )
    is ActionRecord.MissingSampleUnmapped -> JournalLabel(
        verb = "Undo relink",
        target = filenameOf(action.missingPath),
        detail = alsOutcomeSuffix(action.alsOutcome).trim().ifEmpty { null },
        tintHint = VerbTint.Repair,
    )
    is ActionRecord.MacPathRepaired -> JournalLabel(
        verb = "Repair",
        target = "${action.mappingCount} mac paths",
        detail = alsOutcomeSuffix(action.alsOutcome).trim().ifEmpty { null },
        tintHint = VerbTint.Repair,
    )
    is ActionRecord.SnapshotRelabeled -> {
        val before = action.labelBefore?.takeIf { it.isNotBlank() } ?: "(unlabeled)"
        val after = action.labelAfter?.takeIf { it.isNotBlank() } ?: "(unlabeled)"
        JournalLabel(
            verb = "Relabel",
            target = after,
            detail = "← $before",
            tintHint = VerbTint.Action,
        )
    }
}

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
