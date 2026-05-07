package com.sketchbook.featureprojects

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.Stage
import com.sketchbook.repo.ProjectRepository
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Project list ViewModel. `viewModelScope` cancels on `NavEntry` pop.
 *
 * Plain Kotlin: `StateFlow<State>`, `SharedFlow<Effect>`, `dispatch(Intent)` — no MVI library.
 * Derived state (groups/buckets/gemsView/searchResults) lives in the combiner so the screen
 * composable just reads `state.*`.
 *
 * **Why FTS5 + matchesQuery both filter.** The repository's `observeProjects(query)` runs FTS5
 * which is fast but row-level. `matchesQuery` re-checks at the *group* level so a folder-only
 * or tag-only hit still passes when no individual row's `name` matches.
 *
 * Scratch UI state (`query`, `gemsShuffleSeed`, `zoomShelf`) is in-memory; window-restore
 * via `SavedStateHandle` is a future enhancement once the desktop `SavedStateRegistry` writer
 * is wired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class ProjectListViewModel(
    private val repository: ProjectRepository,
    private val filterCoordinator: ProjectFilterCoordinator,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val gemsShuffleSeed = MutableStateFlow(0)
    private val zoomShelf = MutableStateFlow<ShelfId?>(null)
    private val openDetailId = MutableStateFlow<ProjectId?>(null)
    private val searchSelectedIndex = MutableStateFlow(0)
    private val tempoRange = MutableStateFlow<ClosedFloatingPointRange<Double>?>(null)
    private val keyFilter = MutableStateFlow<String?>(null)
    private val stageFilter = MutableStateFlow<Set<Stage>>(emptySet())

    // PR-CC: row-click filter from the sidebar Health chip. Narrows the visible row set to the
    // failing subset (e.g. only stuck projects, only projects with missing samples). `null` =
    // no health-chip narrowing. Lives alongside the existing tempo/key/stage filters and is
    // applied in the same `applyFilters` combiner pass so the dashboard shelves all narrow at once.
    //
    // Owned by [ProjectFilterCoordinator] (`@SingleIn(AppScope)`) rather than this VM so the
    // sidebar Health chip — which lives in chrome scope and clicks before this VM exists for
    // a fresh `Browse` NavEntry — can publish into the same flow the VM consumes. No
    // `LaunchedEffect` orchestration in `RootContent` needed.
    private val healthFilter: StateFlow<HealthFilter?> get() = filterCoordinator.filter

    private val _effects =
        MutableSharedFlow<Effect>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val rowsFlow: Flow<List<ProjectRow>> =
        query.flatMapLatest { repository.observeProjects(it) }
    private val archivedRowsFlow: Flow<List<ProjectRow>> = repository.observeArchivedProjects()
    private val distinctKeysFlow: Flow<List<String>> = repository.observeDistinctKeys()

    val state: StateFlow<State> =
        combine(
            listOf(
                query,
                rowsFlow,
                archivedRowsFlow,
                gemsShuffleSeed,
                zoomShelf,
                openDetailId,
                searchSelectedIndex,
                tempoRange,
                keyFilter,
                distinctKeysFlow,
                stageFilter,
                healthFilter,
            ),
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val q = values[0] as String

            @Suppress("UNCHECKED_CAST")
            val rawRows = values[1] as List<ProjectRow>

            @Suppress("UNCHECKED_CAST")
            val archivedRows = values[2] as List<ProjectRow>
            val seed = values[3] as Int
            val zoom = values[4] as ShelfId?
            val openId = values[5] as ProjectId?
            val selectedIdx = values[6] as Int

            @Suppress("UNCHECKED_CAST")
            val tempo = values[7] as ClosedFloatingPointRange<Double>?
            val key = values[8] as String?

            @Suppress("UNCHECKED_CAST")
            val distinctKeys = values[9] as List<String>

            @Suppress("UNCHECKED_CAST")
            val stages = values[10] as Set<Stage>
            val health = values[11] as HealthFilter?

            // Filter happens here, before grouping/bucketing — so the chip selection narrows the
            // visible row set on every shelf. The 1,628-row library does this in microseconds.
            val rows = applyFilters(rawRows, tempo, key, stages, health)
            val groups = deriveProjectGroups(rows)
            val archivedGroups = deriveProjectGroups(archivedRows)
            val buckets = bucketize(groups, archivedGroups)
            val gemsView =
                if (seed == 0) {
                    buckets.forgottenGems
                } else {
                    buckets.forgottenGems.shuffled(kotlin.random.Random(seed.toLong() * 7919L))
                }
            val results = if (q.isBlank()) emptyList() else groups.filter { matchesQuery(it, q) }
            val clampedIdx = if (results.isEmpty()) 0 else selectedIdx.coerceIn(0, results.size - 1)

            State(
                query = q,
                rows = rows,
                archivedRows = archivedRows,
                groups = groups,
                buckets = buckets,
                gemsView = gemsView,
                gemsShuffleSeed = seed,
                searchResults = results,
                searchSelectedIndex = clampedIdx,
                zoomShelf = zoom,
                openDetailId = openId,
                tempoRange = tempo,
                keyFilter = key,
                stageFilter = stages,
                healthFilter = health,
                distinctKeys = distinctKeys,
                loading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    private fun applyFilters(
        rows: List<ProjectRow>,
        tempoRange: ClosedFloatingPointRange<Double>?,
        keyFilter: String?,
        stageFilter: Set<Stage>,
        healthFilter: HealthFilter?,
    ): List<ProjectRow> {
        if (tempoRange == null && keyFilter == null && stageFilter.isEmpty() && healthFilter == null) return rows
        return rows.filter { row ->
            val tempo = row.tempo
            (tempoRange == null || (tempo != null && tempo in tempoRange)) &&
                (keyFilter == null || row.key == keyFilter) &&
                // Stage filter narrows on the *effective* stage so a user override wins over the
                // inferred classification — same rule the chip uses for rendering.
                (stageFilter.isEmpty() || row.effectiveStage in stageFilter) &&
                // PR-CC: health filter narrows on the *inferred* stage (mirroring the SQL
                // aggregate's choice). Override-driven stage changes don't move a row into or
                // out of the failing-stuck subset; the dashboard tracks the catalog's objective
                // state. Missing-samples filter reads the cached count on the row.
                (healthFilter == null || healthFilter.matches(row))
        }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Search -> {
                query.update { intent.query }
                searchSelectedIndex.update { 0 }
            }

            is Intent.Open -> {
                _effects.tryEmit(Effect.Navigate(intent.id))
            }

            is Intent.ZoomShelf -> {
                zoomShelf.update { intent.shelf }
            }

            is Intent.ShuffleGems -> {
                gemsShuffleSeed.update { (it + 1).coerceAtLeast(1) }
            }

            is Intent.OpenDetail -> {
                openDetailId.update { intent.id }
            }

            is Intent.CloseDetail -> {
                openDetailId.update { null }
            }

            is Intent.NavigateSearchNext -> {
                val size = state.value.searchResults.size
                if (size > 0) {
                    searchSelectedIndex.update { (it + 1).coerceAtMost(size - 1) }
                }
            }

            is Intent.NavigateSearchPrev -> {
                val size = state.value.searchResults.size
                if (size > 0) {
                    searchSelectedIndex.update { (it - 1).coerceAtLeast(0) }
                }
            }

            is Intent.OpenSelectedSearch -> {
                val s = state.value
                s.searchResults.getOrNull(s.searchSelectedIndex)?.let { group ->
                    openDetailId.update { group.representative.id }
                }
            }

            is Intent.SetTempoRange -> {
                tempoRange.update { intent.range }
            }

            is Intent.SetKeyFilter -> {
                keyFilter.update { intent.key }
            }

            is Intent.SetStageFilter -> {
                stageFilter.update { intent.stages }
            }

            is Intent.SetHealthFilter -> {
                filterCoordinator.setFilter(intent.filter)
            }

            is Intent.ClearFilters -> {
                tempoRange.update { null }
                keyFilter.update { null }
                stageFilter.update { emptySet() }
                filterCoordinator.setFilter(null)
            }
        }
    }

    @Immutable
    data class State(
        val query: String = "",
        val rows: List<ProjectRow> = emptyList(),
        val archivedRows: List<ProjectRow> = emptyList(),
        val groups: List<ProjectGroup> = emptyList(),
        val buckets: Buckets = Buckets.EMPTY,
        val gemsView: List<ProjectGroup> = emptyList(),
        val gemsShuffleSeed: Int = 0,
        val searchResults: List<ProjectGroup> = emptyList(),
        val searchSelectedIndex: Int = 0,
        val zoomShelf: ShelfId? = null,
        val openDetailId: ProjectId? = null,
        /** Active tempo (BPM) filter range; null = "Tempo: any". Filter is applied to `rows`
         *  (and downstream groups/buckets) inside the combiner. */
        val tempoRange: ClosedFloatingPointRange<Double>? = null,
        /** Active key filter (e.g. "F# Minor"); null = "Key: any". */
        val keyFilter: String? = null,
        /** Active stage filter; empty = "Stage: any". Multi-select via the toolbar chip popup;
         *  the row passes when its [com.sketchbook.core.ProjectRow.effectiveStage] is in the set. */
        val stageFilter: Set<Stage> = emptySet(),
        /** PR-CC: health-row filter. `null` = no health-chip narrowing. Set by clicking a
         *  Health-chip breakdown row in the sidebar; cleared by [Intent.ClearFilters] or by
         *  passing `null` via [Intent.SetHealthFilter]. */
        val healthFilter: HealthFilter? = null,
        /** Sorted, distinct, non-null keys present in the catalog. Powers the Key chip's popup. */
        val distinctKeys: List<String> = emptyList(),
        val loading: Boolean = true,
    )

    sealed interface Intent {
        data class Search(
            val query: String,
        ) : Intent

        data class Open(
            val id: ProjectId,
        ) : Intent

        data class ZoomShelf(
            val shelf: ShelfId?,
        ) : Intent

        data object ShuffleGems : Intent

        data class OpenDetail(
            val id: ProjectId,
        ) : Intent

        data object CloseDetail : Intent

        data object NavigateSearchNext : Intent

        data object NavigateSearchPrev : Intent

        data object OpenSelectedSearch : Intent

        /** Set the tempo range filter (`null` clears it). Applies to the row set before bucketing. */
        data class SetTempoRange(
            val range: ClosedFloatingPointRange<Double>?,
        ) : Intent

        /** Set the key filter (e.g. "F# Minor"; `null` clears it). */
        data class SetKeyFilter(
            val key: String?,
        ) : Intent

        /** Replace the stage filter set; empty clears it. Multi-select toolbar chip dispatches
         *  this as the user toggles each Stage. */
        data class SetStageFilter(
            val stages: Set<Stage>,
        ) : Intent

        /** PR-CC: set or clear the sidebar Health-chip row filter. `null` clears the filter so
         *  the user can fall back to the unfiltered library without going through ClearFilters
         *  (which would also wipe their active tempo/key/stage selections). */
        data class SetHealthFilter(
            val filter: HealthFilter?,
        ) : Intent

        /** Clear all four filters (tempo / key / stage / health) in one shot. */
        data object ClearFilters : Intent
    }

    sealed interface Effect {
        data class Navigate(
            val id: ProjectId,
        ) : Effect
    }
}

/**
 * PR-CC: row-click filter from the sidebar Health chip. Each variant maps to one of the
 * four breakdown rows in [com.sketchbook.desktop.HealthChip]. The chip click sets the
 * matching variant on [ProjectListViewModel.Intent.SetHealthFilter] so the dashboard narrows
 * to the failing subset.
 *
 * **Coverage today:** `OnlyStuck` and `OnlyMissingSamples` are wired — they read [ProjectRow]
 * fields the row already carries (`stageInferred` and `missingSampleCount`). The Synced and
 * Plugins-installed variants are tracked but currently no-op against the row set: those signals
 * live in tables ([sync_state], [project_plugins.is_installed]) that don't surface on
 * `ProjectRow` yet, and pulling them onto the row is a larger change than this PR's scope.
 * A follow-up will widen the row, at which point [matches] for those two variants stops being
 * a no-op `true`.
 */
sealed interface HealthFilter {
    fun matches(row: ProjectRow): Boolean

    /** Mirrors the SQL aggregate's choice — read [ProjectRow.stageInferred], not effectiveStage,
     *  so a user override doesn't silently move a project out of the failing-Stuck subset. */
    data object OnlyStuck : HealthFilter {
        override fun matches(row: ProjectRow): Boolean = row.stageInferred == Stage.Stuck
    }

    /** Any project the parser couldn't resolve at least one sample for. */
    data object OnlyMissingSamples : HealthFilter {
        override fun matches(row: ProjectRow): Boolean = row.missingSampleCount > 0
    }

    /** Placeholder — the row doesn't carry sync state today. Always passes; the chip click
     *  navigates the user to Browse without narrowing. Tracked as deferred follow-up. */
    data object OnlyUnsynced : HealthFilter {
        override fun matches(row: ProjectRow): Boolean = true
    }

    /** Placeholder — the row doesn't carry plugin-presence today. Always passes; same
     *  deferral as [OnlyUnsynced]. */
    data object OnlyMissingPlugins : HealthFilter {
        override fun matches(row: ProjectRow): Boolean = true
    }
}
