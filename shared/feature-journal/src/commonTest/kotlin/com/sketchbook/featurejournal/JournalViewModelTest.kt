package com.sketchbook.featurejournal

import app.cash.turbine.test
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.impl.InMemoryJournalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @BeforeTest fun setUpMain() { Dispatchers.setMain(mainDispatcher) }
    @AfterTest fun tearDownMain() { Dispatchers.resetMain() }

    @Test
    fun observesAppendedEntries() = runTest(mainDispatcher) {
        val repo = InMemoryJournalRepository()
        val vm = JournalViewModel(repo)

        repo.append(
            JournalEntry(
                timestamp = Instant.parse("2026-05-06T12:00:00Z"),
                projectId = ProjectId(7),
                action = ActionRecord.Archive(wasArchived = false, isArchived = true),
            ),
        )

        vm.state.test {
            var s = awaitItem()
            while (s.entries.isEmpty()) s = awaitItem()
            assertEquals(1, s.entries.size)
            assertEquals(ProjectId(7), s.entries[0].projectId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun openProjectIntentEmitsNavigateEffect() = runTest(mainDispatcher) {
        val repo = InMemoryJournalRepository()
        val vm = JournalViewModel(repo)
        vm.effects.test {
            vm.dispatch(JournalViewModel.Intent.OpenProject(ProjectId(42)))
            val effect = awaitItem()
            assertTrue(effect is JournalViewModel.Effect.NavigateToProject)
            assertEquals(ProjectId(42), effect.projectId)
        }
    }
}
