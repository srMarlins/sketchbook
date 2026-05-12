package com.sketchbook.featuretimeline

import app.cash.turbine.test
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUpMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private val uuid = ProjectUuid("01H-tl")

    private fun snap(
        rev: Long,
        kind: SnapshotKind,
        ts: String,
        label: String? = null,
    ) = Snapshot(
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

    private class Repo(
        val flow: MutableStateFlow<List<Snapshot>>,
    ) : SnapshotRepository {
        var rewoundTo: SnapshotRev? = null
        var failNext: Boolean = false
        var lastRelabel: Triple<ProjectUuid, SnapshotRev, String?>? = null

        override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> = flow

        override suspend fun recordSnapshot(
            snapshot: Snapshot,
            manifestPath: String,
            manifestHash: String,
        ) = Unit

        override suspend fun setSnapshotLabel(
            uuid: ProjectUuid,
            rev: SnapshotRev,
            label: String?,
        ): com.sketchbook.repo.JournalEntry {
            lastRelabel = Triple(uuid, rev, label)
            return com.sketchbook.repo.JournalEntry(
                timestamp = kotlin.time.Instant.fromEpochMilliseconds(0L),
                projectId = com.sketchbook.core.ProjectId(1L),
                action =
                    com.sketchbook.repo.ActionRecord.SnapshotRelabeled(
                        rev = rev.value,
                        labelBefore = null,
                        labelAfter = label,
                        kindBefore = "auto",
                    ),
            )
        }

        override suspend fun materializeAt(
            uuid: ProjectUuid,
            rev: SnapshotRev,
        ): com.sketchbook.repo.MaterializeOutcome =
            if (failNext) {
                failNext = false
                throw IllegalStateException("disk full")
            } else {
                rewoundTo = rev
                com.sketchbook.repo.MaterializeOutcome.Materialized
            }
    }

    @Test
    fun defaultViewHidesAutosAndShowsNamedBranchNewestFirst() =
        runTest(mainDispatcher) {
            val history =
                listOf(
                    snap(1, SnapshotKind.Auto, "2026-05-04T10:00:00Z"),
                    snap(2, SnapshotKind.Named, "2026-05-04T11:00:00Z", label = "checkpoint"),
                    snap(3, SnapshotKind.Auto, "2026-05-05T09:00:00Z"),
                    snap(4, SnapshotKind.Branch, "2026-05-05T09:30:00Z", label = "auto-fork"),
                )
            val flow = MutableStateFlow(history)
            val vm = TimelineViewModel(Repo(flow), zone = TimeZone.UTC)
            vm.load(uuid)
            vm.state.test {
                while (awaitItem().history.isEmpty()) { /* drain */ }
                cancelAndIgnoreRemainingEvents()
            }
            val groups = vm.visibleGroups()
            assertEquals(2, groups.size)
            assertEquals(2026, groups[0].date.year)
            assertEquals(5, groups[0].date.day)
            assertEquals(listOf(SnapshotRev(4)), groups[0].snapshots.map { it.rev })
            assertEquals(listOf(SnapshotRev(2)), groups[1].snapshots.map { it.rev })
        }

    @Test
    fun toggleShowAllRevealsAutos() =
        runTest(mainDispatcher) {
            val history =
                listOf(
                    snap(1, SnapshotKind.Auto, "2026-05-05T10:00:00Z"),
                    snap(2, SnapshotKind.Named, "2026-05-05T11:00:00Z", label = "x"),
                )
            val vm = TimelineViewModel(Repo(MutableStateFlow(history)), zone = TimeZone.UTC)
            vm.load(uuid)
            vm.state.test {
                while (awaitItem().history.isEmpty()) { /* drain */ }
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(
                1,
                vm
                    .visibleGroups()
                    .single()
                    .snapshots.size,
            )
            vm.dispatch(TimelineViewModel.Intent.ToggleShowAll)
            vm.state.test {
                while (!awaitItem().showAll) { /* drain */ }
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(
                2,
                vm
                    .visibleGroups()
                    .single()
                    .snapshots.size,
            )
        }

    @Test
    fun confirmRewindCallsMaterializeAndEmitsCompletion() =
        runTest(mainDispatcher) {
            val repo = Repo(MutableStateFlow(emptyList()))
            val vm = TimelineViewModel(repo)
            vm.load(uuid)
            vm.effects.test {
                vm.dispatch(TimelineViewModel.Intent.ConfirmRewind(SnapshotRev(5)))
                val started = awaitItem()
                assertTrue(started is TimelineViewModel.Effect.RewindStarted)
                val done = awaitItem()
                assertTrue(done is TimelineViewModel.Effect.RewindCompleted)
                assertEquals(SnapshotRev(5), repo.rewoundTo)
            }
        }

    @Test
    fun relabelSnapshotForwardsToRepositoryWithCleanedLabel() =
        runTest(mainDispatcher) {
            val repo = Repo(MutableStateFlow(emptyList()))
            val vm = TimelineViewModel(repo)
            vm.load(uuid)

            vm.dispatch(TimelineViewModel.Intent.RelabelSnapshot(SnapshotRev(7), "  demo  "))
            // viewModelScope launches on Main; drain so the coroutine reaches setSnapshotLabel.
            testScheduler.advanceUntilIdle()
            val (recordedUuid, recordedRev, recordedLabel) = assertNotNull(repo.lastRelabel)
            assertEquals(uuid, recordedUuid)
            assertEquals(SnapshotRev(7), recordedRev)
            assertEquals("demo", recordedLabel) // trimmed
        }

    @Test
    fun relabelSnapshotWithBlankPassesNullToClear() =
        runTest(mainDispatcher) {
            val repo = Repo(MutableStateFlow(emptyList()))
            val vm = TimelineViewModel(repo)
            vm.load(uuid)

            vm.dispatch(TimelineViewModel.Intent.RelabelSnapshot(SnapshotRev(2), "   "))
            testScheduler.advanceUntilIdle()
            val (_, _, recordedLabel) = assertNotNull(repo.lastRelabel)
            kotlin.test.assertNull(recordedLabel)
        }

    @Test
    fun rewindFailureEmitsRewindFailed() =
        runTest(mainDispatcher) {
            val repo = Repo(MutableStateFlow(emptyList())).also { it.failNext = true }
            val vm = TimelineViewModel(repo)
            vm.load(uuid)
            vm.effects.test {
                vm.dispatch(TimelineViewModel.Intent.ConfirmRewind(SnapshotRev(2)))
                awaitItem() // RewindStarted
                val failed = awaitItem()
                assertTrue(failed is TimelineViewModel.Effect.RewindFailed)
                assertEquals("disk full", failed.reason)
            }
        }
}
