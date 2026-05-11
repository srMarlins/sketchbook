package com.sketchbook.sync

import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.LockDoc
import com.sketchbook.cloud.metadata.MetadataStore
import com.sketchbook.cloud.metadata.TreeDoc
import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.UserId
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Orchestrates the §4.2 save-to-snapshot pipeline.
 *
 * Inputs are intentionally narrow so this stays testable in commonTest with an in-memory
 * [CloudBackend] + [WorkingTree]. The JVM application (PR-18) wires up the real watcher → tree
 * walker → repository chain on top.
 *
 * Returned [Flow] emits live progress and ends with either [SnapshotProgress.Saved] (success or
 * branch-fork) or [SnapshotProgress.Failed].
 */
class SnapshotPipeline(
    private val cloud: CloudBackend,
    private val metadataStore: MetadataStore,
    private val ownerUserId: UserId = UserId.DEFAULT,
    private val hostId: String,
    private val hostName: String,
    private val clock: Clock = Clock.System,
    private val leaseTtl: Duration = 15.minutes,
    /**
     * Cadence for in-flight lease refresh while [run] holds the lock. 5min is the project-
     * wide default; tests override this to verify the refresh actually fires. Must be
     * strictly less than [leaseTtl] (less margin = more refresh churn; more margin = risk
     * of losing the lease on a brief network blip during a long save).
     */
    private val heartbeatInterval: Duration = 5.minutes,
) {
    fun run(input: PipelineInput): Flow<SnapshotProgress> =
        channelFlow {
            val uuid = input.uuid
            val parentRev = input.lastKnownManifest?.rev
            val parentFiles = input.lastKnownManifest?.files ?: emptyMap()
            val parentExpectedHead = input.expectedHeadGeneration
            val blobScope: BlobScope = if (input.selfContained) BlobScope.Private(uuid) else BlobScope.Shared

            // 1) Lease — Firestore-backed CAS via MetadataStore. The lock doc lives at
            //    /users/{uid}/locks/{treeId} per the design data model. holderName goes in
            //    the same CAS write so the "held by X" UI label lands in one round-trip.
            val lockPath = DocPath.lock(ownerUserId.value, uuid.value)
            val acquired =
                metadataStore.acquireLock(
                    path = lockPath,
                    holder = hostId,
                    ttl = leaseTtl,
                    holderName = hostName,
                )
            if (!acquired) {
                val current = metadataStore.getDoc(lockPath, LockDoc.serializer())
                val ownerLabel = current?.holderName?.takeIf { it.isNotBlank() } ?: current?.holder ?: "another host"
                send(SnapshotProgress.LeaseHeld(uuid, ownerLabel))
                send(SnapshotProgress.Failed(uuid, "lock held by $ownerLabel"))
                return@channelFlow
            }
            send(SnapshotProgress.LeaseAcquired(uuid))

            // Heartbeat loop, launched on the channelFlow's producerScope (`this`). The
            // producerScope is a child scope of the flow's collection context; channelFlow
            // automatically cancels every child coroutine when the flow body returns. That
            // means the heartbeat's lifetime is bounded by the body without the flow's
            // emit/send being gated on heartbeat-cancellation propagation. We still cancel
            // the job explicitly in the finally block below so a refresh in flight at the
            // end of the save doesn't re-extend the TTL after we delete the doc.
            val heartbeatJob: Job =
                launch {
                    while (currentCoroutineContext().isActive) {
                        delay(heartbeatInterval)
                        try {
                            if (!metadataStore.refreshLock(lockPath, holder = hostId, ttl = leaseTtl)) {
                                // Lost the lease — abort the heartbeat. The pipeline body
                                // keeps running; if the CAS at step 5 races a takeover it
                                // will surface as a Conflict.
                                return@launch
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            // Transient refresh failure (network blip) — log and retry on
                            // the next cadence rather than permanently giving up (N6). The
                            // lease still has its previous TTL; we have slack.
                            System.err.println("[SnapshotPipeline] lease refresh failed (will retry): $t")
                        }
                    }
                }

            try {
                // 2) Walk + diff.
                val rels = input.tree.list()
                val unchanged = mutableMapOf<String, ManifestFile>()
                val toUpload = mutableMapOf<String, ManifestFile>()
                rels.forEachIndexed { i, rel ->
                    send(SnapshotProgress.Hashing(uuid, i + 1, rels.size))
                    val stat = input.tree.stat(rel)
                    val parent = parentFiles[rel]
                    if (parent != null && parent.size == stat.size && parent.mtime == stat.mtime) {
                        unchanged[rel] = parent
                        return@forEachIndexed
                    }
                    val hash = input.tree.hash(rel)
                    val entry = ManifestFile(hash = hash, size = stat.size, mtime = stat.mtime)
                    if (parent != null && parent.hash == hash) {
                        unchanged[rel] = entry
                    } else {
                        toUpload[rel] = entry
                    }
                }

                // 3) Upload deduped: HEAD-then-PUT each unique blob. `actuallyUploadedBytes` tracks
                // only the bytes that left this host — when `headBlob` returns true we count it as
                // reused, not new. The figure flows into the manifest's stats.newBytes.
                var bytesDone = 0L
                val totalUploadBytes = toUpload.values.sumOf { it.size }
                val uniqueByHash = toUpload.values.groupBy { it.hash }
                var actuallyUploadedBytes = 0L
                for ((hash, entries) in uniqueByHash) {
                    val first = entries.first()
                    val anyRel = toUpload.entries.first { it.value.hash == hash }.key
                    if (cloud.headBlob(hash, blobScope)) {
                        bytesDone += first.size
                        send(SnapshotProgress.Uploading(uuid, hash, bytesDone, totalUploadBytes))
                        continue
                    }
                    cloud.putBlob(hash, input.tree.read(anyRel), first.size, blobScope)
                    actuallyUploadedBytes += first.size
                    bytesDone += first.size
                    send(SnapshotProgress.Uploading(uuid, hash, bytesDone, totalUploadBytes))
                }

                // 4) Compose manifest. New rev = parentRev+1, or 1 if no parent.
                val newRev = parentRev?.next() ?: SnapshotRev(1)
                send(SnapshotProgress.WritingManifest(uuid, newRev))

                val files = LinkedHashMap<String, ManifestFile>(unchanged.size + toUpload.size)
                files.putAll(unchanged)
                files.putAll(toUpload)
                val totalBytes = files.values.sumOf { it.size }
                val newBytes = actuallyUploadedBytes
                // Caller can promote this snapshot to a Named entry by setting [PipelineInput.kind]
                // (with optional [PipelineInput.label]). The Z3 quick-capture hotkey uses that to
                // force a labeled timeline row regardless of dirty flag. Default stays Auto so the
                // existing watcher-driven path is unchanged.
                val auto =
                    Manifest(
                        ownerUserId = ownerUserId,
                        projectUuid = uuid,
                        rev = newRev,
                        parentRev = parentRev,
                        timestamp = clock.now(),
                        hostId = hostId,
                        hostName = hostName,
                        kind = input.kind,
                        label = input.label,
                        files = files,
                        stats =
                            ManifestStats(
                                fileCount = files.size,
                                totalBytes = totalBytes,
                                newBytes = newBytes,
                            ),
                        selfContained = input.selfContained,
                    )

                // 5) CAS HEAD. On Conflict, re-fetch and write our work as a branch.
                val casResult = cloud.appendManifestHead(uuid, parentExpectedHead, auto)
                val saved =
                    casResult.fold(
                        onSuccess = { gen ->
                            writeTreeHeadToFirestore(uuid, newRev, gen)
                            SnapshotProgress.Saved(uuid, newRev, input.kind, input.label)
                        },
                        onFailure = { err ->
                            if (err is SketchbookError.Conflict) {
                                // Re-fetch latest HEAD generation by listing manifests; pick the last one
                                // and rev one above it. Fake/real backends both return ManifestRefs sorted
                                // by rev; if the list is empty something is wrong, propagate as failure.
                                val refs = cloud.listManifests(uuid, sinceRev = parentRev)
                                val latest = refs.maxByOrNull { it.rev }
                                if (latest == null) {
                                    send(SnapshotProgress.Failed(uuid, "conflict but cannot find new HEAD"))
                                    return@fold null
                                }
                                val branchRev = SnapshotRev(latest.rev + 1)
                                val ts = clock.now()
                                val label = "auto-fork: $hostName-${ts.toEpochMilliseconds()}"
                                val branch =
                                    auto.copy(
                                        rev = branchRev,
                                        parentRev = parentRev,
                                        kind = SnapshotKind.Branch,
                                        label = label,
                                        timestamp = ts,
                                    )
                                val branchResult = cloud.appendManifestHead(uuid, latest.generation, branch)
                                branchResult.fold(
                                    onSuccess = { branchGen ->
                                        writeTreeHeadToFirestore(uuid, branchRev, branchGen)
                                        SnapshotProgress.Saved(uuid, branchRev, SnapshotKind.Branch, label)
                                    },
                                    onFailure = { e ->
                                        SnapshotProgress.Failed(uuid, "branch write failed: ${e.message}")
                                    },
                                )
                            } else {
                                SnapshotProgress.Failed(uuid, "head write failed: ${err.message}")
                            }
                        },
                    )
            if (saved != null) send(saved)
            } finally {
                // 6) Stop heartbeating + release lease. Cancel the heartbeat first so a
                //    refresh in flight at this moment doesn't re-extend the TTL after we
                //    delete the doc. We don't need to join — channelFlow already waits for
                //    its child coroutines (the heartbeat launch above) before the flow
                //    completes downstream, and the cancel propagates through the producerScope.
                heartbeatJob.cancel()
                try {
                    metadataStore.releaseLock(lockPath, holder = hostId)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    // Pipeline outcome was already emitted; releaseLock is best-effort cleanup.
                }
            }
        }

    /**
     * Post-CAS head publication. Update the tree's Firestore doc with the new head_rev /
     * head_gen / head_updated_at / head_updated_by_host so [SyncCoordinator]s on other
     * machines fire `pollOnce` on the listener's next emission.
     *
     * Retried up to 3 times with exponential backoff (200ms, 400ms) — head publication is
     * the cross-machine notification primitive; a transient Firestore blip (network blip,
     * brief 5xx, dropped listener gRPC stream) shouldn't cost us a cycle of sync latency.
     * If all retries fail we log and return: the local manifest was still appended to
     * Storage, so other machines will discover it on their next manual pull.
     *
     * CancellationException is re-thrown without retry so the surrounding pipeline coroutine
     * can unwind cleanly when the caller cancels (e.g. UID change, app shutdown).
     */
    private suspend fun writeTreeHeadToFirestore(
        uuid: ProjectUuid,
        rev: SnapshotRev,
        gen: com.sketchbook.cloud.Generation,
    ) {
        val path = DocPath.tree(ownerUserId.value, uuid.value)
        val now = clock.now()
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < HEAD_WRITE_MAX_ATTEMPTS) {
            try {
                metadataStore.updateDoc(path, TreeDoc.serializer()) { existing ->
                    existing?.copy(
                        head_rev = rev.value,
                        head_gen = gen.raw,
                        head_updated_at = now,
                        head_updated_by_host = hostId,
                    ) ?: TreeDoc(
                        owner_user_id = ownerUserId.value,
                        display_name = uuid.value,
                        created_at = now,
                        created_by_host = hostId,
                        head_rev = rev.value,
                        head_gen = gen.raw,
                        head_updated_at = now,
                        head_updated_by_host = hostId,
                    )
                }
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                attempt++
                if (attempt >= HEAD_WRITE_MAX_ATTEMPTS) break
                // 200ms, 400ms — two retries inside ~600ms total. Small enough that a
                // user-perceived save isn't blocked, large enough to clear a brief 5xx.
                delay(HEAD_WRITE_BASE_DELAY_MS shl (attempt - 1))
            }
        }
        System.err.println(
            "[SnapshotPipeline] tree head write failed after $HEAD_WRITE_MAX_ATTEMPTS attempts for uuid=${uuid.value}: $lastError",
        )
    }

    private companion object {
        const val HEAD_WRITE_MAX_ATTEMPTS = 3
        const val HEAD_WRITE_BASE_DELAY_MS = 200L
    }
}

