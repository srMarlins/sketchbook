package com.sketchbook.sync

import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class PullPollerTest {

    private val uuid = ProjectUuid("01H-pull-test")
    private val now = Instant.parse("2026-05-05T12:00:00Z")

    private class RecordingSnapshotRepository : SnapshotRepository {
        val recorded = mutableListOf<Snapshot>()
        override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> = MutableStateFlow(emptyList())
        override suspend fun recordSnapshot(snapshot: Snapshot, manifestPath: String, manifestHash: String): Result<Unit> {
            recorded += snapshot
            return Result.success(Unit)
        }
        override suspend fun setSnapshotLabel(uuid: ProjectUuid, rev: SnapshotRev, label: String?): Result<com.sketchbook.repo.JournalEntry> = Result.success(
            com.sketchbook.repo.JournalEntry(
                timestamp = Instant.fromEpochMilliseconds(0L),
                projectId = com.sketchbook.core.ProjectId(1L),
                action = com.sketchbook.repo.ActionRecord.SnapshotRelabeled(rev.value, null, label, "auto"),
            ),
        )
        override suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> = Result.success(Unit)
    }

    private fun manifest(rev: Long, kind: SnapshotKind = SnapshotKind.Auto): Manifest = Manifest(
        projectUuid = uuid,
        rev = SnapshotRev(rev),
        parentRev = if (rev > 1) SnapshotRev(rev - 1) else null,
        timestamp = now,
        hostId = "host-a",
        hostName = "DesktopA",
        kind = kind,
        files = emptyMap(),
        stats = ManifestStats(0, 0, 0),
    )

    @Test
    fun emitsExistingManifestsOnFirstPoll() = runTest {
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
    fun startAfterSkipsKnownRevs() = runTest {
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
}
