package com.sketchbook.desktop.repo

import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.repo.SyncQueue
import com.sketchbook.repo.SyncQueueState
import com.sketchbook.sync.PipelineInput
import com.sketchbook.sync.SnapshotPipeline
import com.sketchbook.sync.SnapshotProgress
import com.sketchbook.syncio.JvmWorkingTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * Real cloud-backed `SyncQueue`. Composes:
 *
 *  - [CloudBackend] — the GCS adapter (DirectGcsBackend in production, FakeCloudBackend in tests).
 *  - [SnapshotPipeline] — the §4.2 lease-walk-upload-CAS sequence.
 *  - [SyncStateStore] — catalog-backed per-project state and identity.
 *  - [ProjectRepository] — for looking up the on-disk path from a [ProjectId].
 *  - [JvmWorkingTree] — instantiated per push against the project's parent directory.
 *
 * Per-project state derivation:
 *   - actively uploading → `Uploading`
 *   - sync_state.dirty == 1 → `Pending`
 *   - sync_state.local_rev > 0 && dirty == 0 → `Synced`
 *   - no sync_state row → `LocalOnly` (never synced)
 *   - failed pipeline run on this session → `Conflict` (best-effort; restart resets to LocalOnly)
 *
 * **Scope.** Runs on [scope] (the app-lifetime CoroutineScope from Metro). All cloud ops touch
 * the network so they live on `Dispatchers.IO`.
 */
class GcsSyncQueue(
    private val cloud: CloudBackend,
    private val pipeline: SnapshotPipeline,
    private val syncState: SyncStateStore,
    private val projects: ProjectRepository,
    private val scope: CoroutineScope,
) : SyncQueue {

    private val uploading = MutableStateFlow<Set<ProjectUuid>>(emptySet())
    private val conflicts = MutableStateFlow<Set<ProjectUuid>>(emptySet())

    override fun observe(): Flow<SyncQueueState> {
        return combine(
            syncState.observeVersion().onStart { emit(0L) },
            uploading,
        ) { _, up ->
            val rows = withContext(Dispatchers.IO) { syncState.all() }
            SyncQueueState(
                pending = rows.count { it.dirty },
                uploading = up.size,
                downloading = 0,
                online = true,
            )
        }
    }

    override fun observeProject(id: ProjectId): Flow<ProjectSyncState> {
        return combine(
            syncState.observeVersion().onStart { emit(0L) },
            uploading,
            conflicts,
        ) { _, up, conf ->
            val uuid = withContext(Dispatchers.IO) { syncState.identityFor(id) }
            when {
                uuid in up -> ProjectSyncState.Uploading
                uuid in conf -> ProjectSyncState.Conflict
                else -> {
                    val row = withContext(Dispatchers.IO) { syncState.stateOf(uuid) }
                    when {
                        row == null -> ProjectSyncState.LocalOnly
                        row.dirty -> ProjectSyncState.Pending
                        row.localRev > 0 -> ProjectSyncState.Synced
                        else -> ProjectSyncState.LocalOnly
                    }
                }
            }
        }
    }

    /**
     * Push by [ProjectUuid]. Looks up the local row, runs the snapshot pipeline against the
     * project's parent directory, marks state on success.
     */
    override suspend fun pushNow(uuid: ProjectUuid): Result<Unit> {
        val pid = syncState.projectIdFor(uuid)
            ?: return Result.failure(IllegalStateException("no local project for uuid $uuid"))
        return runPipeline(pid, uuid)
    }

    /** Convenience for the desktop UI's "Sync now" button (works in [ProjectId] terms). */
    suspend fun pushNowById(id: ProjectId): Result<Unit> {
        val uuid = withContext(Dispatchers.IO) { syncState.identityFor(id) }
        return runPipeline(id, uuid)
    }

    private suspend fun runPipeline(pid: ProjectId, uuid: ProjectUuid): Result<Unit> {
        val row: ProjectRow = projects.observeProject(pid).first()
            ?: return Result.failure(IllegalStateException("project row $pid not found"))
        val alsPath = row.path.value
        val rootDir = Paths.get(alsPath).parent
            ?: return Result.failure(IllegalStateException("project path has no parent: $alsPath"))

        uploading.value = uploading.value + uuid
        conflicts.value = conflicts.value - uuid
        try {
            val tree = JvmWorkingTree(rootDir)
            val current = withContext(Dispatchers.IO) { syncState.stateOf(uuid) }
            val expectedHead = if (current == null || current.cloudHeadRev == 0L) {
                Generation.ZERO
            } else {
                // We don't persist the GCS object generation per snapshot today — pass null
                // (no precondition) and let the pipeline's branch path catch any concurrent
                // writer. v1.2 will track generation alongside cloud_head_rev.
                null
            }
            val input = PipelineInput(
                uuid = uuid,
                tree = tree,
                lastKnownManifest = null,
                expectedHeadGeneration = expectedHead,
                selfContained = current?.selfContained ?: false,
            )

            var savedRev: Long? = null
            var failureReason: String? = null
            withContext(Dispatchers.IO) {
                pipeline.run(input).collect { progress ->
                    when (progress) {
                        is SnapshotProgress.Saved -> savedRev = progress.rev.value
                        is SnapshotProgress.Failed -> failureReason = progress.reason
                        else -> Unit
                    }
                }
            }
            val finalRev = savedRev
            return if (finalRev != null) {
                withContext(Dispatchers.IO) { syncState.markSynced(uuid, finalRev) }
                Result.success(Unit)
            } else {
                conflicts.value = conflicts.value + uuid
                Result.failure(IllegalStateException(failureReason ?: "pipeline did not complete"))
            }
        } catch (t: Throwable) {
            conflicts.value = conflicts.value + uuid
            return Result.failure(t)
        } finally {
            uploading.value = uploading.value - uuid
        }
    }

    /**
     * Synchronous best-effort lookup for non-suspending UI code. Same logic as the flow but
     * blocks on [SyncStateStore] reads (which are local SQLite — safe from the EDT).
     */
    fun snapshotFor(id: ProjectId): ProjectSyncState {
        val uuid = syncState.identityFor(id)
        return when {
            uuid in uploading.value -> ProjectSyncState.Uploading
            uuid in conflicts.value -> ProjectSyncState.Conflict
            else -> {
                val row = syncState.stateOf(uuid)
                when {
                    row == null -> ProjectSyncState.LocalOnly
                    row.dirty -> ProjectSyncState.Pending
                    row.localRev > 0 -> ProjectSyncState.Synced
                    else -> ProjectSyncState.LocalOnly
                }
            }
        }
    }
}
