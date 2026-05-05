package com.sketchbook.featuredetail

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.SnapshotRepository
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Single-project pane. The selected project id and tab live in `MutableStateFlow`s; the
 * displayed `State` is a pure `combine(rowFlow, historyFlow, tabSelection)`. When `load(id)`
 * swaps the id, `flatMapLatest` cancels the previous row + history subscriptions and
 * re-subscribes to the new ones — no manual `Job` tracking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailStateHolder(
    private val projects: ProjectRepository,
    private val snapshots: SnapshotRepository,
    scope: CoroutineScope,
    private val projectUuidLookup: suspend (ProjectId) -> ProjectUuid? = { null },
) {

    private val selectedId = MutableStateFlow<ProjectId?>(null)
    private val tabSelection = MutableStateFlow(Tab.Overview)

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val rowFlow: Flow<ProjectRow?> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(null) else projects.observeProject(id)
    }

    private val historyFlow: Flow<List<Snapshot>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else flow {
            val uuid = projectUuidLookup(id)
            if (uuid == null) emit(emptyList()) else emitAll(snapshots.observeHistory(uuid))
        }
    }

    val state: StateFlow<State> = combine(rowFlow, historyFlow, tabSelection) { row, history, tab ->
        State(row = row, history = history, tab = tab, loading = row == null && selectedId.value != null)
    }.stateIn(scope, SharingStarted.Eagerly, State())

    fun load(id: ProjectId) {
        selectedId.update { id }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.SelectTab -> tabSelection.update { intent.tab }
            is Intent.OpenInLive -> {
                val path = state.value.row?.path?.value ?: return
                _effects.tryEmit(Effect.LaunchLive(path))
            }
        }
    }

    data class State(
        val row: ProjectRow? = null,
        val history: List<Snapshot> = emptyList(),
        val tab: Tab = Tab.Overview,
        val loading: Boolean = false,
    )

    enum class Tab { Overview, Tracks, Samples, Plugins, History }

    sealed interface Intent {
        data class SelectTab(val tab: Tab) : Intent
        data object OpenInLive : Intent
    }

    sealed interface Effect {
        data class LaunchLive(val projectPath: String) : Effect
    }
}
