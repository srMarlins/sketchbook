package com.sketchbook.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.core.AppScope
import com.sketchbook.core.PluginFormat
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import com.sketchbook.desktop.repo.SwappableSyncQueue
import com.sketchbook.repo.LibraryHealth
import com.sketchbook.repo.PluginUsage
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.repo.ProposalStatus
import com.sketchbook.repo.ProposalsRepository
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SyncQueue
import com.sketchbook.repo.SyncQueueState
import com.sketchbook.sync.ForceSnapshotPipeline
import com.sketchbook.sync.ForceSnapshotUseCase
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Aggregates all the cross-cutting state and operations the desktop chrome (sidebar, activity
 * bar, force-snapshot hotkey, side-panel plumbing) reads. Without this VM, `RootContent` would
 * have to take a `DesktopAppGraph` and reach into it as a service locator — exactly the
 * anti-pattern Metro DI is meant to eliminate.
 *
 * Repos and the [SwappableSyncQueue] are constructor-injected; the runtime cast from the
 * generic [SyncQueue] binding to the concrete desktop façade is hidden inside [syncImpl] so
 * call sites stay type-stable.
 */
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class RootChromeViewModel(
    syncQueue: SyncQueue,
    private val syncStateStore: SyncStateStore,
    scanCoordinator: LibraryScanCoordinator,
    proposalsRepository: ProposalsRepository,
    repairRepository: RepairRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    /**
     * Down-cast captured once. The graph binds [SwappableSyncQueue] to [SyncQueue]; the cast is
     * a no-op in production but stays nullable so unit tests can pass an [InMemorySyncQueue]
     * (or any other [SyncQueue]) without faking the desktop façade. Methods that depend on it
     * fall back to no-ops when null.
     */
    private val syncImpl: SwappableSyncQueue? = syncQueue as? SwappableSyncQueue

    val syncState: StateFlow<SyncQueueState> = syncQueue.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, SyncQueueState())

    val scanProgress: StateFlow<ScanUiState> = scanCoordinator.progress

    val proposalCount: StateFlow<Int> = proposalsRepository.observe()
        .map { proposals -> proposals.count { it.status == ProposalStatus.Pending } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val attentionCount: StateFlow<Int> = repairRepository.observeFindings()
        .map { it.macImports.size + it.missingSamplesTotal }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val libraryHealth: StateFlow<LibraryHealth> = projectRepository.observeLibraryHealth()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryHealth.EMPTY)

    /**
     * `null` when cloud isn't configured — the cluster of nullable callbacks (`syncStateFor`,
     * `onPushNow`, `conflictMessageFor`) bottoms out here so the UI can disable affordances
     * without a deeper conditional.
     */
    val syncWired: Boolean get() = syncImpl != null

    /** Per-project sync pip. Falls back to [ProjectSyncState.Unknown] without a desktop façade. */
    fun syncStateFor(id: ProjectId): ProjectSyncState =
        syncImpl?.snapshotFor(id) ?: ProjectSyncState.Unknown

    /** Optional friendly message for the per-project conflict surface. */
    fun conflictMessage(id: ProjectId): String? = syncImpl?.conflictMessage(id)

    /** Trigger an immediate push for one project. No-op without cloud. */
    suspend fun pushNow(id: ProjectId): Result<Unit> =
        syncImpl?.pushNowById(id) ?: Result.failure(IllegalStateException("Cloud not configured"))

    /** Translate the local id used everywhere in the UI to the cloud-stable [ProjectUuid]. */
    fun timelineUuidFor(id: ProjectId): ProjectUuid = syncStateStore.identityFor(id)

    /**
     * Inverse plugin query for the side-panel "Plugins" tab. Routes the call through the
     * already-injected [ProjectRepository] so callers don't see the repo at all.
     */
    fun observeProjectsUsing(
        pluginName: String,
        format: PluginFormat?,
        excludeProjectId: ProjectId?,
    ): Flow<List<PluginUsage>> = projectRepository.observeProjectsUsing(pluginName, format, excludeProjectId)

    /**
     * Quick-capture hotkey (Ctrl/Cmd+Shift+S) routes through this use case. Wraps the runtime
     * `syncImpl` so callers don't see the cast or the no-cloud fallback.
     */
    val forceSnapshotUseCase: ForceSnapshotUseCase = ForceSnapshotUseCase(
        syncImpl ?: object : ForceSnapshotPipeline {
            override suspend fun recordForcedNamed(
                uuid: ProjectUuid,
                label: String,
            ): Result<SnapshotRev> = Result.failure(
                IllegalStateException("Cloud sync isn't configured — set credentials in Settings first."),
            )
        },
    )
}
