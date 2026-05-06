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
    private fun row(
        id: Long,
        name: String,
        tags: List<String> = emptyList(),
        archived: Boolean = false,
        tempo: Double? = 124.0,
        key: String? = null,
        stageInferred: com.sketchbook.core.Stage? = null,
        stageOverride: com.sketchbook.core.Stage? = null,
    ) = ProjectRow(
        id = ProjectId(id),
        name = name,
        path = ProjectPath("Projects/2026/$name/Project.als"),
        tempo = tempo,
        trackCount = 8,
        lastSavedLiveVersion = "11.3.20",
        updatedAt = now,
        tags = tags,
        colorTag = null,
        archived = archived,
        key = key,
        stageInferred = stageInferred,
        stageOverride = stageOverride,
    )

    private class FakeRepo(
        private val byQuery: Map<String, MutableStateFlow<List<ProjectRow>>> = emptyMap(),
        private val archived: MutableStateFlow<List<ProjectRow>> = MutableStateFlow(emptyList()),
    ) : ProjectRepository {
        override fun observeProjects(query: String): Flow<List<ProjectRow>> =
            byQuery[query] ?: byQuery[""] ?: flowOf(emptyList())
        override fun observeArchivedProjects(): Flow<List<ProjectRow>> = archived
        override fun observeProject(id: ProjectId): Flow<ProjectRow?> = flowOf(null)
        override suspend fun move(id: ProjectId, newParentDir: String): Result<JournalEntry> = stub()
        override suspend fun rename(id: ProjectId, newName: String): Result<JournalEntry> = stub()
        override suspend fun archive(id: ProjectId, archived: Boolean): Result<JournalEntry> = stub()
        override suspend fun setTags(id: ProjectId, tags: List<String>): Result<JournalEntry> = stub()
        override suspend fun setStageOverride(
            id: ProjectId,
            stage: com.sketchbook.core.Stage?,
        ): Result<JournalEntry> = stub()
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
    fun archivedRowsExposedSeparatelyFromActiveRows() = runTest {
        val active = MutableStateFlow(listOf(row(1, "kick")))
        val archived = MutableStateFlow(listOf(row(2, "old-snare", archived = true)))
        val repo = FakeRepo(mapOf("" to active), archived)
        val holder = ProjectListStateHolder(repo, backgroundScope)

        holder.state.test {
            var s = awaitItem()
            while (s.rows.isEmpty() || s.archivedRows.isEmpty()) s = awaitItem()
            assertEquals(listOf("kick"), s.rows.map { it.name })
            assertEquals(listOf("old-snare"), s.archivedRows.map { it.name })
            assertTrue(s.rows.none { it.archived })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun tempoAndKeyFiltersNarrowTheRowSet() = runTest {
        val all = MutableStateFlow(
            listOf(
                row(1, "a", tempo = 140.0, key = "F# Minor"),
                row(2, "b", tempo = 128.0, key = "F# Minor"),
                row(3, "c", tempo = 140.0, key = "C Major"),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val holder = ProjectListStateHolder(repo, backgroundScope)

        holder.state.test {
            // Drain until rows populate.
            var s = awaitItem()
            while (s.rows.size < 3) s = awaitItem()

            holder.dispatch(ProjectListStateHolder.Intent.SetTempoRange(135.0..145.0))
            holder.dispatch(ProjectListStateHolder.Intent.SetKeyFilter("F# Minor"))

            s = awaitItem()
            while (s.tempoRange == null || s.keyFilter != "F# Minor" || s.rows.size != 1) {
                s = awaitItem()
            }
            assertEquals(listOf("a"), s.rows.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stageFilterNarrowsToSelectedStages() = runTest {
        val all = MutableStateFlow(
            listOf(
                row(1, "sketchy", stageInferred = com.sketchbook.core.Stage.Sketch),
                row(2, "stuck-old", stageInferred = com.sketchbook.core.Stage.Stuck),
                row(3, "no-chip", stageInferred = null),
                row(4, "active", stageInferred = com.sketchbook.core.Stage.InProgress),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val holder = ProjectListStateHolder(repo, backgroundScope)

        holder.state.test {
            var s = awaitItem()
            while (s.rows.size < 4) s = awaitItem()

            // Filter to just Stuck.
            holder.dispatch(
                ProjectListStateHolder.Intent.SetStageFilter(setOf(com.sketchbook.core.Stage.Stuck))
            )
            while (s.stageFilter.isEmpty() || s.rows.size != 1) s = awaitItem()
            assertEquals(listOf("stuck-old"), s.rows.map { it.name })

            // Toggle Sketch in — multi-select.
            holder.dispatch(
                ProjectListStateHolder.Intent.ToggleStageFilter(com.sketchbook.core.Stage.Sketch)
            )
            while (s.stageFilter.size != 2 || s.rows.size != 2) s = awaitItem()
            assertEquals(setOf("stuck-old", "sketchy"), s.rows.map { it.name }.toSet())

            // Toggle Stuck off; only Sketch remains.
            holder.dispatch(
                ProjectListStateHolder.Intent.ToggleStageFilter(com.sketchbook.core.Stage.Stuck)
            )
            while (s.stageFilter != setOf(com.sketchbook.core.Stage.Sketch) || s.rows.size != 1) s = awaitItem()
            assertEquals(listOf("sketchy"), s.rows.map { it.name })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stageFilterUsesEffectiveStageWithOverrideWinning() = runTest {
        // Row 1: inferred=Sketch, override=Stuck → effective stage is Stuck. Filter on Stuck
        // must include row 1 even though the inferred stage doesn't match.
        val all = MutableStateFlow(
            listOf(
                row(
                    1, "pinned",
                    stageInferred = com.sketchbook.core.Stage.Sketch,
                    stageOverride = com.sketchbook.core.Stage.Stuck,
                ),
                row(2, "really-stuck", stageInferred = com.sketchbook.core.Stage.Stuck),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val holder = ProjectListStateHolder(repo, backgroundScope)

        holder.state.test {
            var s = awaitItem()
            while (s.rows.size < 2) s = awaitItem()

            holder.dispatch(
                ProjectListStateHolder.Intent.SetStageFilter(setOf(com.sketchbook.core.Stage.Stuck))
            )
            while (s.stageFilter.isEmpty() || s.rows.size != 2) s = awaitItem()
            assertEquals(setOf("pinned", "really-stuck"), s.rows.map { it.name }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearFiltersRestoresFullRowSet() = runTest {
        val all = MutableStateFlow(
            listOf(
                row(1, "a", tempo = 140.0, key = "F# Minor"),
                row(2, "b", tempo = 128.0, key = "F# Minor"),
                row(3, "c", tempo = 140.0, key = "C Major"),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val holder = ProjectListStateHolder(repo, backgroundScope)

        holder.state.test {
            var s = awaitItem()
            while (s.rows.size < 3) s = awaitItem()

            holder.dispatch(ProjectListStateHolder.Intent.SetKeyFilter("F# Minor"))
            while (s.keyFilter != "F# Minor" || s.rows.size != 2) s = awaitItem()
            assertEquals(setOf("a", "b"), s.rows.map { it.name }.toSet())

            holder.dispatch(ProjectListStateHolder.Intent.ClearFilters)
            while (s.keyFilter != null || s.rows.size != 3) s = awaitItem()
            assertEquals(setOf("a", "b", "c"), s.rows.map { it.name }.toSet())
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
