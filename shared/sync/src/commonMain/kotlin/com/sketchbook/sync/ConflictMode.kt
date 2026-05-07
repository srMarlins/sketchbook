package com.sketchbook.sync

/**
 * Strategy applied when a CAS write to a tree's manifest HEAD loses to a concurrent writer.
 *
 * - [BranchFork]: re-fetch HEAD, write our work as a divergent branch (`auto-fork: …` label).
 *   Used by `Project` kind where multi-host concurrent edits to the same project are rare and
 *   when they happen the user wants to see both versions.
 * - [Merge]: re-fetch HEAD's manifest, merge file-by-file (LWW per relpath), and re-CAS at the
 *   next rev. Tombstones survive the merge so deletions don't get re-added. Used by `UserLibrary`
 *   kind where filesystem state is bursty and divergent file maps are normal.
 */
sealed interface ConflictMode {
    data object BranchFork : ConflictMode

    data class Merge(val deletePolicy: DeletePolicy) : ConflictMode
}

/** How tombstones flow through merges. Only consulted when [ConflictMode.Merge]. */
sealed interface DeletePolicy {
    /** Tombstone entries (`deleted = true`) survive merges so a delete on one host beats a stale add. */
    data object Tombstones : DeletePolicy

    /** Reserved for additive-only kinds (none today). Tombstones are silently dropped. */
    data object IgnoreDeletes : DeletePolicy
}
