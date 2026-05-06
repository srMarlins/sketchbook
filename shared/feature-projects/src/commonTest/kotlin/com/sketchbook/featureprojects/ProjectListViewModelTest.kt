package com.sketchbook.featureprojects

import app.cash.turbine.test
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.Stage
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class ProjectListViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUpMain() { Dispatchers.setMain(mainDispatcher) }
    @AfterTest fun tearDownMain() { Dispatchers.resetMain() }

    private val now = Instant.parse("2026-05-05T12:00:00Z")
    private fun row(
        id: Long,
        name: String,
        tags: List<String> = emptyList(),
        archived: Boolean = false,
        tempo: Double? = 124.0,
        key: String? = null,
        stageInferred: Stage? = null,
        stageOverride: Stage? = null,
        missingSampleCount: Int = 0,
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
        missingSampleCount = missingSampleCount,
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
        private fun stub() = Result.success(JournalEntry(
            timestamp = Instant.parse("2026-05-05T12:00:00Z"),
            projectId = ProjectId(1),
            action = ActionRecord.Archive(false, true),
        ))
    }

    /**
     * In-memory fake of the AppScope coordinator that owns the Health-chip filter. Tests can
     * either drive via [vm.dispatch(Intent.SetHealthFilter(..))] (which routes through the VM
     * back into here) or push directly with [setFilter] to simulate a sidebar chip click that
     * happened before the VM existed.
     */
    private class FakeProjectFilterCoordinator : ProjectFilterCoordinator {
        private val _filter = MutableStateFlow<HealthFilter?>(null)
        override val filter: StateFlow<HealthFilter?> = _filter.asStateFlow()
        override fun setFilter(filter: HealthFilter?) { _filter.value = filter }
    }

    private fun newVm(repo: ProjectRepository) =
        ProjectListViewModel(repo, FakeProjectFilterCoordinator())

    @Test
    fun stateUpdatesWhenRepositoryEmits() = runTest(mainDispatcher) {
        val all = MutableStateFlow(listOf(row(1, "kick"), row(2, "snare")))
        val repo = FakeRepo(mapOf("" to all))
        val vm = newVm(repo)

        vm.state.test {
            var s = awaitItem()
            while (s.rows.isEmpty()) s = awaitItem()
            assertEquals(listOf("kick", "snare"), s.rows.map { it.name })
            assertEquals(false, s.loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun searchIntentSwitchesObservedQuery() = runTest(mainDispatcher) {
        val all = MutableStateFlow(listOf(row(1, "kick"), row(2, "snare")))
        val matches = MutableStateFlow(listOf(row(1, "kick")))
        val repo = FakeRepo(mapOf("" to all, "kick" to matches))
        val vm = newVm(repo)

        vm.state.test {
            var s = awaitItem()
            while (s.rows.size < 2) s = awaitItem()
            assertEquals(2, s.rows.size)

            vm.dispatch(ProjectListViewModel.Intent.Search("kick"))

            while (s.query != "kick" || s.rows.size != 1) s = awaitItem()
            assertEquals("kick", s.query)
            assertEquals(listOf("kick"), s.rows.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun archivedRowsExposedSeparatelyFromActiveRows() = runTest(mainDispatcher) {
        val active = MutableStateFlow(listOf(row(1, "kick")))
        val archived = MutableStateFlow(listOf(row(2, "old-snare", archived = true)))
        val repo = FakeRepo(mapOf("" to active), archived)
        val vm = newVm(repo)

        vm.state.test {
            var s = awaitItem()
            while (s.rows.isEmpty() || s.archivedRows.isEmpty()) s = awaitItem()
            assertEquals(listOf("kick"), s.rows.map { it.name })
            assertEquals(listOf("old-snare"), s.archivedRows.map { it.name })
            assertTrue(s.rows.none { it.archived })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun tempoAndKeyFiltersNarrowTheRowSet() = runTest(mainDispatcher) {
        val all = MutableStateFlow(
            listOf(
                row(1, "a", tempo = 140.0, key = "F# Minor"),
                row(2, "b", tempo = 128.0, key = "F# Minor"),
                row(3, "c", tempo = 140.0, key = "C Major"),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val vm = newVm(repo)

        vm.state.test {
            // Drain until rows populate.
            var s = awaitItem()
            while (s.rows.size < 3) s = awaitItem()

            vm.dispatch(ProjectListViewModel.Intent.SetTempoRange(135.0..145.0))
            vm.dispatch(ProjectListViewModel.Intent.SetKeyFilter("F# Minor"))

            s = awaitItem()
            while (s.tempoRange == null || s.keyFilter != "F# Minor" || s.rows.size != 1) {
                s = awaitItem()
            }
            assertEquals(listOf("a"), s.rows.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearFiltersRestoresFullRowSet() = runTest(mainDispatcher) {
        val all = MutableStateFlow(
            listOf(
                row(1, "a", tempo = 140.0, key = "F# Minor"),
                row(2, "b", tempo = 128.0, key = "F# Minor"),
                row(3, "c", tempo = 140.0, key = "C Major"),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val vm = newVm(repo)

        vm.state.test {
            var s = awaitItem()
            while (s.rows.size < 3) s = awaitItem()

            vm.dispatch(ProjectListViewModel.Intent.SetKeyFilter("F# Minor"))
            while (s.keyFilter != "F# Minor" || s.rows.size != 2) s = awaitItem()
            assertEquals(setOf("a", "b"), s.rows.map { it.name }.toSet())

            vm.dispatch(ProjectListViewModel.Intent.ClearFilters)
            while (s.keyFilter != null || s.rows.size != 3) s = awaitItem()
            assertEquals(setOf("a", "b", "c"), s.rows.map { it.name }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stageFilterUsesEffectiveStageWithOverrideWinning() = runTest(mainDispatcher) {
        // a → inferred=Mixing, no override → effective=Mixing (passes filter)
        // b → inferred=InProgress, override=Mixing → effective=Mixing (passes filter)
        // c → inferred=Mixing, override=Sketch → effective=Sketch (filtered out)
        // d → inferred=null, no override → effective=null (filtered out)
        val all = MutableStateFlow(
            listOf(
                row(1, "a", stageInferred = Stage.Mixing),
                row(2, "b", stageInferred = Stage.InProgress, stageOverride = Stage.Mixing),
                row(3, "c", stageInferred = Stage.Mixing, stageOverride = Stage.Sketch),
                row(4, "d"),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val vm = newVm(repo)

        vm.state.test {
            var s = awaitItem()
            while (s.rows.size < 4) s = awaitItem()

            vm.dispatch(ProjectListViewModel.Intent.SetStageFilter(setOf(Stage.Mixing)))

            s = awaitItem()
            while (s.stageFilter != setOf(Stage.Mixing) || s.rows.size != 2) s = awaitItem()
            assertEquals(setOf("a", "b"), s.rows.map { it.name }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun healthFilterOnlyStuckNarrowsToInferredStuckRows() = runTest(mainDispatcher) {
        // PR-CC: a, b have stage_inferred=Stuck. c has Mixing override but inferred=Sketch
        // (override wins for the per-row chip but does NOT count as Stuck for the health filter
        // because the health filter reads stage_inferred to mirror the SQL aggregate). d's
        // override is Stuck but inferred is null — also filtered out.
        val all = MutableStateFlow(
            listOf(
                row(1, "a", stageInferred = Stage.Stuck),
                row(2, "b", stageInferred = Stage.Stuck),
                row(3, "c", stageInferred = Stage.Sketch, stageOverride = Stage.Mixing),
                row(4, "d", stageOverride = Stage.Stuck),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val vm = newVm(repo)

        vm.state.test {
            var s = awaitItem()
            while (s.rows.size < 4) s = awaitItem()

            vm.dispatch(ProjectListViewModel.Intent.SetHealthFilter(HealthFilter.OnlyStuck))

            s = awaitItem()
            while (s.healthFilter !is HealthFilter.OnlyStuck || s.rows.size != 2) s = awaitItem()
            assertEquals(setOf("a", "b"), s.rows.map { it.name }.toSet())

            // Clearing via null restores the full set without touching tempo/key/stage filters.
            vm.dispatch(ProjectListViewModel.Intent.SetHealthFilter(null))
            while (s.healthFilter != null || s.rows.size != 4) s = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun healthFilterOnlyMissingSamplesNarrowsToProjectsWithMissingSampleCount() = runTest(mainDispatcher) {
        val all = MutableStateFlow(
            listOf(
                row(1, "clean", missingSampleCount = 0),
                row(2, "broken", missingSampleCount = 3),
                row(3, "also-broken", missingSampleCount = 1),
            ),
        )
        val repo = FakeRepo(mapOf("" to all))
        val vm = newVm(repo)

        vm.state.test {
            var s = awaitItem()
            while (s.rows.size < 3) s = awaitItem()

            vm.dispatch(ProjectListViewModel.Intent.SetHealthFilter(HealthFilter.OnlyMissingSamples))
            s = awaitItem()
            while (s.healthFilter !is HealthFilter.OnlyMissingSamples || s.rows.size != 2) s = awaitItem()
            assertEquals(setOf("broken", "also-broken"), s.rows.map { it.name }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun openIntentEmitsNavigateEffectExactlyOnce() = runTest(mainDispatcher) {
        val repo = FakeRepo()
        val vm = newVm(repo)

        vm.effects.test {
            vm.dispatch(ProjectListViewModel.Intent.Open(ProjectId(7)))
            val effect = awaitItem()
            assertTrue(effect is ProjectListViewModel.Effect.Navigate)
            assertEquals(ProjectId(7), effect.id)
            expectNoEvents()
        }
    }
}
