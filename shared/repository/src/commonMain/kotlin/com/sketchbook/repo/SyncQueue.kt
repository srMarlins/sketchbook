package com.sketchbook.repo

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.flow.Flow

/**
 * Cloud-sync queue + state. Kept tiny at v1 — surfaces enough for the UI to render the per-row
 * pip, the sidebar status, and the Settings/cloud pane. The real upload/download orchestration
 * lives in `:shared:sync` (see `SnapshotPipeline`); this is the read model the UI binds to.
 *
 * Per-project state:
 *  - **Synced** — every local variant matches a remote snapshot.
 *  - **Pending** — there are local variants newer than the remote head; an upload is queued.
 *  - **Uploading** — actively uploading.
 *  - **Conflict** — remote head and local head have diverged; user attention required.
 *  - **LocalOnly** — never reached the cloud.
 *  - **Unknown** — not yet checked since launch.
 */
interface SyncQueue {
    fun observe(): Flow<SyncQueueState>
    fun observeProject(id: ProjectId): Flow<ProjectSyncState>
    suspend fun pushNow(uuid: ProjectUuid): Result<Unit>
}

data class SyncQueueState(
    val pending: Int = 0,
    val uploading: Int = 0,
    val downloading: Int = 0,
    val lastSuccessAtMs: Long? = null,
    val lastErrorMessage: String? = null,
    val online: Boolean = true,
)

enum class ProjectSyncState { Synced, Pending, Uploading, Conflict, LocalOnly, Unknown }
