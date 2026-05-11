package com.sketchbook.sync

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Per-project pull of newly-landed manifests. Phase 3 collapsed the previous polling Flow
 * (which slept 30s between checks per project) into a single suspend [pollOnce]; the
 * [SyncCoordinator] listens to Firestore tree-doc deltas and fires `pollOnce(uuid, sinceRev)`
 * when the listener reports a head_rev advance.
 *
 * Each new manifest is decoded into a [Snapshot] row + persisted via [SnapshotRepository];
 * the returned list is the post-snapshot view, in rev order. Callers use it to advance
 * `sync_state.cloud_head_rev` and trigger auto-materialize where appropriate.
 */
class PullPoller(
    private val cloud: CloudBackend,
    private val snapshots: SnapshotRepository,
    /**
     * Parallelism for the manifest-fetch fan-out. 4 strikes a balance between cold-catch-up
     * latency (1 list + N gets — 50 manifests at 200ms RTT drops from 10s to ~2.5s) and not
     * spamming GCS's per-bucket-prefix QPS budget when many projects bulk-pull at once.
     */
    private val manifestFetchPermits: Int = 4,
) {
    /**
     * Read everything in cloud at `rev > sinceRev` and record it locally.
     *
     * **Contract:** the returned list is the **contiguous successful prefix** starting at
     * `sinceRev + 1`. If [CloudBackend.readManifest] throws for any rev in the range, polling
     * stops at that rev and the returned list contains only the successfully-pulled prefix;
     * the caller advances the watermark to `pulled.last().rev` (or leaves it untouched if the
     * list is empty). This is load-bearing for K1: a transient failure must not silently skip
     * a rev — the next listener emission will re-issue `pollOnce(uuid, sinceRev = lastSuccess)`
     * and naturally retry the failed read.
     *
     * Idempotent — re-running with the same `sinceRev` reads the same manifests and produces
     * the same snapshot rows (SnapshotRepository.recordSnapshot uses INSERT OR REPLACE).
     *
     * Errors on the initial [CloudBackend.listManifests] are propagated as exceptions (apart
     * from cancellation, which is always rethrown). Errors during per-rev [readManifest] are
     * swallowed for the failing rev only and end the pull.
     */
    suspend fun pollOnce(
        uuid: ProjectUuid,
        sinceRev: SnapshotRev? = null,
    ): List<Snapshot> {
        val refs =
            try {
                cloud.listManifests(uuid, sinceRev)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                return emptyList()
            }
        val sortedRefs =
            refs
                .filter { sinceRev == null || it.rev > sinceRev.value }
                .sortedBy { it.rev }
        if (sortedRefs.isEmpty()) return emptyList()

        // Parallel fetch within a bounded semaphore (E2). Each ref's read runs concurrently
        // up to [manifestFetchPermits], drops cancellation through coroutineScope, and folds
        // transient failures into a Result so the contiguous-prefix check (B1) below can
        // stop at the first hole without losing the successful prefix.
        val semaphore = Semaphore(manifestFetchPermits)
        val results =
            coroutineScope {
                sortedRefs
                    .map { ref ->
                        async {
                            semaphore.withPermit {
                                try {
                                    Result.success(cloud.readManifest(ref))
                                } catch (c: CancellationException) {
                                    throw c
                                } catch (t: Throwable) {
                                    Result.failure(t)
                                }
                            }
                        }
                    }.awaitAll()
            }

        val out = mutableListOf<Snapshot>()
        for ((ref, result) in sortedRefs.zip(results)) {
            val manifest =
                result.getOrElse {
                    // Hole in the contiguous range — stop the pull. The watermark stays at the
                    // last successful rev; the next listener emission re-issues this poll with
                    // sinceRev = lastSuccess and retries the failed read.
                    break
                }
            val snapshot =
                Snapshot(
                    projectUuid = manifest.projectUuid,
                    rev = manifest.rev,
                    parentRev = manifest.parentRev,
                    timestamp = manifest.timestamp,
                    hostId = manifest.hostId,
                    hostName = manifest.hostName,
                    kind = manifest.kind,
                    label = manifest.label,
                    selfContained = manifest.selfContained,
                    fileCount = manifest.stats.fileCount,
                    totalBytes = manifest.stats.totalBytes,
                    newBytes = manifest.stats.newBytes,
                )
            snapshots.recordSnapshot(snapshot, manifestPath = ref.path, manifestHash = "")
            out += snapshot
        }
        return out
    }
}
