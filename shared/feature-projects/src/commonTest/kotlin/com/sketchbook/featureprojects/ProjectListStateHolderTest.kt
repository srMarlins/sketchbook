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
import kotlinx.datetime.Instant
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
            val initial = awaitItem()
            assertEquals(true, initial.loading) // pre-emission
            val populated = awaitItem()
            assertEquals(listOf("kick", "snare"), populated.rows.map { it.name })
            assertEquals(false, populated.loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun searchIntentSwitchesObservedQuery() = runTest {
        val all = MutableStateFlow(listOf(row(1, "kick"), row(2, "snare")))
        val matches = MutableStateFlow(listOf(row(1, "kick")))
        val repo = FakeRepo(mapOf("" to all, "kick" to matches))
        val holder = ProjectListStateHolder(repo, backgroundScope)

        // Wait for initial population.
        holder.state.test {
            awaitItem() // initial loading=true
            val initial = awaitItem()
            assertEquals(2, initial.rows.size)

            holder.dispatch(ProjectListStateHolder.Intent.Search("kick"))
            // First emission has updated query but rows from old observation may briefly remain.
            val withQuery = awaitItem()
            assertEquals("kick", withQuery.query)

            // Then the new flow lands.
            val refined = awaitItem()
            assertEquals(listOf("kick"), refined.rows.map { it.name })
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
