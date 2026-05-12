package com.sketchbook.liveit

import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.metadata.AcquireResult
import com.sketchbook.cloud.metadata.CollectionEntry
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.TreeDoc
import com.sketchbook.core.ProjectUuid
import com.sketchbook.sync.SnapshotProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val LISTENER_WAIT_TIMEOUT_MS = 30_000L
private const val LOCK_BARRIER_WAIT_MS = 500L
private const val LOCK_EXPIRY_TTL_SEC = 2L
private const val LOCK_EXPIRY_WAIT_SEC = 3L
private const val TEST_UUID_RAND_RANGE = 0xffff
private const val TEST_UUID_RAND_WIDTH = 4

/**
 * Aggregate result for one scenario. [narrative] is a short prose summary so the runner can
 * print a human-readable line per scenario in the final report; [timings] holds wall-clock
 * milliseconds for key events so we can call out e.g. listener latency.
 */
data class ScenarioResult(
    val name: String,
    val success: Boolean,
    val narrative: String,
    val timings: Map<String, Long> = emptyMap(),
    val failure: String? = null,
)

object TwoClientScenarios {
    val ALL = listOf("linearSync", "collectionListener", "editAndResync", "lockContention", "bidirectional", "lockExpiry")

    suspend fun run(
        name: String,
        harness: TwoClientHarness,
        templateDir: Path,
    ): ScenarioResult =
        when (name) {
            "linearSync" -> linearSync(harness, templateDir)
            "collectionListener" -> collectionListener(harness, templateDir)
            "editAndResync" -> editAndResync(harness, templateDir)
            "lockContention" -> lockContention(harness, templateDir)
            "bidirectional" -> bidirectional(harness, templateDir)
            "lockExpiry" -> lockExpiry(harness, templateDir)
            else -> ScenarioResult(name, success = false, narrative = "unknown scenario", failure = "unknown")
        }

    /**
     * A pushes; B's listener picks up the head_rev advance; B materializes; assert byte
     * equality of A's and B's working trees (over [TwoClientFs.hashTree]'s included-file
     * set, which mirrors [com.sketchbook.syncio.JvmWorkingTree]'s exclusion rules).
     *
     * The listener subscription is set up BEFORE A's push so the measured latency reflects
     * the actual cross-machine notification path (CAS lands → Firestore propagates →
     * listener emits) and not the cost of subscribing.
     */
    private suspend fun linearSync(
        harness: TwoClientHarness,
        templateDir: Path,
    ): ScenarioResult =
        scenario("linearSync", harness) { uuid ->
            TwoClientFs.seedFromTemplate(templateDir, harness.clientA.workDir)
            // B's workDir starts empty — it pulls everything in this scenario.

            coroutineScope {
                val listenerSawHead =
                    async {
                        withTimeoutOrNull(LISTENER_WAIT_TIMEOUT_MS.milliseconds) {
                            harness
                                .observeTreeDoc(uuid)
                                .first { it != null && it.head_rev >= 1L }
                        }
                    }
                // Yield once so the listener actually subscribes before we kick off the push.
                delay(LOCK_BARRIER_WAIT_MS.milliseconds)

                val tPushStart = nowMs()
                val pushed =
                    requireSaved(
                        harness.snapshot(
                            harness.clientA,
                            uuid,
                            lastKnownManifest = null,
                            expectedHeadGeneration = Generation.ZERO,
                        ),
                    )
                val tPushDone = nowMs()
                val listenerEmission =
                    listenerSawHead.await()
                        ?: error("B's listener did not see head_rev≥1 within ${LISTENER_WAIT_TIMEOUT_MS}ms")
                val tListenerFired = nowMs()
                check(listenerEmission.head_updated_by_host == harness.clientA.hostId) {
                    "B's listener saw head from ${listenerEmission.head_updated_by_host}, expected ${harness.clientA.hostId}"
                }

                harness.materializeInto(harness.clientB, uuid)
                val tMaterialized = nowMs()

                assertTreesEqual(harness.clientA.workDir, harness.clientB.workDir)

                ScenarioResult(
                    name = "linearSync",
                    success = true,
                    narrative =
                        "A pushed rev=${pushed.rev.value}; B's listener fired after " +
                            "${tListenerFired - tPushDone}ms; materialize took ${tMaterialized - tListenerFired}ms.",
                    timings =
                        mapOf(
                            "push_ms" to (tPushDone - tPushStart),
                            "listener_latency_ms" to (tListenerFired - tPushDone),
                            "materialize_ms" to (tMaterialized - tListenerFired),
                        ),
                )
            }
        }

