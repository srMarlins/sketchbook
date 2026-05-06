package com.sketchbook.featuredetail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single-project pane. The selected project id and tab live in `MutableStateFlow`s; the
 * displayed `State` is a pure `combine(rowFlow, historyFlow, tabSelection)`. When `load(id)`
 * swaps the id, `flatMapLatest` cancels the previous row + history subscriptions and
 * re-subscribes to the new ones — no manual `Job` tracking.
 *
 * **Note on lifecycle.** Today the project detail surface is a side panel inside the project
 * list screen, not its own `NavEntry`. So this ViewModel keeps `load(id)` rather than taking the
 * id via `@AssistedInject`. When the detail surface migrates to its own nav destination, swap to
 * `@AssistedInject` keyed on `projectId` and remove `load(id)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class ProjectDetailViewModel(
    private val projects: ProjectRepository,
    private val snapshots: SnapshotRepository,
) : ViewModel() {

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

    /**
     * History flow is empty until [load] is called with a project that has a stable
     * [ProjectUuid]. Snapshot history is keyed on uuid (sync-pipeline identity) but the screen
     * speaks ProjectId — bridging requires a uuid lookup that today's pipeline doesn't expose
     * cheaply. Pre-existing limitation; not in scope for the DI refactor.
     */
    private val historyFlow: Flow<List<Snapshot>> = flowOf(emptyList())

    /**
     * Lock status mirrors the same uuid-bridging limitation as [historyFlow]. Pre-existing
     * behavior is "always Free" because the legacy lookup callback returned null; preserved here.
     */
    private val lockFlow: Flow<LockStatus> = flowOf(LockStatus.Free)

    val state: StateFlow<State> = combine(
        rowFlow,
        historyFlow,
        tabSelection,
        lockFlow,
        pluginsFlow,
        samplesFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST") val row = values[0] as ProjectRow?
        @Suppress("UNCHECKED_CAST") val history = values[1] as List<Snapshot>
        val tab = values[2] as Tab
        val lock = values[3] as LockStatus
        @Suppress("UNCHECKED_CAST") val plugins = values[4] as List<PluginRef>
        @Suppress("UNCHECKED_CAST") val samples = values[5] as List<SampleEntry>
        State(
            row = row,
            history = history,
            tab = tab,
            loading = row == null && selectedId.value != null,
            lockStatus = lock,
            plugins = plugins,
            samples = samples,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

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
            is Intent.ForceTakeLock -> Unit // No-op until uuid bridging lands.
            is Intent.Rename -> {
                val id = selectedId.value ?: return
                val trimmed = intent.name.trim()
                if (trimmed.isEmpty() || trimmed == state.value.row?.name) return
                viewModelScope.launch { projects.rename(id, trimmed) }
            }
            is Intent.SetTags -> {
                val id = selectedId.value ?: return
                val cleaned = intent.tags
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                viewModelScope.launch { projects.setTags(id, cleaned) }
            }
            is Intent.Move -> {
                val id = selectedId.value ?: return
                val current = state.value.row?.path?.value ?: return
                val target = intent.newParentDir.trim()
                if (target.isEmpty()) return
                if (target.replace('\\', '/') == parentDirOfPath(current)) return
                viewModelScope.launch { projects.move(id, target) }
            }
            is Intent.ToggleArchive -> {
                val row = state.value.row ?: return
                viewModelScope.launch { projects.archive(row.id, !row.archived) }
            }
        }
    }

    private fun parentDirOfPath(absPath: String): String {
        val normalized = absPath.replace('\\', '/')
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) normalized else normalized.substring(0, idx)
    }

    @Immutable
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
        data class Rename(val name: String) : Intent
        data class SetTags(val tags: List<String>) : Intent
        data class Move(val newParentDir: String) : Intent
        data object ToggleArchive : Intent
    }

    sealed interface Effect {
        data class LaunchLive(val projectPath: String) : Effect
        data object LockTaken : Effect
        data class LockTakeFailed(val reason: String) : Effect
    }
}
