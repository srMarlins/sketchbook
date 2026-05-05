package com.sketchbook.repo

import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.ProjectUuid
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
}
