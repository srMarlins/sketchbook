package com.sketchbook.featuretimeline

import app.cash.turbine.test
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineStateHolderTest {

    private val uuid = ProjectUuid("01H-tl")

    private fun snap(rev: Long, kind: SnapshotKind, ts: String, label: String? = null) = Snapshot(
        projectUuid = uuid,
        rev = SnapshotRev(rev),
        parentRev = if (rev > 1) SnapshotRev(rev - 1) else null,
        timestamp = Instant.parse(ts),
        hostId = "host-a",
        hostName = "DesktopA",
        kind = kind,
        label = label,
        selfContained = false,
        fileCount = 1,
        totalBytes = 100,
    )

    private class Repo(val flow: MutableStateFlow<List<Snapshot>>) : SnapshotRepository {
        var rewoundTo: SnapshotRev? = null
        var failNext: Boolean = false
        var lastRelabel: Triple<ProjectUuid, SnapshotRev, String?>? = null
        override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> = flow
        override suspend fun recordSnapshot(snapshot: Snapshot, manifestPath: String, manifestHash: String): Result<Unit> = Result.success(Unit)
        override suspend fun setSnapshotLabel(
            uuid: ProjectUuid,
            rev: SnapshotRev,
            label: String?,
        ): Result<com.sketchbook.repo.JournalEntry> {
            lastRelabel = Triple(uuid, rev, label)
            return Result.success(
                com.sketchbook.repo.JournalEntry(
                    timestamp = kotlin.time.Instant.fromEpochMilliseconds(0L),
                    projectId = com.sketchbook.core.ProjectId(0L),
                    action = com.sketchbook.repo.ActionRecord.SnapshotRelabeled(
                        rev = rev.value,
                        labelBefore = null,
                        labelAfter = label,
                        kindBefore = "auto",
                    ),
                ),
            )
        }
        override suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> {
            return if (failNext) {
                failNext = false
                Result.failure(IllegalStateException("disk full"))
            } else {
                rewoundTo = rev
                Result.success(Unit)
            }
        }
    }

    @Test
    fun defaultViewHidesAutosAndShowsNamedBranchNewestFirst() = runTest {
        val history = listOf(
            snap(1, SnapshotKind.Auto, "2026-05-04T10:00:00Z"),
            snap(2, SnapshotKind.Named, "2026-05-04T11:00:00Z", label = "checkpoint"),
            snap(3, SnapshotKind.Auto, "2026-05-05T09:00:00Z"),
            snap(4, SnapshotKind.Branch, "2026-05-05T09:30:00Z", label = "auto-fork"),
        )
        val flow = MutableStateFlow(history)
        val holder = TimelineStateHolder(Repo(flow), backgroundScope, zone = TimeZone.UTC)
        holder.load(uuid)
        holder.state.test {
            while (awaitItem().history.isEmpty()) { /* drain */ }
            cancelAndIgnoreRemainingEvents()
        }
        val groups = holder.visibleGroups()
        assertEquals(2, groups.size)
        assertEquals(2026, groups[0].date.year)
        assertEquals(5, groups[0].date.day)
        // Within the newest day: branch (rev 4) only — auto rev 3 hidden.
        assertEquals(listOf(SnapshotRev(4)), groups[0].snapshots.map { it.rev })
        // Older day: named (rev 2) only — auto rev 1 hidden.
        assertEquals(listOf(SnapshotRev(2)), groups[1].snapshots.map { it.rev })
    }

    @Test
    fun toggleShowAllRevealsAutos() = runTest {
        val history = listOf(
            snap(1, SnapshotKind.Auto, "2026-05-05T10:00:00Z"),
            snap(2, SnapshotKind.Named, "2026-05-05T11:00:00Z", label = "x"),
        )
        val holder = TimelineStateHolder(Repo(MutableStateFlow(history)), backgroundScope, zone = TimeZone.UTC)
        holder.load(uuid)
        holder.state.test {
            while (awaitItem().history.isEmpty()) { /* drain */ }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, holder.visibleGroups().single().snapshots.size)
        holder.dispatch(TimelineStateHolder.Intent.ToggleShowAll)
        // Wait for combine to forward the showAll change.
        holder.state.test {
            while (!awaitItem().showAll) { /* drain */ }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, holder.visibleGroups().single().snapshots.size)
    }

    @Test
    fun confirmRewindCallsMaterializeAndEmitsCompletion() = runTest {
        val repo = Repo(MutableStateFlow(emptyList()))
        val holder = TimelineStateHolder(repo, backgroundScope)
        holder.load(uuid)
        holder.effects.test {
            holder.dispatch(TimelineStateHolder.Intent.ConfirmRewind(SnapshotRev(5)))
            val started = awaitItem()
            assertTrue(started is TimelineStateHolder.Effect.RewindStarted)
            val done = awaitItem()
            assertTrue(done is TimelineStateHolder.Effect.RewindCompleted)
            assertEquals(SnapshotRev(5), repo.rewoundTo)
        }
    }

    @Test
    fun rewindFailureEmitsRewindFailed() = runTest {
        val repo = Repo(MutableStateFlow(emptyList())).also { it.failNext = true }
        val holder = TimelineStateHolder(repo, backgroundScope)
        holder.load(uuid)
        holder.effects.test {
            holder.dispatch(TimelineStateHolder.Intent.ConfirmRewind(SnapshotRev(2)))
            awaitItem() // RewindStarted
            val failed = awaitItem()
            assertTrue(failed is TimelineStateHolder.Effect.RewindFailed)
            assertEquals("disk full", failed.reason)
        }
    }
}
