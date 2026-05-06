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
}
