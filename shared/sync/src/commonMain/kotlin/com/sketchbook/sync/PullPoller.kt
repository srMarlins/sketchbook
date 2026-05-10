package com.sketchbook.sync

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository

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
) {
    /**
     * Read everything in cloud at `rev > sinceRev` and record it locally. Idempotent —
     * re-running with the same `sinceRev` reads the same manifests and produces the same
     * snapshot rows (SnapshotRepository.recordSnapshot uses INSERT OR REPLACE).
     *
     * Errors during list/read are swallowed; the returned list is what we successfully
     * pulled. Callers can compare to the expected range to detect partial-pull cases.
     */
    suspend fun pollOnce(
        uuid: ProjectUuid,
        sinceRev: SnapshotRev? = null,
    ): List<Snapshot> {
        val refs = runCatching { cloud.listManifests(uuid, sinceRev) }.getOrElse { return emptyList() }
        val sorted = refs.sortedBy { it.rev }
        val out = mutableListOf<Snapshot>()
        var cursor: SnapshotRev? = sinceRev
        for (ref in sorted) {
            val rev = SnapshotRev(ref.rev)
            val c = cursor
            if (c != null && rev <= c) continue
            val manifest = runCatching { cloud.readManifest(uuid, rev) }.getOrNull() ?: continue
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
            cursor = rev
        }
        return out
    }
}
