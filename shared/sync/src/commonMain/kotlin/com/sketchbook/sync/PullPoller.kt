package com.sketchbook.sync

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Polls cloud HEAD per project and persists newly-seen manifests as snapshot rows.
 *
 * Subscribes are lightweight — just fetch HEAD + (optionally) the new manifests since last seen.
 * Materialization is on-demand (PR-22).
 */
class PullPoller(
    private val cloud: CloudBackend,
    private val snapshots: SnapshotRepository,
    private val pollInterval: Duration = 30.seconds,
) {
    /**
     * Long-running flow that emits each new [Snapshot] as it lands. Cancelling the collector
     * stops polling.
     */
    fun subscribe(
        uuid: ProjectUuid,
        startAfter: SnapshotRev? = null,
    ): Flow<Snapshot> =
        flow {
            var sinceRev: SnapshotRev? = startAfter
            while (true) {
                val refs = runCatching { cloud.listManifests(uuid, sinceRev) }.getOrElse { emptyList() }
                val sorted = refs.sortedBy { it.rev }
                for (ref in sorted) {
                    val rev = SnapshotRev(ref.rev)
                    val current = sinceRev
                    if (current != null && rev <= current) continue
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
                    sinceRev = rev
                    emit(snapshot)
                }
                delay(pollInterval)
            }
        }
}
