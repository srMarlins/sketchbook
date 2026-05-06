package com.sketchbook.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotRev
import kotlinx.coroutines.flow.Flow

/**
 * Snapshot history per project. The pull poller and snapshot pipeline write here; the timeline
 * UI reads from here.
 */
interface SnapshotRepository {

    /** Live history for a project, newest revision first. */
    fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>>

    /**
     * Persist a snapshot row from a manifest (or a locally-just-uploaded one). Idempotent —
     * an existing `(uuid, rev)` is a no-op.
     */
    suspend fun recordSnapshot(snapshot: Snapshot, manifestPath: String, manifestHash: String): Result<Unit>

    /**
     * Materialize a project's working tree at the given rev. The actual sync work lives in the
     * sync engine (PR-9); this is the entry point repositories expose to the UI.
     */
    suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit>

    /**
     * PR-Z Z1: edit the human-readable label on a snapshot row. Side-effect: promotes `kind` to
     * `Named` so the row stops being a candidate for auto-coalescing (matches PR-O O4 semantics).
     * [label] may be `null` or `""` to clear; both forms persist as `NULL`/`""` respectively per
     * the SQL column. Implementations append a [JournalEntry] with [ActionRecord.SnapshotRelabeled]
     * so the audit log captures the edit.
     */
    suspend fun setSnapshotLabel(uuid: ProjectUuid, rev: SnapshotRev, label: String?): Result<JournalEntry>

    /**
     * Streaming variant of [materializeAt] for the rewind/restore UI: emits per-stage progress
     * so the user sees what's happening when blobs aren't cached. Default impl wraps
     * [materializeAt] with synthetic Start/Done events; real impls (sync engine in PR-9) emit
     * granular per-blob progress.
     */
    fun materializeAtWithProgress(uuid: ProjectUuid, rev: SnapshotRev): Flow<MaterializationProgress> = kotlinx.coroutines.flow.flow {
        emit(MaterializationProgress.Started(uuid, rev))
        val r = materializeAt(uuid, rev)
        if (r.isSuccess) {
            emit(MaterializationProgress.Done(uuid, rev))
        } else {
            emit(MaterializationProgress.Failed(uuid, rev, friendlyReason(r.exceptionOrNull())))
        }
    }
}

/**
 * Human-friendly failure reason for the rewind UI. The busy case is matched by simple class
 * name so commonMain doesn't have to depend on the JVM-only `WorkingTreeBusyException` type.
 */
private fun friendlyReason(cause: Throwable?): String = when {
    cause == null -> "materialize failed"

    cause::class.simpleName == "WorkingTreeBusyException" ->
        "Close the project in Ableton, then try again."

    else -> cause.message ?: "materialize failed"
}

sealed interface MaterializationProgress {
    val uuid: ProjectUuid
    val rev: SnapshotRev

    data class Started(override val uuid: ProjectUuid, override val rev: SnapshotRev) : MaterializationProgress

    /** A blob is being downloaded from cloud. */
    data class Downloading(
        override val uuid: ProjectUuid,
        override val rev: SnapshotRev,
        val bytesDone: Long,
        val bytesTotal: Long,
        val blobsRemaining: Int,
    ) : MaterializationProgress

    /** Local file laydown — hardlinks/copies into the working tree. */
    data class WritingFiles(
        override val uuid: ProjectUuid,
        override val rev: SnapshotRev,
        val filesDone: Int,
        val filesTotal: Int,
    ) : MaterializationProgress

    data class Done(override val uuid: ProjectUuid, override val rev: SnapshotRev) : MaterializationProgress
    data class Failed(override val uuid: ProjectUuid, override val rev: SnapshotRev, val reason: String) : MaterializationProgress
}