    /**
     * Same push/listen/materialize sequence as [linearSync], but B subscribes to the
     * *collection* listener (`observeCollection` on `users/{uid}/trees`) rather than a
     * single-doc listener. This mirrors the actual [com.sketchbook.sync.SyncCoordinator]
     * production path — the desktop app listens to the whole trees collection, not individual
     * docs. Collection and single-doc Firestore listeners have subtly different semantics:
     * the collection listener fires for *new* docs (which [linearSync]'s doc listener never
     * sees since the doc doesn't exist yet when it subscribes) in addition to updates.
     *
     * Passes when the collection emission contains an entry for [uuid] with `head_rev ≥ 1`,
     * the entry names the pushing host, and the materialized bytes equal A's working tree.
     */
    private suspend fun collectionListener(
        harness: TwoClientHarness,
        templateDir: Path,
    ): ScenarioResult =
        scenario("collectionListener", harness) { uuid ->
            TwoClientFs.seedFromTemplate(templateDir, harness.clientA.workDir)

            coroutineScope {
                val listenerSawHead =
                    async {
                        withTimeoutOrNull(LISTENER_WAIT_TIMEOUT_MS.milliseconds) {
                            harness
                                .observeTreesCollection()
                                .first { entries ->
                                    entries.any { it.id == uuid.value && it.value.head_rev >= 1L }
                                }
                        }
                    }
                delay(LOCK_BARRIER_WAIT_MS.milliseconds)

                val tPushStart = nowMs()
                val pushed =
                    requireSaved(
                        harness.snapshot(
                            harness.clientA,
                            uuid,
                            lastKnownManifest = null,
                            expectedHeadGeneration = Generation.ZERO,
                        ),
                    )
                val tPushDone = nowMs()
                val emission: List<CollectionEntry<TreeDoc>> =
                    listenerSawHead.await()
                        ?: error("collection listener did not see head_rev≥1 for ${uuid.value} within ${LISTENER_WAIT_TIMEOUT_MS}ms")
                val tListenerFired = nowMs()

                val treeEntry = emission.first { it.id == uuid.value }
                check(treeEntry.value.head_updated_by_host == harness.clientA.hostId) {
                    "collection listener: head_updated_by_host=${treeEntry.value.head_updated_by_host}, " +
                        "expected ${harness.clientA.hostId}"
                }

                harness.materializeInto(harness.clientB, uuid)
                val tMaterialized = nowMs()

                assertTreesEqual(harness.clientA.workDir, harness.clientB.workDir)

                ScenarioResult(
                    name = "collectionListener",
                    success = true,
                    narrative =
                        "A pushed rev=${pushed.rev.value}; collection listener fired after " +
                            "${tListenerFired - tPushDone}ms; materialize took ${tMaterialized - tListenerFired}ms.",
                    timings =
                        mapOf(
                            "push_ms" to (tPushDone - tPushStart),
                            "listener_latency_ms" to (tListenerFired - tPushDone),
                            "materialize_ms" to (tMaterialized - tListenerFired),
                        ),
                )
            }
        }

    /**
     * linearSync, then A drops a deterministic edit file, pushes v2, B catches v2 via the
     * already-running listener, materializes. Asserts the new file is on B's side.
     */
    private suspend fun editAndResync(
        harness: TwoClientHarness,
        templateDir: Path,
    ): ScenarioResult =
        scenario("editAndResync", harness) { uuid ->
            TwoClientFs.seedFromTemplate(templateDir, harness.clientA.workDir)

            coroutineScope {
                val listenerV1 =
                    async {
                        withTimeoutOrNull(LISTENER_WAIT_TIMEOUT_MS.milliseconds) {
                            harness.observeTreeDoc(uuid).first { it != null && it.head_rev >= 1L }
                        }
                    }
                delay(LOCK_BARRIER_WAIT_MS.milliseconds)
                val v1 = requireSaved(harness.snapshot(harness.clientA, uuid, null, Generation.ZERO))
                listenerV1.await() ?: error("B did not see v1")
                val v1Manifest = harness.materializeInto(harness.clientB, uuid)
                val v1Gen = Generation(harness.readTreeDoc(uuid)!!.head_gen)

                // A edits its workDir.
                TwoClientFs.addEdit(harness.clientA.workDir, rev = 2)

                val listenerV2 =
                    async {
                        withTimeoutOrNull(LISTENER_WAIT_TIMEOUT_MS.milliseconds) {
                            harness.observeTreeDoc(uuid).first { it != null && it.head_rev >= 2L }
                        }
                    }
                delay(LOCK_BARRIER_WAIT_MS.milliseconds)
                val tV2Start = nowMs()
                val v2 = requireSaved(harness.snapshot(harness.clientA, uuid, v1Manifest, v1Gen))
                val tV2Done = nowMs()
                listenerV2.await() ?: error("B did not see v2")
                val tV2Listener = nowMs()

                harness.materializeInto(harness.clientB, uuid)
                val tV2Materialized = nowMs()

                assertTreesEqual(harness.clientA.workDir, harness.clientB.workDir)
                check(harness.clientB.workDir.resolve("live-integration-edit-rev-2.txt").let { java.nio.file.Files.isRegularFile(it) }) {
                    "edit-rev-2 file not present in B's workDir after materialize"
                }

                ScenarioResult(
                    name = "editAndResync",
                    success = true,
                    narrative =
                        "A pushed v1=${v1.rev.value} + edit + v2=${v2.rev.value}; " +
                            "B caught v2 in ${tV2Listener - tV2Done}ms, materialize ${tV2Materialized - tV2Listener}ms.",
                    timings =
                        mapOf(
                            "v2_push_ms" to (tV2Done - tV2Start),
                            "v2_listener_latency_ms" to (tV2Listener - tV2Done),
                            "v2_materialize_ms" to (tV2Materialized - tV2Listener),
                        ),
                )
            }
        }

