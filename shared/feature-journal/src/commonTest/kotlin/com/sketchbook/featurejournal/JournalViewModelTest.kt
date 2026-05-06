package com.sketchbook.featurejournal

import app.cash.turbine.test
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.RepairFindings
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.impl.InMemoryJournalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
class JournalViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUpMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest fun tearDownMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun observesAppendedEntries() = runTest(mainDispatcher) {
        val repo = InMemoryJournalRepository()
        val vm = JournalViewModel(repo, EmptyProjects, EmptyRepair)

        repo.append(
            JournalEntry(
                timestamp = Instant.parse("2026-05-06T12:00:00Z"),
                projectId = ProjectId(7),
                action = ActionRecord.Archive(wasArchived = false, isArchived = true),
            ),
        )

        vm.state.test {
            var s = awaitItem()
            while (s.rows.isEmpty()) s = awaitItem()
            assertEquals(1, s.rows.size)
            assertEquals(ProjectId(7), s.rows[0].entry.projectId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun openProjectIntentEmitsNavigateEffect() = runTest(mainDispatcher) {
        val repo = InMemoryJournalRepository()
        val vm = JournalViewModel(repo, EmptyProjects, EmptyRepair)
        vm.effects.test {
            vm.dispatch(JournalViewModel.Intent.OpenProject(ProjectId(42)))
            val effect = awaitItem()
            assertTrue(effect is JournalViewModel.Effect.NavigateToProject)
            assertEquals(ProjectId(42), effect.projectId)
        }
    }

    /** Stub used by these tests — they only exercise observe + intent dispatch, not undoOne, so
     *  the mutator branches can throw if anyone wires them in unexpectedly. */
    private object EmptyProjects : ProjectRepository {
        override fun observeProjects(query: String): Flow<List<ProjectRow>> = flowOf(emptyList())
        override fun observeProject(id: ProjectId): Flow<ProjectRow?> = flowOf(null)
        override suspend fun move(id: ProjectId, newParentDir: String) = error("not used")
        override suspend fun rename(id: ProjectId, newName: String) = error("not used")
        override suspend fun archive(id: ProjectId, archived: Boolean) = error("not used")
        override suspend fun setTags(id: ProjectId, tags: List<String>) = error("not used")
    }

    private object EmptyRepair : RepairRepository {
        override fun observeFindings(projectId: ProjectId?, limit: Int): Flow<RepairFindings> = flowOf(RepairFindings(emptyList(), emptyList(), 0, false))
        override suspend fun acknowledgeMacImport(projectId: ProjectId) = error("not used")
        override suspend fun applyMacPathRepair(projectId: ProjectId) = error("not used")
        override suspend fun dismissMissingSample(projectId: ProjectId, missingPath: String) = error("not used")
        override suspend fun applyMissingSampleMatch(
            projectId: ProjectId,
            missingPath: String,
            candidatePath: String,
        ) = error("not used")
        override suspend fun restoreMissingSampleMatch(
            projectId: ProjectId,
            missingPath: String,
            candidatePath: String,
        ) = error("not used")
        override suspend fun restoreMacPathRepair(projectId: ProjectId) = error("not used")
    }
}
