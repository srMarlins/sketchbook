package com.sketchbook.desktop.repo

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.metadata.InMemoryMetadataStore
import com.sketchbook.cloud.ManifestRef
import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.UserId
import com.sketchbook.repo.impl.SqlProjectRepository
import com.sketchbook.sync.SnapshotPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Drain-loop tests for [GcsSyncQueue]. Each test wires a real [SqlProjectRepository] +
 * [SyncStateStore] over an in-memory catalog, a real [SnapshotPipeline] talking to a
 * test-controlled [CountingCloudBackend], and a real on-disk project tree (so [JvmWorkingTree]
 * has something to walk).
 *
 * Time control: a [VirtualClock] reads from the [TestCoroutineScheduler] so the queue's
 * `clock.now()` stays in lockstep with virtual time as we [advanceTimeBy].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GcsSyncQueueDrainTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { dir ->
            runCatching {
                Files.walk(dir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
        tempDirs.clear()
    }

    /**
     * Hands every test a fully-wired environment. The real SnapshotPipeline writes manifests via
     * [cloud], so successful pushes flip `sync_state.dirty` to 0 through [SyncStateStore]. The
     * caller can stamp an mtime on each project's `.als` (default: well outside the quiet
     * period) and toggle [cloud.failuresRemaining] for transient-failure tests.
     */
    private fun TestCoroutineScheduler.env(scope: CoroutineScope): Env {
        val handle = CatalogDb.openInMemory()
        val catalog = handle.catalog
        val fts = CatalogFts(handle.driver)
        val journal =
            com.sketchbook.repo.impl
                .InMemoryJournalRepository()
        val testDispatcher = UnconfinedTestDispatcher(this)
        val projects =
            SqlProjectRepository(
                catalog = catalog,
                ioDispatcher = testDispatcher,
                journal = journal,
                fts = com.sketchbook.repo.ProjectFtsSearcher { q -> fts.search(q) },
            )
        val syncState = SyncStateStore(catalog)
        val cloud = CountingCloudBackend()
        val metadataStore = CountingMetadataStore()
        val pipeline =
            SnapshotPipeline(
                cloud = cloud,
                metadataStore = metadataStore,
                ownerUserId = UserId.DEFAULT,
                hostId = "host-test",
                hostName = "TestHost",
            )
        val clock = VirtualClock(this)
        val queue =
            GcsSyncQueue(
                cloud = cloud,
                pipeline = pipeline,
                syncState = syncState,
                projects = projects,
                scope = scope,
                journal = null,
                clock = clock,
                ioDispatcher = testDispatcher,
            )
        return Env(catalog, fts, projects, syncState, cloud, metadataStore, queue, clock)
    }

    private data class Env(
        val catalog: com.sketchbook.catalog.db.Catalog,
        val fts: CatalogFts,
        val projects: SqlProjectRepository,
        val syncState: SyncStateStore,
        val cloud: CountingCloudBackend,
        val metadataStore: CountingMetadataStore,
        val queue: GcsSyncQueue,
        val clock: VirtualClock,
    )

    /**
     * Seed a project row + identity, write a tiny `.als` to a fresh temp dir, mark dirty, and
     * return the uuid. [updatedAtMs] sets the row's `last_modified` and the file's mtime so the
     * default is "outside the quiet period". Callers can override the file mtime later via
     * [Files.setLastModifiedTime].
     */
    private fun seedDirtyProject(
        env: Env,
        name: String,
        updatedAtMs: Long,
        fileMtimeMs: Long = updatedAtMs,
    ): Pair<ProjectUuid, Path> {
        val dir = createTempDirectory("sketchbook-drain-test-")
        tempDirs.add(dir)
        val alsPath = dir.resolve("$name.als")
        alsPath.writeBytes("AbletonProject".encodeToByteArray())
        Files.setLastModifiedTime(alsPath, FileTime.fromMillis(BASE_EPOCH_MS + fileMtimeMs))

        env.catalog.catalogQueries.insertOrReplaceProject(
            path = alsPath.toString(),
            name = name,
            parent_dir = dir.toString(),
            tempo = null,
            time_sig_num = null,
            time_sig_den = null,
            key = null,
            track_count = 0,
            audio_tracks = 0,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = "12.0",
            last_modified = updatedAtMs / 1000.0,
            last_scanned = updatedAtMs / 1000.0,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = 14L,
        )
        val pid =
            env.catalog.catalogQueries
                .selectProjectIdByPath(alsPath.toString())
                .executeAsOne()
        env.fts.upsert(rowid = pid, name = name, parentDir = dir.toString(), pluginNames = "", sampleFilenames = "", notes = "")
        val uuid = env.syncState.identityFor(com.sketchbook.core.ProjectId(pid))
        env.syncState.markDirty(uuid)
        return uuid to alsPath
    }

    // ---------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------

    @Test
    fun drainStartsImmediatelyOnLaunch() =
        runTest {
            val env = testScheduler.env(backgroundScope)
            // mtime >30s before virtual now (which starts at 0) — past the quiet period.
            seedDirtyProject(env, "alpha", updatedAtMs = -60_000, fileMtimeMs = -60_000)

            env.queue.start()
            // No advanceTimeBy — drain should run before any wait.
            runCurrent()
            advanceUntilIdle()

            assertEquals(1, env.cloud.appendCount)
        }

    @Test
    fun drainProcessesOldestDirtyFirst() =
        runTest {
            val env = testScheduler.env(backgroundScope)
            // updated_at ascending = older first. Files all outside quiet period.
            val (oldest, _) = seedDirtyProject(env, "old3h", updatedAtMs = -3 * 3_600_000, fileMtimeMs = -60_000)
            seedDirtyProject(env, "old2h", updatedAtMs = -2 * 3_600_000, fileMtimeMs = -60_000)
            seedDirtyProject(env, "old1h", updatedAtMs = -1 * 3_600_000, fileMtimeMs = -60_000)

            env.queue.start()
            runCurrent()
            // First push is in-flight or done; advance just enough to let it complete.
            advanceTimeBy(1)
            runCurrent()

            // After the first drain tick, the oldest uuid should have been pushed (rev=1).
            val oldestRow = env.syncState.stateOf(oldest)
            assertEquals(1L, oldestRow?.localRev, "oldest should have been pushed first")
            assertEquals(false, oldestRow?.dirty)
        }

    @Test
    fun drainWakesOnVersionBump() =
        runTest {
            val env = testScheduler.env(backgroundScope)

            env.queue.start()
            // No dirty rows yet → drainOnce returns immediately, loop awaits version bump or 60s.
            runCurrent()
            assertEquals(0, env.cloud.appendCount)

            // Advance only 5s of virtual time, then mark dirty. The version bump should wake the
            // loop *without* needing the 60s fallback.
            advanceTimeBy(5_000)
            runCurrent()
            seedDirtyProject(env, "wake", updatedAtMs = -60_000, fileMtimeMs = -60_000)
            // markDirty bumps observeVersion → drain wakes → drainOnce → push.
            advanceTimeBy(1_000)
            runCurrent()
            advanceUntilIdle()

            assertEquals(1, env.cloud.appendCount, "version bump should have woken the drain")
        }

    @Test
    fun drainBacksOffExponentiallyOnTransientFailure() =
        runTest {
            val env = testScheduler.env(backgroundScope)
            seedDirtyProject(env, "flaky", updatedAtMs = -60_000, fileMtimeMs = -60_000)
            // Throw on every push attempt for the duration of this test — we'll measure backoff
            // intervals between successive append attempts (which never happen because putBlob fails
            // first, but the *attempt* counter on cloud increments via lease acquisitions).
            env.cloud.failuresRemaining = 10

            env.queue.start()
            runCurrent()

            // First attempt at t=0.
            assertEquals(1, env.metadataStore.lockAcquireCount, "first attempt should fire immediately")

            // Backoff after fail #1 = 30s. Verify no retry before that.
            advanceTimeBy(29_000)
            runCurrent()
            assertEquals(1, env.metadataStore.lockAcquireCount, "no retry before 30s backoff expires")
            // Cross 30s boundary → next 60s wake fires retry. Use 60s wake (the only timer).
            advanceTimeBy(31_000)
            runCurrent() // total 60s → wake fires drainOnce.
            assertEquals(2, env.metadataStore.lockAcquireCount, "second attempt at ~60s")

            // Backoff after fail #2 = 60s. Next wake: another 60s window. Need to wait ~60s past t=60.
            advanceTimeBy(60_000)
            runCurrent() // t=120 → next wake.
            assertEquals(3, env.metadataStore.lockAcquireCount, "third attempt around t=120s (60s backoff cleared)")

            // Backoff after fail #3 = 300s. Next wake at +60s (t=180), but backoff still active.
            advanceTimeBy(60_000)
            runCurrent() // t=180.
            assertEquals(3, env.metadataStore.lockAcquireCount, "still backed off at t=180s")
            // Several more 60s wakes pass, all skip. We need t >= 120 + 300 = 420.
            advanceTimeBy(240_000)
            runCurrent() // t=420.
            assertEquals(4, env.metadataStore.lockAcquireCount, "fourth attempt around t=420s (300s backoff cleared)")

            // Backoff after fail #4 = 900s. Need t >= 420 + 900 = 1320.
            advanceTimeBy(900_000)
            runCurrent() // t=1320.
            assertEquals(5, env.metadataStore.lockAcquireCount, "fifth attempt around t=1320s (900s cap)")
        }

    @Test
    fun drainCapsBackoffAt15Min() =
        runTest {
            val env = testScheduler.env(backgroundScope)
            seedDirtyProject(env, "stuck", updatedAtMs = -60_000, fileMtimeMs = -60_000)
            env.cloud.failuresRemaining = 100 // never succeed.

            env.queue.start()
            runCurrent()
            // Walk through 5 failures the same way as the previous test.
            advanceTimeBy(60_000)
            runCurrent() // attempt #2
            advanceTimeBy(60_000)
            runCurrent() // attempt #3
            advanceTimeBy(300_000)
            runCurrent() // attempt #4
            advanceTimeBy(900_000)
            runCurrent() // attempt #5
            assertEquals(5, env.metadataStore.lockAcquireCount)

            // After attempt #5 fails, backoff should still be 900s (capped). Verify by advancing
            // exactly 900s — attempt #6 should fire right around that boundary.
            advanceTimeBy(900_000)
            runCurrent()
            assertEquals(6, env.metadataStore.lockAcquireCount, "6th attempt should fire at 900s cap, not grow further")

            // And the 7th: another 900s, not 30 minutes or anything bigger.
            advanceTimeBy(900_000)
            runCurrent()
            assertEquals(7, env.metadataStore.lockAcquireCount, "7th attempt also at 900s cap (no further growth)")
        }

    @Test
    fun drainSkipsCasConflictUntilCleared() =
        runTest {
            val env = testScheduler.env(backgroundScope)
            val (uuid, _) = seedDirtyProject(env, "fork", updatedAtMs = -60_000, fileMtimeMs = -60_000)
            // Make appendManifestHead return Conflict on the first attempt; pipeline will write a
            // branch (also a successful manifest write) which puts the uuid in `conflicts`.
            env.cloud.firstAppendIsConflict = true

            env.queue.start()
            runCurrent()
            advanceUntilIdle()

            // Pipeline ran once and produced the branch; uuid is now in `conflicts`. Even after a
            // long quiet period the drain should not retry the same uuid.
            val attemptsAfterConflict = env.metadataStore.lockAcquireCount
            advanceTimeBy(3_600_000)
            runCurrent() // 1h.
            advanceUntilIdle()
            assertEquals(attemptsAfterConflict, env.metadataStore.lockAcquireCount, "uuid in conflict should not be re-attempted")

            // Manually clear the conflict — the row is still dirty (markSynced was called for the
            // branch write but the test simulates the user-driven clearance pattern).
            env.syncState.markDirty(uuid)
            env.queue.clearConflict(uuid)
            advanceTimeBy(1_000)
            runCurrent()
            advanceUntilIdle()
            assertTrue(env.metadataStore.lockAcquireCount > attemptsAfterConflict, "drain should pick up the uuid after conflict cleared")
        }

    @Test
    fun drainResetsBackoffOnSuccess() =
        runTest {
            val env = testScheduler.env(backgroundScope)
            val (uuid, alsPath) = seedDirtyProject(env, "recover", updatedAtMs = -60_000, fileMtimeMs = -60_000)
            // Fail twice, then succeed.
            env.cloud.failuresRemaining = 2

            env.queue.start()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent() // attempt #2
            advanceTimeBy(60_000)
            runCurrent() // attempt #3 — succeeds, backoff cleared

            assertTrue(env.cloud.appendCount >= 1, "third attempt should have written a manifest")
            val baselineAcquires = env.metadataStore.lockAcquireCount

            // Mutate the file so the next push must upload a new blob (head-then-PUT). Without a
            // content change, `headBlob` would short-circuit and `failuresRemaining` would never
            // fire, since putBlob is the only failure injection point.
            alsPath.writeBytes("AbletonProject-v2".encodeToByteArray())
            Files.setLastModifiedTime(alsPath, FileTime.fromMillis(BASE_EPOCH_MS + testScheduler.currentTime - 60_000))

            env.cloud.failuresRemaining = 1
            env.syncState.markDirty(uuid)
            advanceTimeBy(1_000)
            runCurrent()
            val attemptsAfterFreshFail = env.metadataStore.lockAcquireCount
            assertTrue(attemptsAfterFreshFail > baselineAcquires, "version bump should have driven a retry")

            // After the fresh fail, backoff must be 30s (the floor). The next 60s wake will fire
            // drainOnce; the row's nextAttempt is past, so a retry happens. If backoff had carried
            // over the prior 60s/300s state, we'd see no retry within this window.
            advanceTimeBy(120_000)
            runCurrent()
            assertTrue(
                env.metadataStore.lockAcquireCount > attemptsAfterFreshFail,
                "after reset, 30s backoff should let retry happen well before 5m",
            )
        }

    @Test
    fun drainSkipsProjectsInMtimeQuietPeriod() =
        runTest {
            val env = testScheduler.env(backgroundScope)
            // mtime within last 30s (5s ago).
            val (_, alsPath) = seedDirtyProject(env, "fresh", updatedAtMs = -60_000, fileMtimeMs = -5_000)

            env.queue.start()
            runCurrent()
            advanceUntilIdle()

            assertEquals(0, env.metadataStore.lockAcquireCount, "fresh-mtime project should be skipped")

            // Age the file's mtime past the quiet period (now is at virtual t=current; set mtime
            // well into the past relative to the virtual clock).
            val virtualNowMs = testScheduler.currentTime
            Files.setLastModifiedTime(alsPath, FileTime.fromMillis(BASE_EPOCH_MS + virtualNowMs - 60_000))

            // Bump version so the loop wakes (markDirty re-stamps updated_at, but we want a wake
            // signal independent of the mtime change).
            val uuid =
                env.syncState
                    .dirtyOldestFirst()
                    .single()
                    .uuid
            env.syncState.markDirty(uuid)
            advanceTimeBy(1_000)
            runCurrent()
            advanceUntilIdle()

            assertEquals(1, env.cloud.appendCount, "drain should pick up the project once mtime ages out")
        }

    @Test
    fun stopCancelsDrainLoop() =
        runTest {
            val env = testScheduler.env(backgroundScope)
            seedDirtyProject(env, "halt", updatedAtMs = -60_000, fileMtimeMs = -60_000)

            env.queue.start()
            runCurrent()
            advanceUntilIdle()
            val countAfterFirstPush = env.cloud.appendCount
            assertTrue(countAfterFirstPush >= 1, "expected at least one push before stop")

            env.queue.stop()

            // Re-mark dirty after stop — without an active drain, this should be ignored.
            val uuid = env.syncState.identityFor(com.sketchbook.core.ProjectId(1L))
            env.syncState.markDirty(uuid)
            advanceTimeBy(3_600_000)
            runCurrent()
            advanceUntilIdle()

            assertEquals(countAfterFirstPush, env.cloud.appendCount, "stopped drain must not push")
        }
}

/**
 * Reads `currentTime` from the test scheduler so the queue's `clock.now()` advances in lockstep
 * with `advanceTimeBy()`.
 *
 * Anchored at [BASE_EPOCH_MS] (2026-05-06 UTC) so all derived file mtimes stay positive: ext4
 * on Linux clamps `Files.setLastModifiedTime` with negative values, which would silently break
 * the mtime quiet-period filter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(BASE_EPOCH_MS + scheduler.currentTime)
}

private const val BASE_EPOCH_MS = 1_777_900_800_000L // 2026-05-06 UTC, comfortably positive

/**
 * In-memory CloudBackend with two test knobs:
 *  - [failuresRemaining]: throw `IOException` on `putBlob` until decremented to 0.
 *  - [firstAppendIsConflict]: return `Conflict` on the first `appendManifestHead` to drive the
 *    pipeline's branch path.
 *
 * Counters cover what tests actually need to assert: `lockAcquireCount` is the most reliable
 * "did the drain attempt a push?" signal because the lease is acquired before any blob upload
 * (so it increments even on transient failure paths).
 */
private class CountingCloudBackend : CloudBackend {
    var failuresRemaining: Int = 0
    var firstAppendIsConflict: Boolean = false
    var appendCount: Int = 0
        private set

    private val blobs = mutableMapOf<Pair<BlobScope, BlobHash>, ByteArray>()
    private val manifests = mutableMapOf<ProjectUuid, MutableList<Pair<ManifestRef, Manifest>>>()
    private var nextGen = 1L
    private var appendCalls = 0

    private fun nextGeneration(): Generation = Generation((nextGen++).toString())

    override suspend fun headBlob(
        hash: BlobHash,
        scope: BlobScope,
    ) = blobs.containsKey(scope to hash)

    override suspend fun putBlob(
        hash: BlobHash,
        source: RawSource,
        size: Long,
        scope: BlobScope,
    ) {
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            throw java.io.IOException("simulated transient failure")
        }
        val bytes = source.buffered().readByteArray()
        blobs[scope to hash] = bytes
    }

    override suspend fun getBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): RawSource = error("not used in drain tests")

    override suspend fun readManifest(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): Manifest {
        val list = manifests[uuid] ?: throw SketchbookError.NotFound("no manifests for $uuid")
        return list.firstOrNull { it.second.rev == rev }?.second
            ?: throw SketchbookError.NotFound("rev $rev not found")
    }

    override suspend fun listManifests(
        uuid: ProjectUuid,
        sinceRev: SnapshotRev?,
    ): List<ManifestRef> {
        val list = manifests[uuid] ?: return emptyList()
        return list.map { it.first }.filter { sinceRev == null || it.rev > sinceRev.value }
    }

    override suspend fun appendManifestHead(
        uuid: ProjectUuid,
        expectedHead: Generation?,
        manifest: Manifest,
    ): Result<Generation> {
        appendCalls += 1
        // First call gets a synthetic Conflict if requested. Subsequent calls (the branch write
        // that follows the conflict path inside SnapshotPipeline) succeed normally.
        if (firstAppendIsConflict && appendCalls == 1) {
            return Result.failure(SketchbookError.Conflict("test-injected"))
        }
        appendCount += 1
        val gen = nextGeneration()
        val ref =
            ManifestRef(
                rev = manifest.rev.value,
                path = "manifests/${uuid.value}/${manifest.rev.value}.json",
                generation = gen,
            )
        manifests.getOrPut(uuid) { mutableListOf() }.add(ref to manifest)
        return Result.success(gen)
    }

    // Lock methods removed in Phase 3 — leases live in MetadataStore; CountingMetadataStore below
    // exposes the lock-acquire count tests rely on.
}

/**
 * MetadataStore wrapper that decorates [InMemoryMetadataStore] with a counter on acquireLock.
 * Drives the drain-backoff tests that previously counted on CloudBackend.acquireLock calls.
 */
private class CountingMetadataStore(
    private val delegate: com.sketchbook.cloud.metadata.InMemoryMetadataStore = com.sketchbook.cloud.metadata.InMemoryMetadataStore(),
) : com.sketchbook.cloud.metadata.MetadataStore by delegate {
    var lockAcquireCount: Int = 0
        private set

    override suspend fun acquireLock(
        path: com.sketchbook.cloud.metadata.DocPath,
        holder: String,
        ttl: kotlin.time.Duration,
    ): Boolean {
        lockAcquireCount += 1
        return delegate.acquireLock(path, holder, ttl)
    }
}
