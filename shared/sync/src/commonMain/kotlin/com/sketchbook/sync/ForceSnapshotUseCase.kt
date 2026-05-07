package com.sketchbook.sync

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev

/**
 * Records a Named snapshot of [uuid]'s current working tree on demand — independent of the
 * watcher-driven auto-save loop. Used by the desktop's `Ctrl/Cmd+Shift+S` quick-capture hotkey
 * (Z3) so producers can name a take without waiting for the next save coalesce window.
 *
 * The implementation is intentionally a thin abstraction over [SnapshotPipeline]: the use case
 * goes through the pipeline so blob upload, manifest write, and journaling all happen via the
 * normal save path. See [com.sketchbook.desktop.repo.GcsSyncQueue.recordForcedNamed] for the
 * production wiring; tests fake this interface to avoid the GCS dependency.
 */
interface ForceSnapshotPipeline {
    /**
     * Capture a Named snapshot of [uuid]'s current bytes with [label]. Returns the resulting
     * [SnapshotRev] on success. On CAS conflict the underlying pipeline still falls back to a
     * Branch manifest — the divergence path wins over Named, mirroring auto-save semantics.
     */
    suspend fun recordForcedNamed(
        uuid: ProjectUuid,
        label: String,
    ): Result<SnapshotRev>
}

/**
 * Z3 quick-capture use case. Validates the user-supplied [label] (no blank strings — an empty
 * label would defeat the whole point of "name this take") and delegates to [pipeline].
 *
 * No SyncStateStore wiring here: the underlying [ForceSnapshotPipeline] implementation handles
 * `markSynced` after a successful push, same as auto-save. Keeping this commonMain-pure means
 * the use case is testable without dragging the JVM-only catalog into shared tests.
 */
class ForceSnapshotUseCase(
    private val pipeline: ForceSnapshotPipeline,
) {
    suspend operator fun invoke(
        uuid: ProjectUuid,
        label: String,
    ): Result<SnapshotRev> {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("snapshot label must not be blank"))
        }
        return pipeline.recordForcedNamed(uuid, trimmed)
    }
}
