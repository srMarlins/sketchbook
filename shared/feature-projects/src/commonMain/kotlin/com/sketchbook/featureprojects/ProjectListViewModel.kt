package com.sketchbook.featureprojects

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.Stage
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.core.AppScope
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
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val gemsShuffleSeed = MutableStateFlow(0)
    private val zoomShelf = MutableStateFlow<ShelfId?>(null)
    private val openDetailId = MutableStateFlow<ProjectId?>(null)
    private val searchSelectedIndex = MutableStateFlow(0)
    private val tempoRange = MutableStateFlow<ClosedFloatingPointRange<Double>?>(null)
    private val keyFilter = MutableStateFlow<String?>(null)
    private val stageFilter = MutableStateFlow<Set<Stage>>(emptySet())

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val rowsFlow: Flow<List<ProjectRow>> =
        query.flatMapLatest { repository.observeProjects(it) }
    private val archivedRowsFlow: Flow<List<ProjectRow>> = repository.observeArchivedProjects()
    private val distinctKeysFlow: Flow<List<String>> = repository.observeDistinctKeys()

    val state: StateFlow<State> = combine(
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
        ),
    ) { values ->
        @Suppress("UNCHECKED_CAST") val q = values[0] as String
        @Suppress("UNCHECKED_CAST") val rawRows = values[1] as List<ProjectRow>
        @Suppress("UNCHECKED_CAST") val archivedRows = values[2] as List<ProjectRow>
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

        // Filter happens here, before grouping/bucketing — so the chip selection narrows the
        // visible row set on every shelf. The 1,628-row library does this in microseconds.
        val rows = applyFilters(rawRows, tempo, key, stages)
        val groups = deriveProjectGroups(rows)
        val archivedGroups = deriveProjectGroups(archivedRows)
        val buckets = bucketize(groups, archivedGroups)
        val gemsView = if (seed == 0) {
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
            distinctKeys = distinctKeys,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    private fun applyFilters(
        rows: List<ProjectRow>,
        tempoRange: ClosedFloatingPointRange<Double>?,
        keyFilter: String?,
        stageFilter: Set<Stage>,
    ): List<ProjectRow> {
        if (tempoRange == null && keyFilter == null && stageFilter.isEmpty()) return rows
        return rows.filter { row ->
            val tempo = row.tempo
            (tempoRange == null || (tempo != null && tempo in tempoRange)) &&
                (keyFilter == null || row.key == keyFilter) &&
                // Stage filter narrows on the *effective* stage so a user override wins over the
                // inferred classification — same rule the chip uses for rendering.
                (stageFilter.isEmpty() || row.effectiveStage in stageFilter)
        }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Search -> {
                query.update { intent.query }
                searchSelectedIndex.update { 0 }
            }
            is Intent.Open -> _effects.tryEmit(Effect.Navigate(intent.id))
            is Intent.ZoomShelf -> zoomShelf.update { intent.shelf }
            is Intent.ShuffleGems -> gemsShuffleSeed.update { (it + 1).coerceAtLeast(1) }
            is Intent.OpenDetail -> openDetailId.update { intent.id }
            is Intent.CloseDetail -> openDetailId.update { null }
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
            is Intent.SetTempoRange -> tempoRange.update { intent.range }
            is Intent.SetKeyFilter -> keyFilter.update { intent.key }
            is Intent.SetStageFilter -> stageFilter.update { intent.stages }
            is Intent.ClearFilters -> {
                tempoRange.update { null }
                keyFilter.update { null }
                stageFilter.update { emptySet() }
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
        /** Sorted, distinct, non-null keys present in the catalog. Powers the Key chip's popup. */
        val distinctKeys: List<String> = emptyList(),
        val loading: Boolean = true,
    )

    sealed interface Intent {
        data class Search(val query: String) : Intent
        data class Open(val id: ProjectId) : Intent
        data class ZoomShelf(val shelf: ShelfId?) : Intent
        data object ShuffleGems : Intent
        data class OpenDetail(val id: ProjectId) : Intent
        data object CloseDetail : Intent
        data object NavigateSearchNext : Intent
        data object NavigateSearchPrev : Intent
        data object OpenSelectedSearch : Intent
        /** Set the tempo range filter (`null` clears it). Applies to the row set before bucketing. */
        data class SetTempoRange(val range: ClosedFloatingPointRange<Double>?) : Intent
        /** Set the key filter (e.g. "F# Minor"; `null` clears it). */
        data class SetKeyFilter(val key: String?) : Intent
        /** Replace the stage filter set; empty clears it. Multi-select toolbar chip dispatches
         *  this as the user toggles each Stage. */
        data class SetStageFilter(val stages: Set<Stage>) : Intent
        /** Clear all three filters (tempo / key / stage) in one shot. */
        data object ClearFilters : Intent
    }

    sealed interface Effect {
        data class Navigate(val id: ProjectId) : Effect
    }
}
