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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
) {
    fun run(input: PipelineInput): Flow<SnapshotProgress> =
        flow {
            val uuid = input.uuid
            val parentRev = input.lastKnownManifest?.rev
            val parentFiles = input.lastKnownManifest?.files ?: emptyMap()
            val parentExpectedHead = input.expectedHeadGeneration
            val blobScope: BlobScope = if (input.selfContained) BlobScope.Private(uuid) else BlobScope.Shared

            // 1) Lease — Firestore-backed CAS via MetadataStore. The lock doc lives at
            //    /users/{uid}/locks/{treeId} per the design data model. Phase 3 ships the
            //    metadata-side; the host-name population is a follow-up setDoc below so the
            //    "held by X" UI has a friendly label even though the atomic primitive doesn't
            //    take one.
            val lockPath = DocPath.lock(ownerUserId.value, uuid.value)
            val acquired = metadataStore.acquireLock(lockPath, holder = hostId, ttl = leaseTtl)
            if (!acquired) {
                val current = metadataStore.getDoc(lockPath, LockDoc.serializer())
                val ownerLabel = current?.holderName?.takeIf { it.isNotBlank() } ?: current?.holder ?: "another host"
                emit(SnapshotProgress.LeaseHeld(uuid, ownerLabel))
                emit(SnapshotProgress.Failed(uuid, "lock held by $ownerLabel"))
                return@flow
            }
            emit(SnapshotProgress.LeaseAcquired(uuid))
            // Best-effort: backfill the holder-name on the lock doc so the UI side has a
            // label. The acquireLock CAS wrote with holderName="" — overwriting with the
            // populated doc is safe because we now own the lease.
            metadataStore.getDoc(lockPath, LockDoc.serializer())?.let { acq ->
                if (acq.holder == hostId && acq.holderName != hostName) {
                    metadataStore.setDoc(lockPath, acq.copy(holderName = hostName), LockDoc.serializer())
                }
            }

            try {
                // 2) Walk + diff.
                val rels = input.tree.list()
                val unchanged = mutableMapOf<String, ManifestFile>()
                val toUpload = mutableMapOf<String, ManifestFile>()
                rels.forEachIndexed { i, rel ->
                    emit(SnapshotProgress.Hashing(uuid, i + 1, rels.size))
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
                        emit(SnapshotProgress.Uploading(uuid, hash, bytesDone, totalUploadBytes))
                        continue
                    }
                    cloud.putBlob(hash, input.tree.read(anyRel), first.size, blobScope)
                    actuallyUploadedBytes += first.size
                    bytesDone += first.size
                    emit(SnapshotProgress.Uploading(uuid, hash, bytesDone, totalUploadBytes))
                }

                // 4) Compose manifest. New rev = parentRev+1, or 1 if no parent.
                val newRev = parentRev?.next() ?: SnapshotRev(1)
                emit(SnapshotProgress.WritingManifest(uuid, newRev))

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
                                    emit(SnapshotProgress.Failed(uuid, "conflict but cannot find new HEAD"))
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
                if (saved != null) emit(saved)
            } finally {
                // 6) Release lease (best-effort; pipeline outcome already emitted).
                runCatching { metadataStore.releaseLock(lockPath, holder = hostId) }
            }
        }

    /**
     * Post-CAS head publication. Update the tree's Firestore doc with the new head_rev /
     * head_gen / head_updated_at / head_updated_by_host so [SyncCoordinator]s on other
     * machines fire `pollOnce` on the listener's next emission.
     *
     * Best-effort: if Firestore is unreachable the local manifest was still appended to
     * Storage and other machines will discover it on their next manual pull. Logging the
     * failure is better than failing the pipeline — the snapshot DID save locally, just the
     * cross-machine notification didn't go out.
     */
    private suspend fun writeTreeHeadToFirestore(
        uuid: ProjectUuid,
        rev: SnapshotRev,
        gen: com.sketchbook.cloud.Generation,
    ) {
        val path = DocPath.tree(ownerUserId.value, uuid.value)
        val now = clock.now()
        runCatching {
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
        }
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
