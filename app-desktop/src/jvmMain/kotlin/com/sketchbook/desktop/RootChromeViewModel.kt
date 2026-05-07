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
import com.sketchbook.featureprojects.HealthFilter
import com.sketchbook.featureprojects.ProjectFilterCoordinator
import com.sketchbook.repo.LibraryHealth
import com.sketchbook.repo.MissingPluginRow
import com.sketchbook.repo.MissingPluginSummary
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.PluginUsage
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.repo.ProposalStatus
import com.sketchbook.repo.ProposalsRepository
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SettingsRepository
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val filterCoordinator: ProjectFilterCoordinator,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    /**
     * Down-cast captured once. The graph binds [SwappableSyncQueue] to [SyncQueue]; the cast is
     * a no-op in production but stays nullable so unit tests can pass an [InMemorySyncQueue]
     * (or any other [SyncQueue]) without faking the desktop façade. Methods that depend on it
     * fall back to no-ops when null.
     */
    private val syncImpl: SwappableSyncQueue? = syncQueue as? SwappableSyncQueue

    val syncState: StateFlow<SyncQueueState> =
        syncQueue
            .observe()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SyncQueueState())

    val scanProgress: StateFlow<ScanUiState> = scanCoordinator.progress

    val proposalCount: StateFlow<Int> =
        proposalsRepository
            .observe()
            .map { proposals -> proposals.count { it.status == ProposalStatus.Pending } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val attentionCount: StateFlow<Int> =
        repairRepository
            .observeFindings()
            .map { it.macImports.size + it.missingSamplesTotal }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val libraryHealth: StateFlow<LibraryHealth> =
        projectRepository
            .observeLibraryHealth()
            .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryHealth.EMPTY)

    /**
     * Sidebar Health-chip row-click handler. Publishes [filter] into the AppScope-lifetime
     * [ProjectFilterCoordinator] so the per-NavEntry `ProjectListViewModel` (which may not even
     * exist yet at the moment the chip is clicked) picks it up via constructor injection. No
     * `LaunchedEffect` orchestration needed in `RootContent`.
     */
    fun publishHealthFilter(filter: HealthFilter) {
        filterCoordinator.setFilter(filter)
    }

    /**
     * PR-T: scalar count for the Home coverage chip. `null` while the SQL is still warming up so
     * the chip has a way to render nothing rather than a flashing "0 missing" placeholder.
     */
    val missingPluginSummary: StateFlow<MissingPluginSummary?> =
        projectRepository
            .observeMissingPluginSummary()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * PR-T: list of missing (name, format) pairs that the chip's popup renders. Empty list while
     * loading; filled once the probe has run + the SQL has propagated.
     */
    val missingPluginCoverage: StateFlow<List<MissingPluginRow>> =
        projectRepository
            .observeMissingPluginCoverage()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Soft re-prompt for deferred onboarding steps. `AddSamples` is the only variant today —
     * it surfaces when the user skipped the samples-folders step during onboarding and hasn't
     * dismissed the banner yet. Future cloud onboarding adds a `AddCloud` member.
     */
    val pendingOnboardingPrompt: StateFlow<OnboardingPrompt?> =
        settingsRepository
            .observe()
            .map { settings -> derivePendingOnboardingPrompt(settings.onboardingSkipped) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Sticky-dismiss the banner for [prompt]. Maps the UI-level prompt back to the
     * persistence-level [OnboardingPromptKind] so the repository owns the storage shape.
     */
    fun dismissPrompt(prompt: OnboardingPrompt) {
        viewModelScope.launch {
            when (prompt) {
                OnboardingPrompt.AddSamples -> {
                    settingsRepository.dismissOnboardingPrompt(OnboardingPromptKind.Samples)
                }
            }
        }
    }

    /**
     * `null` when cloud isn't configured — the cluster of nullable callbacks (`syncStateFor`,
     * `onPushNow`, `conflictMessageFor`) bottoms out here so the UI can disable affordances
     * without a deeper conditional.
     */
    val syncWired: Boolean get() = syncImpl != null

    /** Per-project sync pip. Falls back to [ProjectSyncState.Unknown] without a desktop façade. */
    fun syncStateFor(id: ProjectId): ProjectSyncState = syncImpl?.snapshotFor(id) ?: ProjectSyncState.Unknown

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
    val forceSnapshotUseCase: ForceSnapshotUseCase =
        ForceSnapshotUseCase(
            syncImpl ?: object : ForceSnapshotPipeline {
                override suspend fun recordForcedNamed(
                    uuid: ProjectUuid,
                    label: String,
                ): Result<SnapshotRev> =
                    Result.failure(
                        IllegalStateException("Cloud sync isn't configured — set credentials in Settings first."),
                    )
            },
        )
}

/**
 * UI-level enumeration of soft re-prompt banners shown on Home. Distinct from the
 * persistence-level [OnboardingPromptKind] so the chrome can carry richer per-prompt state in
 * the future (copy variants, A/B-able CTAs) without leaking into the repository contract.
 */
sealed interface OnboardingPrompt {
    /** User skipped the samples-folders step during onboarding. */
    data object AddSamples : OnboardingPrompt
    // Future: data object AddCloud — when cloud onboarding lands.
}

/**
 * Pure derivation from [OnboardingSkipFlags] to a UI-level [OnboardingPrompt]. Extracted from
 * [RootChromeViewModel.pendingOnboardingPrompt] so the rule is unit-testable without standing up
 * the full chrome VM (which needs a Catalog-backed [com.sketchbook.catalog.SyncStateStore] and
 * other heavy collaborators that have nothing to do with this banner).
 */
internal fun derivePendingOnboardingPrompt(flags: OnboardingSkipFlags): OnboardingPrompt? =
    when {
        flags.samplesSkipped && !flags.samplesPromptDismissed -> OnboardingPrompt.AddSamples
        else -> null
    }
