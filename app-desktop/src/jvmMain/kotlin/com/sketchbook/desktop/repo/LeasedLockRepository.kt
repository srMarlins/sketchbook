package com.sketchbook.desktop.repo

import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.metadata.AcquireResult
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.LockDoc
import com.sketchbook.cloud.metadata.MetadataStore
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.LockRepository
import com.sketchbook.repo.LockStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Firestore-backed [LockRepository] (Phase 3). Replaces the previous CloudBackend-backed
 * implementation that wrote `<user>/locks/<uuid>.lock` GCS objects — leases now live at
 * `/users/{uid}/locks/{treeId}` Firestore docs as [LockDoc].
 *
 * One per-project listener subscription drives the UI flow ([observe]); transitions go through
 * [MetadataStore.acquireLock] / [refreshLock] / [releaseLock] which atomic-test-and-set against
 * the same doc. Heartbeats refresh on a 5-minute cadence while we hold a lease; on takeover
 * (refreshLock returning false) we surface the new holder via the listener path naturally.
 *
 * **Cloud is a `() -> ...` callback so creds-not-yet-set isn't fatal.** The desktop graph
 * builds this once at app start; acquire / heartbeat short-circuit when the metadata store
 * isn't wired up yet (which currently is "never" once Phase 3 lands, but the same shape lets
 * `signed-out → no listeners` continue to work).
 *
 * @param userIdFlow StateFlow of the current Firebase UID, or `null` when signed-out. The repo
 *   uses `.value` for synchronous path building and observes transitions to tear down per-uuid
 *   listener/heartbeat jobs whenever the UID changes (sign-out, account switch). Without the
 *   teardown, cached entries in [byUuid] would keep streaming the previous user's lock docs
 *   under the previous user's Firestore path while new acquires use the new UID — desynchronizing
 *   the UI flow from the holder of record.
 */
