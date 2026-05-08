package com.sketchbook.desktop.repo

import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
import com.sketchbook.cloud.LeaseRefreshResult
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.LockRepository
import com.sketchbook.repo.LockStatus
import com.sketchbook.sync.LeaseLockState
import com.sketchbook.sync.LockState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Real `LockRepository` impl that wraps [LeaseLockState] per uuid. Heartbeats every 60s while
 * we hold a lease; on `Lost` (CAS-stale), the repository's [observe] flow reports `Stale` so the
 * UI can prompt for force-take.
 *
 * Replaces [InMemoryLockRepository] in production. The in-memory variant is kept around for
 * unit tests in the feature modules that don't want to spin up a CloudBackend.
 *
 * **Cloud dep is a function so creds-not-yet-set isn't a hard error.** The desktop graph builds
 * this once and hands it a `() -> CloudBackend?` derived from `SwappableSyncQueue.currentCloud`;
 * acquire / heartbeat short-circuit when the cloud is null (status drops to Free).
 */
class LeasedLockRepository(
    private val cloud: () -> CloudBackend?,
    private val syncStateStore: SyncStateStore,
    private val hostId: String,
    private val hostName: String,
    private val scope: CoroutineScope,
    private val journal: JournalRepository? = null,
    private val clock: Clock = Clock.System,
) : LockRepository {
    private data class PerUuid(
        val state: LeaseLockState,
        val flow: MutableStateFlow<LockStatus>,
        var heartbeatJob: Job? = null,
    )

    // ConcurrentHashMap.computeIfAbsent: the lambda runs at most once per uuid even under
    // concurrent first-touches from suspend callers (forceTake) and the non-suspend observe()
    // path. Without this, two racing creators each instantiate their own LeaseLockState with
    // its own MutableStateFlow + heartbeat job, dueling on the same lease.
    private val byUuid = ConcurrentHashMap<ProjectUuid, PerUuid>()

    private fun get(uuid: ProjectUuid): PerUuid =
        byUuid.computeIfAbsent(uuid) {
            val backend = cloud() ?: NullCloudBackend
            PerUuid(
                state =
                    LeaseLockState(
                        cloud = backend,
                        uuid = uuid,
                        hostId = hostId,
                        hostName = hostName,
                        clock = clock,
                    ),
                flow = MutableStateFlow<LockStatus>(LockStatus.Free),
            )
        }

    override fun observe(uuid: ProjectUuid): Flow<LockStatus> = get(uuid).flow.asStateFlow()

    override suspend fun forceTake(uuid: ProjectUuid): Result<Unit> {
        val backend = cloud() ?: return Result.failure(IllegalStateException("cloud not configured"))
        val per = get(uuid)
        // Try to acquire; CAS uses the existing lock's generation if present.
        val lock =
            LeaseLock(
                ownerHostId = hostId,
                ownerHostName = hostName,
                acquiredAt = clock.now(),
                expiresAt = clock.now() + 15.minutes,
            )
        var priorOwnerHostName: String? = null
        var priorExpiresAtMs: Long? = null
        val treeId = TrackedTreeId(uuid.value)
        val kind = TrackedTreeKind.Project
        // try/catch (CancellationException) instead of runCatching: backend.acquireLock /
        // refreshLock are suspend; runCatching would silently swallow coroutine cancellation.
        val outcome: Result<Unit> =
            try {
                // Best-effort: if a lock already exists, try refresh-overwrite via CAS.
                val acquireResult = backend.acquireLock(treeId, kind, lock)
                when (acquireResult) {
                    is LeaseAcquireResult.Acquired -> {
                        per.flow.value = LockStatus.Ours(lock.acquiredAt, lock.expiresAt)
                        startHeartbeat(uuid, per)
                    }

                    is LeaseAcquireResult.Held -> {
                        priorOwnerHostName = acquireResult.held.ownerHostName
                        priorExpiresAtMs = acquireResult.held.expiresAt.toEpochMilliseconds()
                        // Force-take: overwrite via refresh CAS targeting the held generation.
                        val r = backend.refreshLock(treeId, kind, lock, acquireResult.generation)
                        when (r) {
                            is LeaseRefreshResult.Refreshed -> {
                                per.flow.value = LockStatus.Ours(lock.acquiredAt, lock.expiresAt)
                                startHeartbeat(uuid, per)
                            }

                            LeaseRefreshResult.Stale -> {
                                per.flow.value =
                                    LockStatus.Stale(
                                        ownerHostName = acquireResult.held.ownerHostName,
                                        expiresAt = acquireResult.held.expiresAt,
                                    )
                                throw IllegalStateException("force-take race: lock changed under us")
                            }
                        }
                    }
                }
                Result.success(Unit)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
        if (outcome.isSuccess) {
            recordForceTake(uuid, priorOwnerHostName, priorExpiresAtMs)
        }
        return outcome
    }

    private suspend fun recordForceTake(
        uuid: ProjectUuid,
        priorOwnerHostName: String?,
        priorExpiresAtMs: Long?,
    ) {
        val journalRepo = journal ?: return
        val projectId: ProjectId = syncStateStore.projectIdFor(uuid) ?: return
        journalRepo.append(
            JournalEntry(
                timestamp = clock.now(),
                projectId = projectId,
                action =
                    ActionRecord.ForceTakeLock(
                        priorOwnerHostName = priorOwnerHostName,
                        priorExpiresAtMs = priorExpiresAtMs,
                    ),
            ),
        )
    }

    private fun startHeartbeat(
        uuid: ProjectUuid,
        per: PerUuid,
    ) {
        per.heartbeatJob?.cancel()
        per.heartbeatJob =
            scope.launch {
                // The actual refresh CAS happens inside LeaseLockState — but we delegate to it for
                // the new design via acquire() which spawns its own heartbeat. To avoid double-
                // heartbeating, we DON'T launch a parallel heartbeat here when LeaseLockState is
                // already running one. Today this loop only mirrors LockState → LockStatus so the
                // UI sees Lost as Stale.
                per.state.state.collect { st ->
                    per.flow.value =
                        when (st) {
                            LockState.Idle -> {
                                LockStatus.Free
                            }

                            is LockState.Owned -> {
                                LockStatus.Ours(st.lock.acquiredAt, st.lock.expiresAt)
                            }

                            is LockState.HeldByOther -> {
                                // If the lease has expired, surface as Stale so the UI offers force-take.
                                val now = clock.now()
                                if (st.held.expiresAt <= now) {
                                    LockStatus.Stale(st.held.ownerHostName, st.held.expiresAt)
                                } else {
                                    LockStatus.HeldByOther(st.held.ownerHostName, st.held.acquiredAt, st.held.expiresAt)
                                }
                            }

                            LockState.Lost -> {
                                LockStatus.Free
                            }
                        }
                }
            }
    }
}

/**
 * Sentinel [CloudBackend] used when the desktop has no cloud creds yet. All ops fail; the
 * [LeasedLockRepository] short-circuits before calling these in normal paths.
 */
private object NullCloudBackend : CloudBackend {
    override suspend fun headBlob(
        hash: com.sketchbook.core.BlobHash,
        scope: com.sketchbook.cloud.BlobScope,
    ) = false

    override suspend fun putBlob(
        hash: com.sketchbook.core.BlobHash,
        source: kotlinx.io.RawSource,
        size: Long,
        scope: com.sketchbook.cloud.BlobScope,
    ) = error("cloud not configured")

    override suspend fun getBlob(
        hash: com.sketchbook.core.BlobHash,
        scope: com.sketchbook.cloud.BlobScope,
    ) = error("cloud not configured")

    override suspend fun readManifest(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        rev: com.sketchbook.core.SnapshotRev,
    ) = error("cloud not configured")

    override suspend fun listManifests(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        sinceRev: com.sketchbook.core.SnapshotRev?,
    ) = emptyList<com.sketchbook.cloud.ManifestRef>()

    override suspend fun appendManifestHead(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expectedHead: com.sketchbook.cloud.Generation?,
        manifest: com.sketchbook.core.Manifest,
    ) = Result.failure<com.sketchbook.cloud.Generation>(IllegalStateException("cloud not configured"))

    override suspend fun acquireLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
    ) = LeaseAcquireResult.Acquired(com.sketchbook.cloud.Generation("0"))

    override suspend fun refreshLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
        expected: com.sketchbook.cloud.Generation,
    ) = LeaseRefreshResult.Stale

    override suspend fun releaseLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expected: com.sketchbook.cloud.Generation,
    ) {}

    override suspend fun readDoc(key: com.sketchbook.core.CloudDocKey): com.sketchbook.cloud.CloudDocRead? = null

    override suspend fun writeDoc(
        key: com.sketchbook.core.CloudDocKey,
        expected: com.sketchbook.cloud.Generation?,
        bytes: ByteArray,
    ) = Result.failure<com.sketchbook.cloud.Generation>(IllegalStateException("cloud not configured"))

    override suspend fun listDocs(prefix: com.sketchbook.core.CloudDocKey.Prefix) = emptyList<com.sketchbook.cloud.CloudDocRef>()
}