    /**
     * A and B simultaneously call SnapshotPipeline.run for the same UUID. Lock primitive
     * picks a winner via Firestore transaction; the loser sees `LeaseHeld` and the pipeline
     * emits a `Failed` terminal event.
     *
     * Implemented with a [CompletableDeferred] barrier rather than a Firestore-side gate:
     * both coroutines park on the same in-process latch, then resume in the same scheduler
     * quantum. The actual contention happens at the [com.sketchbook.cloud.metadata.MetadataStore.acquireLock]
     * call — that's the layer we're testing.
     */
    private suspend fun lockContention(
        harness: TwoClientHarness,
        templateDir: Path,
    ): ScenarioResult =
        scenario("lockContention", harness) { uuid ->
            TwoClientFs.seedFromTemplate(templateDir, harness.clientA.workDir)
            TwoClientFs.seedFromTemplate(templateDir, harness.clientB.workDir)

            coroutineScope {
                val gate = CompletableDeferred<Unit>()
                val pushA =
                    async {
                        gate.await()
                        harness.snapshot(harness.clientA, uuid, null, Generation.ZERO)
                    }
                val pushB =
                    async {
                        gate.await()
                        harness.snapshot(harness.clientB, uuid, null, Generation.ZERO)
                    }
                // Let both coroutines park on the gate before flipping it.
                delay(LOCK_BARRIER_WAIT_MS.milliseconds)
                val tGateOpen = nowMs()
                gate.complete(Unit)

                val (rA, rB) = listOf(pushA, pushB).awaitAll().let { it[0] to it[1] }
                val tDone = nowMs()

                val winners = listOf(rA, rB).filterIsInstance<SnapshotProgress.Saved>()
                val losers = listOf(rA, rB).filterIsInstance<SnapshotProgress.Failed>()
                check(winners.size == 1 && losers.size == 1) {
                    "expected exactly one Saved + one Failed; got winners=$winners losers=$losers"
                }
                val winnerHostId =
                    if (rA is SnapshotProgress.Saved) harness.clientA.hostId else harness.clientB.hostId
                val loserReason = losers.single().reason
                check(loserReason.contains("lock held", ignoreCase = true)) {
                    "loser's reason should mention the lock; got '$loserReason'"
                }

                ScenarioResult(
                    name = "lockContention",
                    success = true,
                    narrative =
                        "Both A+B pushed concurrently. Winner=$winnerHostId rev=${winners.single().rev.value}; " +
                            "loser failed with '$loserReason'. Race resolved in ${tDone - tGateOpen}ms.",
                    timings = mapOf("race_resolution_ms" to (tDone - tGateOpen)),
                )
            }
        }