/**
 * Snapshot pipeline inputs: the project's identity, an on-disk view, and what we currently know
 * about the cloud HEAD.
 *
 * - [lastKnownManifest] enables the unchanged-file diff. `null` on the first sync.
 * - [expectedHeadGeneration] is the CAS token: pass [Generation.ZERO] on first write.
 */
data class PipelineInput(
    val uuid: ProjectUuid,
    val tree: WorkingTree,
    val lastKnownManifest: Manifest?,
    val expectedHeadGeneration: Generation?,
    /**
     * When true, blob uploads use [BlobScope.Private] keyed by [uuid]: the project's bytes never
     * dedup with any other project. Driven by `sync_state.self_contained` (managed via
     * `SettingsRepository.setSelfContained`).
     */
    val selfContained: Boolean = false,
    /**
     * Snapshot kind to record. Defaults to [SnapshotKind.Auto] so the existing watcher-driven
     * save path is unchanged. The Z3 quick-capture hotkey passes [SnapshotKind.Named] with a
     * user-supplied [label] to force a labeled timeline row. CAS-conflict still re-classifies
     * the resulting manifest as [SnapshotKind.Branch] regardless of this hint — divergence
     * always wins over Named.
     */
    val kind: SnapshotKind = SnapshotKind.Auto,
    /** Human-readable label attached to the manifest. Pairs with [kind] = Named. */
    val label: String? = null,
)
