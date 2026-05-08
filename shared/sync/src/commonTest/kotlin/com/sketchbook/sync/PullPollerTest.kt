package com.sketchbook.sync

import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.repo.SnapshotRepository
import com.sketchbook.repo.TreeJournal
import com.sketchbook.repo.TreeJournalEntry
import com.sketchbook.repo.TreeJournalEvent
import com.sketchbook.repo.TreeSnapshotRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class PullPollerTest {
    private val uuid = ProjectUuid("01H-pull-test")
    private val now = Instant.parse("2026-05-05T12:00:00Z")

    private class RecordingSnapshotRepository : SnapshotRepository {
        val recorded = mutableListOf<Snapshot>()

        override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> = MutableStateFlow(emptyList())

        override suspend fun recordSnapshot(
            snapshot: Snapshot,
            manifestPath: String,
            manifestHash: String,
        ): Result<Unit> {
            recorded += snapshot
            return Result.success(Unit)
        }

        override suspend fun setSnapshotLabel(
            uuid: ProjectUuid,
            rev: SnapshotRev,
            label: String?,
        ): Result<com.sketchbook.repo.JournalEntry> =
            Result.success(
                com.sketchbook.repo.JournalEntry(
                    timestamp = Instant.fromEpochMilliseconds(0L),
                    projectId = com.sketchbook.core.ProjectId(1L),
                    action =
                        com.sketchbook.repo.ActionRecord
                            .SnapshotRelabeled(rev.value, null, label, "auto"),
                ),
            )

        override suspend fun materializeAt(
            uuid: ProjectUuid,
            rev: SnapshotRev,
        ): Result<Unit> = Result.success(Unit)
    }

    private fun manifest(
        rev: Long,
        kind: SnapshotKind = SnapshotKind.Auto,
        projectUuid: ProjectUuid = uuid,
    ): Manifest =
        Manifest(
            treeId = TrackedTreeId(projectUuid.value),
            kind = TrackedTreeKind.Project,
            rev = SnapshotRev(rev),
            parentRev = if (rev > 1) SnapshotRev(rev - 1) else null,
            timestamp = now,
            hostId = "host-a",
            hostName = "DesktopA",
            snapshotKind = kind,
            files = emptyMap(),
            stats = ManifestStats(0, 0, 0),
        )

    /**
     * Records both writes the production [com.sketchbook.repo.impl.SqlTreeJournal] makes
     * (snapshot row + journal event) so the test can assert the pull poller routes
     * non-project kinds to the parallel `tree_*` tables.
     */
    private class RecordingTreeJournal : TreeJournal {
        val recordedSnapshots = mutableListOf<Triple<TrackedTreeId, TrackedTreeKind, Manifest>>()
        val recordedEvents = mutableListOf<TreeJournalEntry>()

        override suspend fun recordSnapshot(
            manifest: Manifest,
            treeId: TrackedTreeId,
            kind: TrackedTreeKind,
            manifestPath: String,
        ): TreeJournalEntry {
            recordedSnapshots += Triple(treeId, kind, manifest)
            return TreeJournalEntry(
                treeId = treeId,
                kind = kind,
                timestamp = manifest.timestamp,
                hostId = manifest.hostId,
                event =
                    TreeJournalEvent.Snapshot(
                        rev = manifest.rev.value,
                        parentRev = manifest.parentRev?.value,
                        fileCount = manifest.stats.fileCount,
                        totalBytes = manifest.stats.totalBytes,
                        newBytes = manifest.stats.newBytes,
                        manifestPath = manifestPath,
                    ),
                rev = manifest.rev,
                sequence = (recordedSnapshots.size).toLong(),
            )
        }

        override suspend fun appendEvent(entry: TreeJournalEntry): TreeJournalEntry {
            recordedEvents += entry
            return entry.copy(sequence = recordedEvents.size.toLong())
        }

        override fun observeRecent(
            treeId: TrackedTreeId,
            limit: Int,
        ): Flow<List<TreeJournalEntry>> = MutableStateFlow(emptyList())

        override fun observeSnapshots(treeId: TrackedTreeId): Flow<List<TreeSnapshotRow>> = MutableStateFlow(emptyList())
    }

    @Test
    fun emitsExistingManifestsOnFirstPoll() =
        runTest {
            val cloud = FakeCloudBackend()
            cloud.seedManifest(uuid, manifest(1))
            cloud.seedManifest(uuid, manifest(2))
            val repo = RecordingSnapshotRepository()
            val poller = PullPoller(cloud, repo, pollInterval = 100.milliseconds)

            val first = poller.subscribe(uuid).take(2).toList()

            assertEquals(listOf(SnapshotRev(1), SnapshotRev(2)), first.map { it.rev })
            assertEquals(2, repo.recorded.size)
        }

    @Test
    fun startAfterSkipsKnownRevs() =
        runTest {
            val cloud = FakeCloudBackend()
            cloud.seedManifest(uuid, manifest(1))
            cloud.seedManifest(uuid, manifest(2))
            cloud.seedManifest(uuid, manifest(3))
            val repo = RecordingSnapshotRepository()
            val poller = PullPoller(cloud, repo, pollInterval = 100.milliseconds)

            val first = poller.subscribe(uuid, startAfter = SnapshotRev(2)).first()

            assertEquals(SnapshotRev(3), first.rev)
            assertEquals(1, repo.recorded.size)
        }

    @Test
    fun nonProjectKindRoutesToTreeJournalNotSnapshotsTable() =
        runTest {
            val cloud = FakeCloudBackend()
            val treeId = TrackedTreeId("tt-UL-01")
            val kind = TrackedTreeKind.UserLibrary
            cloud.seedTreeManifest(treeId, kind, manifest(rev = 1, projectUuid = ProjectUuid(treeId.value)))
            cloud.seedTreeManifest(treeId, kind, manifest(rev = 2, projectUuid = ProjectUuid(treeId.value)))

            val snapshotRepo = RecordingSnapshotRepository()
            val tree = RecordingTreeJournal()
            val poller =
                PullPoller(
                    cloud = cloud,
                    snapshots = snapshotRepo,
                    treeJournal = tree,
                    pollInterval = 100.milliseconds,
                )

            val emitted = poller.subscribe(treeId, kind).take(2).toList()

            assertEquals(listOf(SnapshotRev(1), SnapshotRev(2)), emitted.map { it.rev })
            // Non-project kinds skip the legacy snapshots table entirely.
            assertEquals(0, snapshotRepo.recorded.size)
            assertEquals(2, tree.recordedSnapshots.size)
            assertTrue(tree.recordedSnapshots.all { it.first == treeId && it.second == kind })
        }

    @Test
    fun nonProjectKindWithoutTreeJournalThrows() =
        runTest {
            val cloud = FakeCloudBackend()
            val treeId = TrackedTreeId("tt-UL-02")
            val kind = TrackedTreeKind.UserLibrary
            cloud.seedTreeManifest(treeId, kind, manifest(rev = 1, projectUuid = ProjectUuid(treeId.value)))

            val poller = PullPoller(cloud, RecordingSnapshotRepository(), pollInterval = 100.milliseconds)
            assertFails {
                poller.subscribe(treeId, kind).first()
            }
        }
}
