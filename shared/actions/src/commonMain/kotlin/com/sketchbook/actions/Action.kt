package com.sketchbook.actions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format action variants. Names + field shapes match the v0.1 Python journal format
 * exactly so both implementations can read each other's `data/journal/<id>.json` files during the
 * parity period.
 *
 * Every action persists into a [JournalBatch] alongside other actions performed in the same
 * user-intent. `Undo` reverses the most recent batch.
 */
@Serializable
sealed interface ActionRecord {
    val projectId: Long

    @Serializable
    @SerialName("MoveProject")
    data class MoveProject(
        @SerialName("project_id") override val projectId: Long,
        @SerialName("from_") val fromPath: String,
        @SerialName("to") val toPath: String,
        /** SHA-256 of `Project.als` at the time of the action; used by Undo to detect drift. */
        @SerialName("hash_before") val hashBefore: String? = null,
        @SerialName("path_before") val pathBefore: String? = null,
        val noop: Boolean = false,
    ) : ActionRecord

    @Serializable
    @SerialName("RenameProject")
    data class RenameProject(
        @SerialName("project_id") override val projectId: Long,
        @SerialName("from_") val fromPath: String,
        @SerialName("to") val toPath: String,
        @SerialName("hash_before") val hashBefore: String? = null,
        @SerialName("path_before") val pathBefore: String? = null,
        val noop: Boolean = false,
    ) : ActionRecord

    @Serializable
    @SerialName("ArchiveProject")
    data class ArchiveProject(
        @SerialName("project_id") override val projectId: Long,
        @SerialName("from_") val fromPath: String,
        @SerialName("to") val toPath: String,
        @SerialName("hash_before") val hashBefore: String? = null,
        @SerialName("path_before") val pathBefore: String? = null,
        @SerialName("was_archived") val wasArchived: Boolean = false,
    ) : ActionRecord

    @Serializable
    @SerialName("SetTags")
    data class SetTags(
        @SerialName("project_id") override val projectId: Long,
        val before: List<String>,
        val after: List<String>,
    ) : ActionRecord

    @Serializable
    @SerialName("SetColorTag")
    data class SetColorTag(
        @SerialName("project_id") override val projectId: Long,
        val before: Int? = null,
        val after: Int? = null,
    ) : ActionRecord

    @Serializable
    @SerialName("Undo")
    data class Undo(
        @SerialName("project_id") override val projectId: Long,
        @SerialName("undid_batch") val undidBatch: String,
    ) : ActionRecord
}
