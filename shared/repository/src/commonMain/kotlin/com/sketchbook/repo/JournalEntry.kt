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
}
