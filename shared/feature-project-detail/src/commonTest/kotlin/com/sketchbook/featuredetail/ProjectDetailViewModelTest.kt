package com.sketchbook.featuredetail

import app.cash.turbine.test
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUpMain() { Dispatchers.setMain(mainDispatcher) }
    @AfterTest fun tearDownMain() { Dispatchers.resetMain() }

    private val now = Instant.parse("2026-05-05T12:00:00Z")
    private val sampleRow = ProjectRow(
        id = ProjectId(7),
        name = "kick",
        path = ProjectPath("Projects/2026/kick/Project.als"),
        tempo = 124.0,
        trackCount = 8,
        lastSavedLiveVersion = "11.3.20",
        updatedAt = now,
        tags = emptyList(),
        colorTag = null,
    )

    private class FakeProjects(private val row: ProjectRow?) : ProjectRepository {
        var lastMove: Pair<ProjectId, String>? = null
        var lastArchive: Pair<ProjectId, Boolean>? = null
        override fun observeProjects(query: String): Flow<List<ProjectRow>> = flowOf(emptyList())
        override fun observeProject(id: ProjectId): Flow<ProjectRow?> = flowOf(row)
        override suspend fun move(id: ProjectId, newParentDir: String): Result<JournalEntry> {
            lastMove = id to newParentDir
            return Result.success(stub())
        }
        override suspend fun rename(id: ProjectId, newName: String) = Result.success(stub())
        override suspend fun archive(id: ProjectId, archived: Boolean): Result<JournalEntry> {
            lastArchive = id to archived
            return Result.success(stub())
        }
        override suspend fun setTags(id: ProjectId, tags: List<String>) = Result.success(stub())
        private fun stub() = JournalEntry(Instant.parse("2026-05-05T12:00:00Z"), ProjectId(1), ActionRecord.Archive(false, true))
    }

    private class FakeSnapshots(private val flow: MutableStateFlow<List<Snapshot>>) : SnapshotRepository {
        override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> = flow
        override suspend fun recordSnapshot(snapshot: Snapshot, manifestPath: String, manifestHash: String): Result<Unit> = Result.success(Unit)
        override suspend fun setSnapshotLabel(uuid: ProjectUuid, rev: SnapshotRev, label: String?): Result<JournalEntry> =
            Result.success(JournalEntry(Instant.parse("2026-05-05T12:00:00Z"), ProjectId(1), ActionRecord.SnapshotRelabeled(rev.value, null, label, "auto")))
        override suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun loadPopulatesRow() = runTest(mainDispatcher) {
        val projects = FakeProjects(sampleRow)
        val snaps = FakeSnapshots(MutableStateFlow(emptyList()))
        val vm = ProjectDetailViewModel(projects, snaps)

        vm.load(ProjectId(7))

        vm.state.test {
            var saw = awaitItem()
            while (saw.row == null) saw = awaitItem()
            assertEquals("kick", saw.row?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun selectTabUpdatesState() = runTest(mainDispatcher) {
        val projects = FakeProjects(sampleRow)
        val snaps = FakeSnapshots(MutableStateFlow(emptyList()))
        val vm = ProjectDetailViewModel(projects, snaps)
        vm.load(ProjectId(7))
        vm.dispatch(ProjectDetailViewModel.Intent.SelectTab(ProjectDetailViewModel.Tab.History))
        vm.state.test {
            while (awaitItem().tab != ProjectDetailViewModel.Tab.History) { /* keep draining */ }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun toggleArchiveCallsRepositoryWithFlippedFlag() = runTest(UnconfinedTestDispatcher()) {
        val projects = FakeProjects(sampleRow)
        val snaps = FakeSnapshots(MutableStateFlow(emptyList()))
        val vm = ProjectDetailViewModel(projects, snaps)
        vm.load(ProjectId(7))
        advanceUntilIdle()
        assertEquals("kick", vm.state.value.row?.name)
        vm.dispatch(ProjectDetailViewModel.Intent.ToggleArchive)
        advanceUntilIdle()
        assertEquals(ProjectId(7) to true, projects.lastArchive)
    }

    @Test
    fun moveIntentDispatchesRepositoryMove() = runTest(UnconfinedTestDispatcher()) {
        val projects = FakeProjects(sampleRow)
        val snaps = FakeSnapshots(MutableStateFlow(emptyList()))
        val vm = ProjectDetailViewModel(projects, snaps)
        vm.load(ProjectId(7))
        advanceUntilIdle()
        assertEquals("kick", vm.state.value.row?.name)
        vm.dispatch(ProjectDetailViewModel.Intent.Move("/new/parent"))
        advanceUntilIdle()
        assertEquals(ProjectId(7) to "/new/parent", projects.lastMove)
    }

    @Test
    fun openInLiveEmitsLaunchEffectWithProjectPath() = runTest(mainDispatcher) {
        val projects = FakeProjects(sampleRow)
        val snaps = FakeSnapshots(MutableStateFlow(emptyList()))
        val vm = ProjectDetailViewModel(projects, snaps)
        vm.load(ProjectId(7))

        vm.state.test {
            while (awaitItem().row == null) { /* keep draining */ }
            cancelAndIgnoreRemainingEvents()
        }
        vm.effects.test {
            vm.dispatch(ProjectDetailViewModel.Intent.OpenInLive)
            val effect = awaitItem()
            assertTrue(effect is ProjectDetailViewModel.Effect.LaunchLive)
            assertEquals("Projects/2026/kick/Project.als", effect.projectPath)
        }
    }
}
