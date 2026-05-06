package com.sketchbook.featurejournal

import app.cash.turbine.test
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.impl.InMemoryJournalRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class JournalStateHolderTest {

    @Test
    fun observesAppendedEntries() = runTest {
        val repo = InMemoryJournalRepository()
        val holder = JournalStateHolder(repo, backgroundScope)

        repo.append(
            JournalEntry(
                timestamp = Instant.parse("2026-05-06T12:00:00Z"),
                projectId = ProjectId(7),
                action = ActionRecord.Archive(wasArchived = false, isArchived = true),
            ),
        )

        holder.state.test {
            var s = awaitItem()
            while (s.entries.isEmpty()) s = awaitItem()
            assertEquals(1, s.entries.size)
            assertEquals(ProjectId(7), s.entries[0].projectId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun openProjectIntentEmitsNavigateEffect() = runTest {
        val repo = InMemoryJournalRepository()
        val holder = JournalStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(JournalStateHolder.Intent.OpenProject(ProjectId(42)))
            val effect = awaitItem()
            assertTrue(effect is JournalStateHolder.Effect.NavigateToProject)
            assertEquals(ProjectId(42), effect.projectId)
        }
    }
}