    /**
     * Ping-pong. A pushes v1, B materializes + edits + pushes v2, A materializes v2. Asserts
     * both ends converge on the same byte set (excluding [com.sketchbook.syncio.JvmWorkingTree]'s
     * skip dirs).
     */
    private suspend fun bidirectional(
        harness: TwoClientHarness,
        templateDir: Path,
    ): ScenarioResult =
        scenario("bidirectional", harness) { uuid ->
            TwoClientFs.seedFromTemplate(templateDir, harness.clientA.workDir)

            val v1 = requireSaved(harness.snapshot(harness.clientA, uuid, null, Generation.ZERO))
            val v1Gen = Generation(harness.readTreeDoc(uuid)!!.head_gen)
            val v1Manifest = harness.materializeInto(harness.clientB, uuid)

            TwoClientFs.addEdit(harness.clientB.workDir, rev = 2)
            val v2 = requireSaved(harness.snapshot(harness.clientB, uuid, v1Manifest, v1Gen))

            harness.materializeInto(harness.clientA, uuid)
            assertTreesEqual(harness.clientA.workDir, harness.clientB.workDir)

            ScenarioResult(
                name = "bidirectional",
                success = true,
                narrative =
                    "A→cloud rev=${v1.rev.value}; B materialized, edited, pushed rev=${v2.rev.value}; " +
                        "A materialized back. Both trees converged on the same bytes.",
            )
        }

    /**
     * Acquire the lock for A directly via the MetadataStore with a short TTL, then wait
     * past expiry without heartbeating. B then runs a normal push — its `acquireLock` call
     * should return `Acquired` (because the existing lease has expired) and the push should
     * complete.
     *
     * This is the "stale lease cleanup" path. A real forced-takeover (UI-initiated) goes
     * through a different surface (writing a new LockDoc with `force=true` semantics); that
     * scenario is left out of v1.
     */
    private suspend fun lockExpiry(
        harness: TwoClientHarness,
        templateDir: Path,
    ): ScenarioResult =
        scenario("lockExpiry", harness) { uuid ->
            TwoClientFs.seedFromTemplate(templateDir, harness.clientB.workDir)
            val lockPath = DocPath.lock(harness.graph.userId.value, uuid.value)
            val acquired =
                harness.graph.metadataStore.acquireLock(
                    path = lockPath,
                    holder = harness.clientA.hostId,
                    ttl = LOCK_EXPIRY_TTL_SEC.seconds,
                    holderName = harness.clientA.hostName,
                )
            check(acquired == AcquireResult.Acquired) {
                "expected A to acquire the lock; got $acquired"
            }
            delay(LOCK_EXPIRY_WAIT_SEC.seconds)
            // Lease has now expired (TTL=2s, waited 3s). B's push should sail through.
            val v1 = requireSaved(harness.snapshot(harness.clientB, uuid, null, Generation.ZERO))

            ScenarioResult(
                name = "lockExpiry",
                success = true,
                narrative =
                    "A acquired the lock with TTL=${LOCK_EXPIRY_TTL_SEC}s; after waiting " +
                        "${LOCK_EXPIRY_WAIT_SEC}s for expiry, B pushed rev=${v1.rev.value} successfully.",
            )
        }

    // ---- shared helpers ----

    private suspend fun scenario(
        name: String,
        harness: TwoClientHarness,
        body: suspend (uuid: ProjectUuid) -> ScenarioResult,
    ): ScenarioResult {
        val uuid = mintScenarioUuid(name)
        return try {
            val r = body(uuid)
            harness.cleanup(uuid)
            r
        } catch (t: Throwable) {
            runCatching { harness.cleanup(uuid) }
            ScenarioResult(
                name = name,
                success = false,
                narrative = t.message ?: t::class.simpleName ?: "unknown failure",
                failure = t.stackTraceToString(),
            )
        }
    }

    private fun mintScenarioUuid(scenarioName: String): ProjectUuid {
        val epoch = Clock.System.now().epochSeconds
        val rand =
            java.security.SecureRandom()
                .nextInt(TEST_UUID_RAND_RANGE)
                .toString(HEX_RADIX)
                .padStart(TEST_UUID_RAND_WIDTH, '0')
        return ProjectUuid("${LiveTestEnv.TEST_UUID_PREFIX}tc-$scenarioName-$epoch-$rand")
    }

    private fun requireSaved(progress: SnapshotProgress): SnapshotProgress.Saved =
        when (progress) {
            is SnapshotProgress.Saved -> progress
            is SnapshotProgress.Failed -> error("push failed: ${progress.reason}")
            else -> error("expected Saved, got $progress")
        }

    private fun assertTreesEqual(
        a: Path,
        b: Path,
    ) {
        val ha = TwoClientFs.hashTree(a)
        val hb = TwoClientFs.hashTree(b)
        if (ha != hb) {
            val missingInB = ha.keys - hb.keys
            val missingInA = hb.keys - ha.keys
            val differing =
                ha.keys.intersect(hb.keys).filter { ha[it] != hb[it] }
            error(
                "tree mismatch: missingInB=$missingInB missingInA=$missingInA differing=$differing",
            )
        }
    }

    private fun nowMs(): Long = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toEpochMilliseconds()

    private const val HEX_RADIX = 16
}
