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
}
