package com.sketchbook.featureprojects

import app.cash.turbine.test
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectListStateHolderTest {

    private val now = Instant.parse("2026-05-05T12:00:00Z")
    private fun row(id: Long, name: String, tags: List<String> = emptyList()) = ProjectRow(
        id = ProjectId(id),
        name = name,
        path = ProjectPath("Projects/2026/$name/Project.als"),
        tempo = 124.0,
        trackCount = 8,
        lastSavedLiveVersion = "11.3.20",
        updatedAt = now,
        tags = tags,
        colorTag = null,
    )

    private class FakeRepo(
        private val byQuery: Map<String, MutableStateFlow<List<ProjectRow>>> = emptyMap(),
    ) : ProjectRepository {
        override fun observeProjects(query: String): Flow<List<ProjectRow>> =
            byQuery[query] ?: byQuery[""] ?: flowOf(emptyList())
        override fun observeProject(id: ProjectId): Flow<ProjectRow?> = flowOf(null)
        override suspend fun move(id: ProjectId, newParentDir: String): Result<JournalEntry> = stub()
        override suspend fun rename(id: ProjectId, newName: String): Result<JournalEntry> = stub()
        override suspend fun archive(id: ProjectId, archived: Boolean): Result<JournalEntry> = stub()
        override suspend fun setTags(id: ProjectId, tags: List<String>): Result<JournalEntry> = stub()
        private fun stub() = Result.success(JournalEntry(
            timestamp = Instant.parse("2026-05-05T12:00:00Z"),
            projectId = ProjectId(1),
            action = ActionRecord.Archive(false, true),
        ))
    }

    @Test
    fun stateUpdatesWhenRepositoryEmits() = runTest {
        val all = MutableStateFlow(listOf(row(1, "kick"), row(2, "snare")))
        val repo = FakeRepo(mapOf("" to all))
        val holder = ProjectListStateHolder(repo, backgroundScope)

        holder.state.test {
            // Drain until rows populate from the repository flow.
            var s = awaitItem()
            while (s.rows.isEmpty()) s = awaitItem()
            assertEquals(listOf("kick", "snare"), s.rows.map { it.name })
            assertEquals(false, s.loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun searchIntentSwitchesObservedQuery() = runTest {
        val all = MutableStateFlow(listOf(row(1, "kick"), row(2, "snare")))
        val matches = MutableStateFlow(listOf(row(1, "kick")))
        val repo = FakeRepo(mapOf("" to all, "kick" to matches))
        val holder = ProjectListStateHolder(repo, backgroundScope)

        holder.state.test {
            // Drain until initial population.
            var s = awaitItem()
            while (s.rows.size < 2) s = awaitItem()
            assertEquals(2, s.rows.size)

            holder.dispatch(ProjectListStateHolder.Intent.Search("kick"))

            // Drain until the new query's matches arrive.
            while (s.query != "kick" || s.rows.size != 1) s = awaitItem()
            assertEquals("kick", s.query)
            assertEquals(listOf("kick"), s.rows.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun openIntentEmitsNavigateEffectExactlyOnce() = runTest {
        val repo = FakeRepo()
        val holder = ProjectListStateHolder(repo, backgroundScope)

        holder.effects.test {
            holder.dispatch(ProjectListStateHolder.Intent.Open(ProjectId(7)))
            val effect = awaitItem()
            assertTrue(effect is ProjectListStateHolder.Effect.Navigate)
            assertEquals(ProjectId(7), effect.id)
            expectNoEvents()
        }
    }
}
