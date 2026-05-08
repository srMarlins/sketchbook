package com.sketchbook.sync

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.core.projectUuid
import com.sketchbook.repo.SnapshotRepository
import com.sketchbook.repo.TreeJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Polls cloud HEAD per tree and persists newly-seen manifests as history rows.
 *
 * - For [TrackedTreeKind.Project], writes go to the legacy `snapshots` table via
 *   [SnapshotRepository.recordSnapshot] — that's the table the existing project timeline UI
 *   and `SyncStateStore` already read from.
 * - For non-project kinds (UserLibrary etc., commit 8 onward), writes go through
 *   [TreeJournal.recordSnapshot] which records both the `tree_snapshots` row + a
 *   `tree_journal` `snapshot` event in one transaction.
 *
 * Subscriptions are lightweight — fetch HEAD + (optionally) the new manifests since last
 * seen. Materialization is on-demand (PR-22).
 */
class PullPoller(
    private val cloud: CloudBackend,
    private val snapshots: SnapshotRepository,
    private val treeJournal: TreeJournal? = null,
    private val pollInterval: Duration = 30.seconds,
) {
    /**
     * Long-running flow that emits each new [Snapshot] as it lands. Cancelling the collector
     * stops polling.
     */
    fun subscribe(
        uuid: ProjectUuid,
        startAfter: SnapshotRev? = null,
    ): Flow<Snapshot> = subscribe(treeId = TrackedTreeId(uuid.value), kind = TrackedTreeKind.Project, startAfter = startAfter)

    /**
     * Tree-keyed subscription. Project callers route through the [ProjectUuid] overload above
     * so existing call sites stay terse; non-project kinds (UserLibrary in commit 8) call this
     * form directly. For non-project kinds [treeJournal] must be provided at construction —
     * the legacy `snapshots` table is project-only.
     *
     * Wire format is still v=1 — the manifest's `project_uuid` field is present for
     * [TrackedTreeKind.Project] and synthesized to the tree-id for non-project kinds.
     */
    fun subscribe(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        startAfter: SnapshotRev? = null,
    ): Flow<Snapshot> = pollFlow(treeId, kind, startAfter)

    private fun pollFlow(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        startAfter: SnapshotRev?,
    ): Flow<Snapshot> =
        flow {
            var sinceRev: SnapshotRev? = startAfter
            while (true) {
                // try/catch (CancellationException) instead of runCatching: poll lambdas execute
                // suspend cloud calls, and runCatching would silently catch coroutine cancellation
                // and break structured concurrency. See CLAUDE.md "runCatching at suspend boundaries".
                val refs =
                    try {
                        cloud.listManifests(treeId, kind, sinceRev)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        emptyList()
                    }
                val sorted = refs.sortedBy { it.rev }
                for (ref in sorted) {
                    val rev = SnapshotRev(ref.rev)
                    val current = sinceRev
                    if (current != null && rev <= current) continue
                    val manifest =
                        try {
                            cloud.readManifest(treeId, kind, rev)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            continue
                        }
                    // Project-only snapshot row goes to the legacy `snapshots` table; the
                    // `projectUuid` extension throws on non-project kinds, so we synthesize a
                    // ProjectUuid for the sync.Snapshot model only when emitting Project rows.
                    val rowProjectUuid =
                        if (kind == TrackedTreeKind.Project) {
                            manifest.projectUuid
                        } else {
                            ProjectUuid(treeId.value)
                        }
                    val snapshot =
                        Snapshot(
                            projectUuid = rowProjectUuid,
                            rev = manifest.rev,
                            parentRev = manifest.parentRev,
                            timestamp = manifest.timestamp,
                            hostId = manifest.hostId,
                            hostName = manifest.hostName,
                            kind = manifest.snapshotKind,
                            label = manifest.label,
                            selfContained = manifest.selfContained,
                            fileCount = manifest.stats.fileCount,
                            totalBytes = manifest.stats.totalBytes,
                            newBytes = manifest.stats.newBytes,
                        )
                    if (kind == TrackedTreeKind.Project) {
                        snapshots.recordSnapshot(snapshot, manifestPath = ref.path, manifestHash = "")
                    } else {
                        // Non-project kinds: route to the parallel `tree_*` tables. A null
                        // [treeJournal] here means a caller subscribed for a non-project kind
                        // without wiring the dependency — fail loudly so the misconfiguration
                        // surfaces in tests rather than silently dropping history rows.
                        val journal =
                            treeJournal
                                ?: error("PullPoller subscribed for non-project kind ${kind.wireName} without a TreeJournal")
                        journal.recordSnapshot(manifest, treeId, kind, manifestPath = ref.path)
                    }
                    sinceRev = rev
                    emit(snapshot)
                }
                delay(pollInterval)
            }
        }
}
