package com.sketchbook.desktop.repo

import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.runCatchingCancellable
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.repo.PushNowOutcome
import com.sketchbook.repo.SyncQueue
import com.sketchbook.repo.SyncQueueState
import com.sketchbook.sync.ForceSnapshotPipeline
import com.sketchbook.sync.PipelineInput
import com.sketchbook.sync.SnapshotPipeline
import com.sketchbook.sync.SnapshotProgress
import com.sketchbook.syncio.JvmWorkingTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Real cloud-backed `SyncQueue`. Composes:
 *
 *  - [CloudBackend] — the GCS adapter (FirebaseBlobStore in production, FakeCloudBackend in tests).
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
    private val journal: JournalRepository? = null,
    private val clock: Clock = Clock.System,
    /**
     * Dispatcher for catalog reads + cloud I/O. Defaults to [Dispatchers.IO] in production. The
     * drain tests inject a [kotlinx.coroutines.test.UnconfinedTestDispatcher] tied to the test
     * scheduler so virtual time stays in lockstep with the loop's cloud calls.
     */
    private val ioDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : SyncQueue,
    ForceSnapshotPipeline {
    private val uploading = MutableStateFlow<Set<ProjectUuid>>(emptySet())
    private val conflicts = MutableStateFlow<Set<ProjectUuid>>(emptySet())

    /**
     * Per-uuid backoff state for transient failures (network blip, cloud 503, etc). Lives in
     * memory only — by design, restart resets backoff so the user gets a clean retry pass on
     * launch. CAS conflicts go through [conflicts] instead and stay until the user pulls.
     *
     * Mutated only from the single drain coroutine, so no mutex is needed.
     */
    private val backoff = mutableMapOf<ProjectUuid, BackoffEntry>()
    private var drainJob: Job? = null

    /** Per-project last conflict message — surfaced inline on the detail panel. */
    private val conflictMessages = MutableStateFlow<Map<ProjectUuid, String>>(emptyMap())

    /** Read-side accessor for the detail-panel's conflict caption. */
    fun conflictMessage(uuid: ProjectUuid): String? = conflictMessages.value[uuid]

    override fun observe(): Flow<SyncQueueState> =
        combine(
            syncState.observeVersion().onStart { emit(0L) },
            uploading,
        ) { _, up ->
            val rows = withContext(ioDispatcher) { syncState.all() }
            SyncQueueState(
                pending = rows.count { it.dirty },
                uploading = up.size,
                downloading = 0,
                online = true,
            )
        }

    override fun observeProject(id: ProjectId): Flow<ProjectSyncState> =
        combine(
            syncState.observeVersion().onStart { emit(0L) },
            uploading,
            conflicts,
        ) { _, up, conf ->
            val uuid = withContext(ioDispatcher) { syncState.identityFor(id) }
            when {
                uuid in up -> {
                    ProjectSyncState.Uploading
                }

                uuid in conf -> {
                    ProjectSyncState.Conflict
                }

                else -> {
                    val row = withContext(ioDispatcher) { syncState.stateOf(uuid) }
                    when {
                        row == null -> ProjectSyncState.LocalOnly
                        row.dirty -> ProjectSyncState.Pending
                        row.cloudHeadRev > row.localRev -> ProjectSyncState.RemoteAhead
                        row.localRev > 0 -> ProjectSyncState.Synced
                        else -> ProjectSyncState.LocalOnly
                    }
                }
            }
        }

    /**
     * Push by [ProjectUuid]. Looks up the local row, runs the snapshot pipeline against the
     * project's parent directory, marks state on success. Returns [PushNowOutcome.AlreadyInFlight]
     * if another push for the same uuid is currently running; [PushNowOutcome.Conflict] if the
     * pipeline saved a branch because the remote diverged; [PushNowOutcome.Pushed] on a clean
     * push. Throws [SketchbookError] for transport / pipeline failures.
     */
    override suspend fun pushNow(uuid: ProjectUuid): PushNowOutcome {
        if (uuid in uploading.value) return PushNowOutcome.AlreadyInFlight
        val pid =
            syncState.projectIdFor(uuid)
                ?: throw SketchbookError.IoFailure("no local project for uuid $uuid")
        return runPipeline(pid, uuid).toPushOutcome(previousCloudRev(uuid))
    }

    /** Convenience for the desktop UI's "Sync now" button (works in [ProjectId] terms). */
    suspend fun pushNowById(id: ProjectId): PushNowOutcome {
        val uuid = withContext(ioDispatcher) { syncState.identityFor(id) }
        if (uuid in uploading.value) return PushNowOutcome.AlreadyInFlight
        return runPipeline(id, uuid).toPushOutcome(previousCloudRev(uuid))
    }

    private suspend fun previousCloudRev(uuid: ProjectUuid): Long =
        withContext(ioDispatcher) { syncState.stateOf(uuid)?.cloudHeadRev } ?: 0L

    private fun Result<PipelineRunOutcome>.toPushOutcome(priorCloudRev: Long): PushNowOutcome =
        fold(
            onSuccess = { o ->
                if (o.kind == SnapshotKind.Branch) {
                    PushNowOutcome.Conflict(theirRev = priorCloudRev, branchRev = o.rev.value)
                } else {
                    PushNowOutcome.Pushed
                }
            },
            onFailure = { t ->
                if (t is SketchbookError) throw t
                throw SketchbookError.IoFailure("pushNow failed", t)
            },
        )

    /**
     * Z3 quick-capture entry-point: writes a Named manifest of the project's current bytes,
     * tagged with [label]. Goes through the same [SnapshotPipeline] as auto-save so blob upload,
     * lease lifecycle, and journaling all run via the normal path. CAS conflicts still demote
     * the result to a Branch — divergence wins over Named, mirroring auto-save semantics.
     */
    override suspend fun recordForcedNamed(
        uuid: ProjectUuid,
        label: String,
    ): Result<SnapshotRev> {
        val pid =
            withContext(ioDispatcher) { syncState.projectIdFor(uuid) }
                ?: return Result.failure(IllegalStateException("no local project for uuid $uuid"))
        return runPipeline(pid, uuid, kind = SnapshotKind.Named, label = label).map { it.rev }
    }

    /** What [runPipeline] returns on success — both rev and kind so callers can tell Pushed
     *  from CAS-branch outcomes. */
    private data class PipelineRunOutcome(
        val rev: SnapshotRev,
        val kind: SnapshotKind,
    )

    private suspend fun runPipeline(
        pid: ProjectId,
        uuid: ProjectUuid,
        kind: SnapshotKind = SnapshotKind.Auto,
        label: String? = null,
    ): Result<PipelineRunOutcome> {
        val row: ProjectRow =
            projects.observeProject(pid).first()
                ?: return Result.failure(IllegalStateException("project row $pid not found"))
        // ProjectPath.fromPlatform strips leading slashes for portable storage. Restore the
        // absolute form before handing off to NIO: a Windows drive-letter path ("Z:/...") needs
        // no fix-up, but a Unix path arrives as "tmp/foo/x.als" and must be reified as
        // "/tmp/foo/x.als" so Paths.get() resolves it absolutely.
        val rawPath = row.path.value
        val alsPath = if (rawPath.length >= 2 && rawPath[1] == ':') rawPath else "/$rawPath"
        val rootDir =
            Paths.get(alsPath).parent
                ?: return Result.failure(IllegalStateException("project path has no parent: $alsPath"))

        uploading.value = uploading.value + uuid
        conflicts.value = conflicts.value - uuid
        try {
            val tree = JvmWorkingTree(rootDir)
            val current = withContext(ioDispatcher) { syncState.stateOf(uuid) }
            val expectedHead =
                if (current == null || current.cloudHeadRev == 0L) {
                    Generation.ZERO
                } else {
                    // We don't persist the GCS object generation per snapshot today — pass null
                    // (no precondition) and let the pipeline's branch path catch any concurrent
                    // writer. v1.2 will track generation alongside cloud_head_rev.
                    null
                }
            // Prefetch the cloud-side last-known manifest so the pipeline's unchanged-file
            // diff actually fires. Without this, every push full-hashes the project tree and
            // pays a HEAD-per-blob roundtrip even when nothing changed (H8). On the first
            // sync (cloudHeadRev == 0) there's nothing to fetch; transient failures fall back
            // to null + the no-dedup path so a network blip never blocks a save.
            val lastKnownManifest =
                if (current != null && current.cloudHeadRev > 0L) {
                    try {
                        withContext(ioDispatcher) {
                            cloud.readManifest(uuid, SnapshotRev(current.cloudHeadRev))
                        }
                    } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        System.err.println(
                            "[GcsSyncQueue] could not fetch lastKnownManifest for uuid=${uuid.value}, falling back to full hash: $t",
                        )
                        null
                    }
                } else {
                    null
                }
            val input =
                PipelineInput(
                    uuid = uuid,
                    tree = tree,
                    lastKnownManifest = lastKnownManifest,
                    expectedHeadGeneration = expectedHead,
                    selfContained = current?.selfContained ?: false,
                    kind = kind,
                    label = label,
                )

            var savedRev: Long? = null
            var savedKind: SnapshotKind? = null
            var failureReason: String? = null
            withContext(ioDispatcher) {
                pipeline.run(input).collect { progress ->
                    when (progress) {
                        is SnapshotProgress.Saved -> {
                            savedRev = progress.rev.value
                            savedKind = progress.kind
                        }

                        is SnapshotProgress.Failed -> {
                            failureReason = progress.reason
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }
            val finalRev = savedRev
            val finalKind = savedKind
            return if (finalRev != null && finalKind != null) {
                withContext(ioDispatcher) { syncState.markSynced(uuid, finalRev) }
                // A saved branch means our push CAS-failed and we wrote a fork — surface it as
                // Conflict with an inline message so the user can pull + re-push.
                if (finalKind == SnapshotKind.Branch) {
                    conflicts.value = conflicts.value + uuid
                    conflictMessages.value = conflictMessages.value + (
                        uuid to
                            "Remote diverged — your work was saved as a branch. Pull + re-push to merge."
                    )
                    recordConflictJournal(pid, ourRev = finalRev, theirRev = finalRev - 1)
                }
                Result.success(PipelineRunOutcome(SnapshotRev(finalRev), finalKind))
            } else {
                conflicts.value = conflicts.value + uuid
                conflictMessages.value = conflictMessages.value + (
                    uuid to
                        (failureReason ?: "Push failed — retry or check Settings → Cloud.")
                )
                Result.failure(IllegalStateException(failureReason ?: "pipeline did not complete"))
            }
        } catch (t: Throwable) {
            // Plain throwable = transient (network, IO, GCS 5xx). Do NOT add to `conflicts` —
            // that's reserved for CAS-divergence which the user has to resolve. Drain treats
            // these as backoff candidates and keeps retrying forever per spec.
            return Result.failure(t)
        } finally {
            uploading.value = uploading.value - uuid
        }
    }

    private suspend fun recordConflictJournal(
        pid: ProjectId,
        ourRev: Long,
        theirRev: Long,
    ) {
        val j = journal ?: return
        runCatchingCancellable {
            j.append(
                JournalEntry(
                    timestamp = clock.now(),
                    projectId = pid,
                    action = ActionRecord.PushConflict(ourRev = ourRev, theirRev = theirRev),
                ),
            )
        }
    }

    /**
     * Start the background drain loop. Idempotent — calling while already running is a no-op.
     * The drain runs `drainOnce()` immediately (no initial sleep), then loops on
     * `observeVersion()` bumps with a 60s fallback timer so a quiet system still gets one tick
     * per minute (covers backoff expiry + clock-driven mtime quiet-period transitions).
     *
     * No mutex on [backoff]: it's only mutated from the single coroutine launched here.
     */
    fun start() {
        if (drainJob?.isActive == true) return
        drainJob = scope.launch { drainLoop() }
    }

    /** Cancel the drain loop. Safe to call multiple times. In-flight push completes. */
    fun stop() {
        drainJob?.cancel()
        drainJob = null
    }

    /** Test-only: clear a uuid from the conflict set so the drain picks it up again. */
    internal fun clearConflict(uuid: ProjectUuid) {
        conflicts.value = conflicts.value - uuid
        conflictMessages.value = conflictMessages.value - uuid
    }

    private suspend fun drainLoop() {
        while (currentCoroutineContext().isActive) {
            drainOnce()
            // Either a sync_state write nudges us (`observeVersion()` re-emits) or we tick on
            // the 60s fallback. The fallback also covers backoff expiry — without it, a row
            // backed-off for 30s would sit there until something else bumped the version.
            withTimeoutOrNull(60_000L) {
                syncState.observeVersion().drop(1).first()
            }
        }
    }

    private suspend fun drainOnce() {
        val now = clock.now()
        val candidates =
            withContext(ioDispatcher) { syncState.dirtyOldestFirst() }
                .filterNot { it.uuid in conflicts.value }
                .filterNot { (backoff[it.uuid]?.nextAttempt ?: now) > now }
                .filterNot { isInQuietPeriod(it.uuid, now) }
        val target = candidates.firstOrNull() ?: return
        val pid = withContext(ioDispatcher) { syncState.projectIdFor(target.uuid) } ?: return
        val result = runPipeline(pid, target.uuid)
        if (result.isSuccess) {
            // Reset backoff on success so the next transient blip starts fresh at 30s rather
            // than continuing from wherever we left off — the network problem is presumed gone.
            backoff.remove(target.uuid)
        } else if (target.uuid !in conflicts.value) {
            // CAS-divergence path adds to `conflicts` inside runPipeline, and the drain skips
            // those. Anything reaching here is a transient and earns a backoff bump.
            bumpBackoff(target.uuid, now)
        }
    }

    private fun bumpBackoff(
        uuid: ProjectUuid,
        now: Instant,
    ) {
        val current = backoff[uuid]
        val nextIdx =
            if (current == null) {
                0
            } else {
                (BACKOFF_INTERVALS_SECONDS.indexOf(current.intervalSeconds).coerceAtLeast(0) + 1)
                    .coerceAtMost(BACKOFF_INTERVALS_SECONDS.lastIndex)
            }
        val seconds = BACKOFF_INTERVALS_SECONDS[nextIdx]
        backoff[uuid] =
            BackoffEntry(
                nextAttempt = now + seconds.seconds,
                intervalSeconds = seconds,
            )
    }

    /**
     * `.als` mtime within the last 30 seconds = Live just saved (atomic tmp+rename), or the
     * user is actively editing. Either way, defer — uploading mid-save races the rename, and
     * uploading on every keystroke wastes bandwidth. 30s is short enough that idle work
     * uploads quickly but long enough to coalesce a working session.
     */
    private suspend fun isInQuietPeriod(
        uuid: ProjectUuid,
        now: Instant,
    ): Boolean {
        val pid = withContext(ioDispatcher) { syncState.projectIdFor(uuid) } ?: return false
        val row = projects.observeProject(pid).first() ?: return false
        val rawPath = row.path.value
        val alsPath = if (rawPath.length >= 2 && rawPath[1] == ':') rawPath else "/$rawPath"
        return runCatchingCancellable {
            val mtime =
                withContext(ioDispatcher) {
                    Instant.fromEpochMilliseconds(
                        Files.getLastModifiedTime(Paths.get(alsPath)).toMillis(),
                    )
                }
            (now - mtime).inWholeSeconds < QUIET_PERIOD_SECONDS
        }.getOrDefault(false)
    }

    private data class BackoffEntry(
        val nextAttempt: Instant,
        val intervalSeconds: Long,
    )

    private companion object {
        /** Capped exponential. Last entry is the cap — once reached, all further bumps stay. */
        private val BACKOFF_INTERVALS_SECONDS = listOf(30L, 60L, 300L, 900L)
        private const val QUIET_PERIOD_SECONDS = 30L
    }

    /**
     * Synchronous best-effort lookup for non-suspending UI code. Same logic as the flow but
     * blocks on [SyncStateStore] reads (which are local SQLite — safe from the EDT).
     */
    fun snapshotFor(id: ProjectId): ProjectSyncState {
        val uuid = syncState.identityFor(id)
        return when {
            uuid in uploading.value -> {
                ProjectSyncState.Uploading
            }

            uuid in conflicts.value -> {
                ProjectSyncState.Conflict
            }

            else -> {
                val row = syncState.stateOf(uuid)
                when {
                    row == null -> ProjectSyncState.LocalOnly
                    row.dirty -> ProjectSyncState.Pending
                    row.cloudHeadRev > row.localRev -> ProjectSyncState.RemoteAhead
                    row.localRev > 0 -> ProjectSyncState.Synced
                    else -> ProjectSyncState.LocalOnly
                }
            }
        }
    }
}
