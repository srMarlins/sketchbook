package com.sketchbook.featureprojects

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.CoroutineScope
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
 * Project list state holder. Plain Kotlin: `StateFlow<State>` for UI to render, `SharedFlow<Effect>`
 * for one-shot navigation/notifications, and a `dispatch(Intent)` entry point. No MVI library —
 * sealed-class intents handled by a `when`.
 *
 * **Derived state lives here, not in the view.** `groups` / `buckets` / `gemsView` /
 * `searchResults` are all computed from `rows + query + gemsShuffleSeed` inside the combiner,
 * so the screen composable just reads `state.*`. Selection (`searchSelectedIndex`,
 * `zoomShelf`, `openDetailId`) is driven by intents — the view never holds `var ... by remember`
 * for these.
 *
 * **Why FTS5 + matchesQuery both filter.** The repository's `observeProjects(query)` runs FTS5
 * which is fast but row-level. `matchesQuery` re-checks at the *group* level so a folder-only
 * or tag-only hit still passes when no individual row's `name` matches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectListStateHolder(
    private val repository: ProjectRepository,
    scope: CoroutineScope,
) {

    private val query = MutableStateFlow("")
    private val gemsShuffleSeed = MutableStateFlow(0)
    private val zoomShelf = MutableStateFlow<ShelfId?>(null)
    private val openDetailId = MutableStateFlow<ProjectId?>(null)
    private val searchSelectedIndex = MutableStateFlow(0)
    private val tempoRange = MutableStateFlow<ClosedFloatingPointRange<Double>?>(null)
    private val keyFilter = MutableStateFlow<String?>(null)

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    // Rows track the query — repository's `observeProjects` does FTS5 narrowing for us.
    private val rowsFlow: Flow<List<ProjectRow>> = query.flatMapLatest { repository.observeProjects(it) }
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

        // Filter happens here, before grouping/bucketing — so the chip selection narrows the
        // visible row set on every shelf. The 1,628-row library does this in microseconds.
        val rows = applyFilters(rawRows, tempo, key)
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
            distinctKeys = distinctKeys,
            loading = false,
        )
    }.stateIn(scope, SharingStarted.Eagerly, State(loading = true))

    private fun applyFilters(
        rows: List<ProjectRow>,
        tempoRange: ClosedFloatingPointRange<Double>?,
        keyFilter: String?,
    ): List<ProjectRow> {
        if (tempoRange == null && keyFilter == null) return rows
        return rows.filter { row ->
            val tempo = row.tempo
            (tempoRange == null || (tempo != null && tempo in tempoRange)) &&
                (keyFilter == null || row.key == keyFilter)
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
            is Intent.ClearFilters -> {
                tempoRange.update { null }
                keyFilter.update { null }
            }
        }
    }

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
        /** Sorted, distinct, non-null keys present in the catalog. Powers the Key chip's popup. */
        val distinctKeys: List<String> = emptyList(),
        val loading: Boolean = true,
    )

    sealed interface Intent {
        /** Update the search query. Resets `searchSelectedIndex` to 0. */
        data class Search(val query: String) : Intent
        /** Emits [Effect.Navigate] for external callers (deep-links, MCP); the dashboard uses
         *  [OpenDetail] for its side panel instead. */
        data class Open(val id: ProjectId) : Intent
        /** Switch the dashboard into "show all of bucket X" mode (or back to overview if null). */
        data class ZoomShelf(val shelf: ShelfId?) : Intent
        /** Re-roll the random sample shown on the Forgotten Gems shelf. */
        data object ShuffleGems : Intent
        /** Open the side detail panel for [id]. */
        data class OpenDetail(val id: ProjectId) : Intent
        /** Dismiss the side detail panel. */
        data object CloseDetail : Intent
        /** Move the keyboard cursor down one search result. */
        data object NavigateSearchNext : Intent
        /** Move the keyboard cursor up one search result. */
        data object NavigateSearchPrev : Intent
        /** Open the detail panel for the currently-highlighted search result. */
        data object OpenSelectedSearch : Intent
        /** Set the tempo range filter (`null` clears it). Applies to the row set before bucketing. */
        data class SetTempoRange(val range: ClosedFloatingPointRange<Double>?) : Intent
        /** Set the key filter (e.g. "F# Minor"; `null` clears it). */
        data class SetKeyFilter(val key: String?) : Intent
        /** Clear both tempo and key filters in one shot. */
        data object ClearFilters : Intent
    }

    sealed interface Effect {
        data class Navigate(val id: ProjectId) : Effect
    }
}
