package com.sketchbook.repo

import com.sketchbook.core.ProjectId
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Append-only record of a state change on a project. Mirrors the v0.1 Python journal so the
 * reference implementation and Kotlin can read each other's entries during the parity period.
 *
 * The wire-format JSON representation lives alongside in `actions` (PR-8). This module only
 * defines the in-memory shape that repositories return from writes.
 */
@Serializable
data class JournalEntry(
    val timestamp: Instant,
    val projectId: ProjectId,
    val action: ActionRecord,
    /** Optional: machine-local sequence id, if persisted. `null` for in-memory entries. */
    val sequence: Long? = null,
)

/** Action variant + before/after state. New variants land as features add (PR-8). */
@Serializable
sealed interface ActionRecord {

    @Serializable
    data class Move(
        val pathBefore: String,
        val pathAfter: String,
    ) : ActionRecord

    @Serializable
    data class Rename(
        val nameBefore: String,
        val nameAfter: String,
    ) : ActionRecord

    @Serializable
    data class Archive(
        val wasArchived: Boolean,
        val isArchived: Boolean,
    ) : ActionRecord

    @Serializable
    data class SetTags(
        val before: List<String>,
        val after: List<String>,
    ) : ActionRecord

    /** Force-take of a lease lock from another host. Carries the prior owner for audit. */
    @Serializable
    data class ForceTakeLock(
        val priorOwnerHostName: String?,
        val priorExpiresAtMs: Long?,
    ) : ActionRecord

    /**
     * Push attempt CAS-failed because the cloud HEAD diverged. Surfaces in the journal so the
     * user can see why a sync stalled, and so future telemetry can count divergence rates.
     */
    @Serializable
    data class PushConflict(
        val ourRev: Long,
        val theirRev: Long,
    ) : ActionRecord

    /**
     * User mapped a missing sample reference to a concrete file on disk. The catalog is updated
     * unconditionally; [alsOutcome] records what happened to the `.als` file itself (Patched on
     * the happy path, SkippedBusy when Live has the file open, Failed on I/O error). PR-W W4
     * uses this entry as the breadcrumb for "Undo to disk".
     */
    @Serializable
    data class MissingSampleMapped(
        val missingPath: String,
        val candidatePath: String,
        /** One of `AlsPatchService.Outcome` names: `Patched` | `NoChange` | `SkippedBusy` | `Failed`. */
        val alsOutcome: String,
    ) : ActionRecord

    /**
     * User undid a previous `MissingSampleMapped` action. Symmetric counterpart: catalog row is
     * reverted to is_missing=1 and [alsOutcome] records the on-disk restore outcome. The extra
     * sentinel [alsOutcome] value `NoUndoBytes` flags the case where no pre-patch sidecar was
     * found (e.g. the apply happened before W4 shipped, or the sidecar got cleaned up); the
     * catalog still reverts so the finding re-surfaces in Needs Attention.
     */
    @Serializable
    data class MissingSampleUnmapped(
        val missingPath: String,
        val candidatePath: String,
        /** One of `AlsPatchService.Outcome` names plus `NoUndoBytes` for the sidecar-missing case. */
        val alsOutcome: String,
    ) : ActionRecord

    /**
     * User repaired Mac-style absolute paths (e.g. `Macintosh HD:/Users/jay/...`) inside a
     * project's `.als` by stripping the volume prefix down to a POSIX path. PR-W W5 reuses the
     * same patcher pipe as missing-sample Apply: the catalog's mac-import finding is
     * acknowledged (drops out of Needs Attention) and [alsOutcome] records what happened to the
     * file (`Patched` on the happy path, `NoChange` if no Mac-style paths were actually present,
     * `SkippedBusy` when Live has the file open, `Failed` on I/O error).
     *
     * [mappingCount] is the number of distinct Mac-style paths the rewriter was asked to
     * substitute — useful for telemetry and for tying journal entries back to the
     * `mac_paths_count` snapshot from the last scan. Zero is legal: the parser counts POSIX
     * `/Users/`-prefix paths in `mac_paths_count` too, so a project can be flagged in Needs
     * Attention without actually carrying any `Volume:`-prefixed paths.
     */
    @Serializable
    data class MacPathRepaired(
        val mappingCount: Int,
        /** One of `AlsPatchService.Outcome` names. */
        val alsOutcome: String,
    ) : ActionRecord
}
