package com.sketchbook.featuredetail

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Single-project pane. Combines the row from [ProjectRepository] with snapshot history from
 * [SnapshotRepository]. Tracks/samples/plugins tabs surface project metadata once the catalog
 * exposes it via the repository seam (PR-18); for now those tabs render an "extracted at index
 * time" stub.
 */
class ProjectDetailStateHolder(
    private val projects: ProjectRepository,
    private val snapshots: SnapshotRepository,
    private val scope: CoroutineScope,
    private val projectUuidLookup: suspend (ProjectId) -> ProjectUuid? = { null },
) {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    fun load(id: ProjectId) {
        _state.value = State(loading = true)
        scope.launch {
            projects.observeProject(id).collectLatest { row ->
                _state.value = _state.value.copy(row = row, loading = false)
            }
        }
        scope.launch {
            val uuid = projectUuidLookup(id) ?: return@launch
            snapshots.observeHistory(uuid).collectLatest { history ->
                _state.value = _state.value.copy(history = history)
            }
        }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.SelectTab -> _state.value = _state.value.copy(tab = intent.tab)
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
