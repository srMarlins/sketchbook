package com.sketchbook.sync

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
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
    private val ownerUserId: UserId = UserId.DEFAULT,
    private val hostId: String,
    private val hostName: String,
    private val clock: Clock = Clock.System,
    private val leaseTtl: Duration = 15.minutes,
) {

    fun run(input: PipelineInput): Flow<SnapshotProgress> = flow {
        val uuid = input.uuid
        val parentRev = input.lastKnownManifest?.rev
        val parentFiles = input.lastKnownManifest?.files ?: emptyMap()
        val parentExpectedHead = input.expectedHeadGeneration

        // 1) Lease.
        val leaseInstant = clock.now()
        val lock = LeaseLock(
            ownerHostId = hostId,
            ownerHostName = hostName,
            acquiredAt = leaseInstant,
            expiresAt = leaseInstant + leaseTtl,
        )
        val leaseGen: Generation = when (val r = cloud.acquireLock(uuid, lock)) {
            is LeaseAcquireResult.Acquired -> {
                emit(SnapshotProgress.LeaseAcquired(uuid))
                r.generation
            }
            is LeaseAcquireResult.Held -> {
                emit(SnapshotProgress.LeaseHeld(uuid, r.held.ownerHostName))
                emit(SnapshotProgress.Failed(uuid, "lock held by ${r.held.ownerHostName}"))
                return@flow
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

            // 3) Upload deduped: HEAD-then-PUT each unique blob.
            var bytesDone = 0L
            val totalUploadBytes = toUpload.values.sumOf { it.size }
            val uniqueByHash = toUpload.values.groupBy { it.hash }
            for ((hash, entries) in uniqueByHash) {
                val first = entries.first()
                val anyRel = toUpload.entries.first { it.value.hash == hash }.key
                if (cloud.headBlob(hash)) {
                    bytesDone += first.size
                    emit(SnapshotProgress.Uploading(uuid, hash, bytesDone, totalUploadBytes))
                    continue
                }
                cloud.putBlob(hash, input.tree.read(anyRel), first.size)
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
            val newBytes = toUpload.values.sumOf { it.size }
            val auto = Manifest(
                ownerUserId = ownerUserId,
                projectUuid = uuid,
                rev = newRev,
                parentRev = parentRev,
                timestamp = clock.now(),
                hostId = hostId,
                hostName = hostName,
                kind = SnapshotKind.Auto,
                files = files,
                stats = ManifestStats(
                    fileCount = files.size,
                    totalBytes = totalBytes,
                    newBytes = newBytes,
                ),
            )

            // 5) CAS HEAD. On Conflict, re-fetch and write our work as a branch.
            val casResult = cloud.appendManifestHead(uuid, parentExpectedHead, auto)
            val saved = casResult.fold(
                onSuccess = {
                    SnapshotProgress.Saved(uuid, newRev, SnapshotKind.Auto)
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
                        val branch = auto.copy(
                            rev = branchRev,
                            parentRev = parentRev,
                            kind = SnapshotKind.Branch,
                            label = label,
                            timestamp = ts,
                        )
                        val branchResult = cloud.appendManifestHead(uuid, latest.generation, branch)
                        branchResult.fold(
                            onSuccess = {
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
            runCatching { cloud.releaseLock(uuid, leaseGen) }
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
)