class LeasedLockRepository(
    private val metadataStore: () -> MetadataStore?,
    private val userIdFlow: StateFlow<String?>,
    private val syncStateStore: SyncStateStore,
    private val hostId: String,
    private val hostName: String,
    private val scope: CoroutineScope,
    private val journal: JournalRepository? = null,
    private val clock: Clock = Clock.System,
    private val leaseTtl: Duration = 15.minutes,
    private val heartbeatInterval: Duration = 5.minutes,
) : LockRepository {
    init {
        // UID transitions wipe state. `drop(1)` skips the replayed current value so the initial
        // UID doesn't fire resetState() (which would race the test body / startup acquires);
        // StateFlow already de-duplicates by equality, so subsequent emissions are real changes.
        scope.launch {
            userIdFlow.drop(1).collectLatest { _ ->
                resetState()
            }
        }
    }
    private data class PerUuid(
        val status: MutableStateFlow<LockStatus> = MutableStateFlow(LockStatus.Free),
        var listenerJob: Job? = null,
        var heartbeatJob: Job? = null,
    )

    private val byUuid = mutableMapOf<ProjectUuid, PerUuid>()

    private fun get(uuid: ProjectUuid): PerUuid =
        synchronized(byUuid) {
            byUuid.getOrPut(uuid) {
                val per = PerUuid()
                startListener(uuid, per)
                per
            }
        }

    private fun resetState() {
        val drained =
            synchronized(byUuid) {
                val copy = byUuid.values.toList()
                byUuid.clear()
                copy
            }
        for (per in drained) {
            per.listenerJob?.cancel()
            per.heartbeatJob?.cancel()
            per.status.value = LockStatus.Free
        }
    }

    override fun observe(uuid: ProjectUuid): Flow<LockStatus> = get(uuid).status.asStateFlow()

    override suspend fun forceTake(uuid: ProjectUuid): Result<Unit> {
        val store = metadataStore() ?: return Result.failure(IllegalStateException("cloud not configured"))
        val uid = userIdFlow.value ?: return Result.failure(IllegalStateException("signed out"))
        val per = get(uuid)
        val path = DocPath.lock(uid, uuid.value)

        // Capture the prior holder (if any) for journal context BEFORE we overwrite.
        val prior = store.getDoc(path, LockDoc.serializer())
        val priorOwnerName = prior?.holderName?.takeIf { it.isNotBlank() } ?: prior?.holder
        val priorExpiresAtMs = prior?.expiresAt?.toEpochMilliseconds()

        // forceTake bypasses the "live lease blocks acquire" check by deleting the old doc
        // first. Two writes, not atomic — between them another host could acquire. That's
        // acceptable for force-take semantics: the user already accepted "I am racing the
        // current holder"; whoever lands their write last wins.
        runCatching { store.releaseLockAsAnyone(path) }
        when (
            val acquired =
                store.acquireLock(
                    path = path,
                    holder = hostId,
                    ttl = leaseTtl,
                    holderName = hostName,
                )
        ) {
            AcquireResult.Acquired -> Unit
            is AcquireResult.HeldByOther ->
                return Result.failure(
                    IllegalStateException("force-take race: another host re-acquired the lock"),
                )
            is AcquireResult.Failed ->
                return Result.failure(acquired.cause)
        }
        startHeartbeat(uuid, per)
        recordForceTake(uuid, priorOwnerName, priorExpiresAtMs)
        return Result.success(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startListener(
        uuid: ProjectUuid,
        per: PerUuid,
    ) {
        per.listenerJob?.cancel()
        val store = metadataStore() ?: return
        val uid = userIdFlow.value ?: return
        val path = DocPath.lock(uid, uuid.value)
        per.listenerJob =
            scope.launch {
                // Deadline-driven re-derivation (H5). A lease that quietly expires past its
                // expiresAt should flip HeldByOther → Stale in the UI, but the Firestore
                // listener won't re-emit until the doc is touched. Compute the exact delay to
                // the expiry boundary and re-emit then — one wake-up per lease instead of
                // 30 ticks/15min while idle. `flatMapLatest` cancels the pending wake-up if a
                // new lock-doc emission arrives before the deadline.
                store
                    .observeDoc(path, LockDoc.serializer())
                    .flatMapLatest { lock ->
                        flow {
                            emit(computeStatus(lock, clock.now()))
                            val expiresAt = lock?.expiresAt ?: return@flow
                            val now = clock.now()
                            if (now < expiresAt) {
                                kotlinx.coroutines.delay(expiresAt - now)
                                emit(computeStatus(lock, clock.now()))
                            }
                        }
                    }
                    .collect { status -> per.status.value = status }
            }
    }

    private fun computeStatus(
        lock: LockDoc?,
        now: Instant,
    ): LockStatus =
        when {
            lock == null -> LockStatus.Free
            lock.holder == hostId -> LockStatus.Ours(lock.acquiredAt, lock.expiresAt)
            lock.expiresAt <= now -> LockStatus.Stale(lock.holderLabel(), lock.expiresAt)
            else -> LockStatus.HeldByOther(lock.holderLabel(), lock.acquiredAt, lock.expiresAt)
        }

    private fun startHeartbeat(
        uuid: ProjectUuid,
        per: PerUuid,
    ) {
        per.heartbeatJob?.cancel()
        per.heartbeatJob =
            scope.launch {
                val store = metadataStore() ?: return@launch
                val uid = userIdFlow.value ?: return@launch
                val path = DocPath.lock(uid, uuid.value)
                while (true) {
                    delay(heartbeatInterval)
                    val ok = store.refreshLock(path, hostId, leaseTtl)
                    if (!ok) {
                        // Lost the lock — let the listener flip status from Ours → HeldByOther
                        // on the next emission. Stop heartbeating; the listener-driven UI surface
                        // is the source of truth from here.
                        return@launch
                    }
                }
            }
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

    private fun LockDoc.holderLabel(): String = holderName.takeIf { it.isNotBlank() } ?: holder
}

/**
 * Internal helper: best-effort delete of the lock doc regardless of holder, used only by the
 * `forceTake` path. Not on [MetadataStore] because it deliberately violates the "only the
 * holder can release" contract — surfacing it as a member would invite misuse from non-
 * force-take call sites.
 */
private suspend fun MetadataStore.releaseLockAsAnyone(path: DocPath) {
    runCatching { deleteDoc(path) }
}
