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
    /**
     * Who triggered this entry. Persisted into `journal_entries.actor` so the desktop UI and
     * audit log can distinguish user-driven edits from MCP/agent-driven ones. Defaults to
     * `"user"` so existing call sites in `SqlProjectRepository` keep compiling; the MCP
     * subprocess passes `"sketchbook"` (matching `Proposal.actor`).
     */
    val actor: String = "user",
)

/**
 * Action variant + before/after state. New variants land as features add (PR-8).
 *
 * **`typeKey` contract.** Each variant carries a stable string identifier persisted into
 * `journal_entries.action_type`. Do NOT derive these from `::class.simpleName` — under R8 / future
 * obfuscation those become `a`/`b`/`c` and break SQL filters and human-readable audit logs. The
 * abstract property forces every new variant to declare its own key.
 */
@Serializable
sealed interface ActionRecord {

    val typeKey: String

    @Serializable
    data class Move(
        val pathBefore: String,
        val pathAfter: String,
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "Move" }
    }

    @Serializable
    data class Rename(
        val nameBefore: String,
        val nameAfter: String,
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "Rename" }
    }

    @Serializable
    data class Archive(
        val wasArchived: Boolean,
        val isArchived: Boolean,
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "Archive" }
    }

    @Serializable
    data class SetTags(
        val before: List<String>,
        val after: List<String>,
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "SetTags" }
    }

    /** Force-take of a lease lock from another host. Carries the prior owner for audit. */
    @Serializable
    data class ForceTakeLock(
        val priorOwnerHostName: String?,
        val priorExpiresAtMs: Long?,
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "ForceTakeLock" }
    }

    /**
     * Push attempt CAS-failed because the cloud HEAD diverged. Surfaces in the journal so the
     * user can see why a sync stalled, and so future telemetry can count divergence rates.
     */
    @Serializable
    data class PushConflict(
        val ourRev: Long,
        val theirRev: Long,
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "PushConflict" }
    }

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
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "MissingSampleMapped" }
    }

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
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "MissingSampleUnmapped" }
    }

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
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "MacPathRepaired" }
    }

    /**
     * User edited a snapshot's label inline on the Timeline. Carries [rev] so the audit log can
     * tie the entry to a specific row, plus before/after labels for diffing. The accompanying SQL
     * also promotes `kind` to `'named'`, so an `Auto` row that gets a hand-written label becomes
     * a Named snapshot — consistent with PR-O O4's CoalesceJob — but we only record the label
     * delta here; the kind promotion is implicit and visible in subsequent reads.
     *
     * Empty/blank [newLabel] is the "clear the label" gesture, not a no-op: the UI still wants
     * the row to read as Named so the user's intent ("I noticed this row, I'm pinning it") is
     * preserved. Repository callers should pass `""` for cleared and `null` only when they
     * genuinely don't have a value.
     */
    @Serializable
    data class SnapshotRelabeled(
        val rev: Long,
        val labelBefore: String?,
        val labelAfter: String?,
        /** Prior `kind` column ("auto" | "named" | "branch") — useful for telemetry on how
         *  often hand-edits promote auto-saves vs. retitle named takes. */
        val kindBefore: String,
    ) : ActionRecord {
        override val typeKey: String get() = TYPE_KEY
        companion object { const val TYPE_KEY: String = "SnapshotRelabeled" }
    }
}
