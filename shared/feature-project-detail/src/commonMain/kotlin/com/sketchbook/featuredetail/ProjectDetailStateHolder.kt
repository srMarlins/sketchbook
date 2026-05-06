package com.sketchbook.featuredetail

import com.sketchbook.core.PluginRef
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.repo.LockRepository
import com.sketchbook.repo.LockStatus
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.SampleEntry
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
import kotlinx.coroutines.launch

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
    private val scope: CoroutineScope,
    private val projectUuidLookup: suspend (ProjectId) -> ProjectUuid? = { null },
    private val locks: LockRepository? = null,
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

    private val pluginsFlow: Flow<List<PluginRef>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else projects.observePlugins(id)
    }

    private val samplesFlow: Flow<List<SampleEntry>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else projects.observeSamples(id)
    }

    private val historyFlow: Flow<List<Snapshot>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else flow {
            val uuid = projectUuidLookup(id)
            if (uuid == null) emit(emptyList()) else emitAll(snapshots.observeHistory(uuid))
        }
    }

    private val lockFlow: Flow<LockStatus> = selectedId.flatMapLatest { id ->
        val repo = locks
        if (id == null || repo == null) {
            flowOf(LockStatus.Free)
        } else {
            flow<LockStatus> {
                val uuid = projectUuidLookup(id)
                if (uuid == null) emit(LockStatus.Free) else emitAll(repo.observe(uuid))
            }
        }
    }

    val state: StateFlow<State> = combine(
        rowFlow,
        historyFlow,
        tabSelection,
        lockFlow,
        pluginsFlow,
        samplesFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val row = values[0] as ProjectRow?
        @Suppress("UNCHECKED_CAST")
        val history = values[1] as List<Snapshot>
        val tab = values[2] as Tab
        val lock = values[3] as LockStatus
        @Suppress("UNCHECKED_CAST")
        val plugins = values[4] as List<PluginRef>
        @Suppress("UNCHECKED_CAST")
        val samples = values[5] as List<SampleEntry>
        State(
            row = row,
            history = history,
            tab = tab,
            loading = row == null && selectedId.value != null,
            lockStatus = lock,
            plugins = plugins,
            samples = samples,
        )
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
            is Intent.ForceTakeLock -> forceTake()
        }
    }

    private fun forceTake() {
        val repo = locks ?: return
        val id = selectedId.value ?: return
        scope.launch {
            val uuid = projectUuidLookup(id) ?: return@launch
            val r = repo.forceTake(uuid)
            if (r.isSuccess) _effects.tryEmit(Effect.LockTaken)
            else _effects.tryEmit(Effect.LockTakeFailed(r.exceptionOrNull()?.message ?: "force-take failed"))
        }
    }

    data class State(
        val row: ProjectRow? = null,
        val history: List<Snapshot> = emptyList(),
        val tab: Tab = Tab.Overview,
        val loading: Boolean = false,
        val lockStatus: LockStatus = LockStatus.Free,
        val plugins: List<PluginRef> = emptyList(),
        val samples: List<SampleEntry> = emptyList(),
    )

    enum class Tab { Overview, Tracks, Samples, Plugins, History }

    sealed interface Intent {
        data class SelectTab(val tab: Tab) : Intent
        data object OpenInLive : Intent
        data object ForceTakeLock : Intent
    }

    sealed interface Effect {
        data class LaunchLive(val projectPath: String) : Effect
        data object LockTaken : Effect
        data class LockTakeFailed(val reason: String) : Effect
    }
}
