package com.sketchbook.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.runCatchingCancellable
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
     * an existing `(uuid, rev)` is a no-op. Throws [SketchbookError.IoFailure] on catalog write
     * failure.
     */
    @Throws(SketchbookError::class)
    suspend fun recordSnapshot(
        snapshot: Snapshot,
        manifestPath: String,
        manifestHash: String,
    )

    /**
     * Materialize a project's working tree at the given rev. Returns a sealed
     * [MaterializeOutcome]: `Materialized` on full laydown, `WorkingTreeBusy(paths)` when one or
     * more destination files are held open by Ableton (caller decides whether to surface the
     * "close in Ableton and retry" message). Throws [SketchbookError.IoFailure] on transport /
     * disk failures, `IntegrityError` on manifest checksum mismatch, etc.
     */
    @Throws(SketchbookError::class)
    suspend fun materializeAt(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): MaterializeOutcome

    /**
     * PR-Z Z1: edit the human-readable label on a snapshot row. Side-effect: promotes `kind` to
     * `Named` so the row stops being a candidate for auto-coalescing (matches PR-O O4 semantics).
     * [label] may be `null` or `""` to clear; both forms persist as `NULL`/`""` respectively per
     * the SQL column. Implementations append a [JournalEntry] with [ActionRecord.SnapshotRelabeled]
     * so the audit log captures the edit. Throws [SketchbookError.NotFound] when the snapshot
     * doesn't exist.
     */
    @Throws(SketchbookError::class)
    suspend fun setSnapshotLabel(
        uuid: ProjectUuid,
        rev: SnapshotRev,
        label: String?,
    ): JournalEntry

    /**
     * Streaming variant of [materializeAt] for the rewind/restore UI: emits per-stage progress
     * so the user sees what's happening when blobs aren't cached. Default impl wraps
     * [materializeAt] with synthetic Start/Done events; real impls (sync engine in PR-9) emit
     * granular per-blob progress.
     */
    fun materializeAtWithProgress(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): Flow<MaterializationProgress> =
        kotlinx.coroutines.flow.flow {
            emit(MaterializationProgress.Started(uuid, rev))
            val r = runCatchingCancellable { materializeAt(uuid, rev) }
            r.onSuccess { outcome ->
                when (outcome) {
                    MaterializeOutcome.Materialized -> emit(MaterializationProgress.Done(uuid, rev))
                    is MaterializeOutcome.WorkingTreeBusy ->
                        emit(MaterializationProgress.Failed(uuid, rev, "Close the project in Ableton, then try again."))
                }
            }.onFailure { cause ->
                emit(MaterializationProgress.Failed(uuid, rev, cause.message ?: "materialize failed"))
            }
        }
}

/**
 * Domain outcomes for [SnapshotRepository.materializeAt] / `ManifestMaterializer.materialize`.
 *
 * `WorkingTreeBusy` is a meaningful branch — when Ableton has one or more destination files
 * open on Windows, the Rewind UI shows a dedicated "Close the project in Live and retry" hint
 * naming the files. Other failures throw a [SketchbookError] subtype so they surface as a
 * generic error toast.
 */
sealed interface MaterializeOutcome {
    /** Full laydown completed; the working tree is at the requested rev. */
    data object Materialized : MaterializeOutcome

    /**
     * One or more files were held open by another process (typically Ableton Live on Windows).
     * The materializer aborted before touching disk so the working tree is unchanged. [paths]
     * lists the busy files (relative to the project root); the UI can surface them.
     */
    data class WorkingTreeBusy(val paths: List<String>) : MaterializeOutcome
}

sealed interface MaterializationProgress {
    val uuid: ProjectUuid
    val rev: SnapshotRev

    data class Started(
        override val uuid: ProjectUuid,
        override val rev: SnapshotRev,
    ) : MaterializationProgress

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

    data class Done(
        override val uuid: ProjectUuid,
        override val rev: SnapshotRev,
    ) : MaterializationProgress

    data class Failed(
        override val uuid: ProjectUuid,
        override val rev: SnapshotRev,
        val reason: String,
    ) : MaterializationProgress
}
