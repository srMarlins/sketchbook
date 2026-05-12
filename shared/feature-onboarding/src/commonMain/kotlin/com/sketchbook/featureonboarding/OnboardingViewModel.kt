package com.sketchbook.featureonboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.AppScope
import com.sketchbook.core.runCatchingCancellable
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Onboarding flow state model.
 *
 * The flow is list-driven so adding a future cloud step (v1.2) is a single new entry in
 * [OnboardingState.steps], not a refactor. Skip semantics are computed from the current
 * step's identity, not from a parallel boolean.
 *
 * Task 6 implements state + transitions + add/remove intents. [OnboardingIntent.Finish]
 * and [OnboardingIntent.SkipAllUseDefaults] are intentional stubs filled in by Task 7.
 */
data class OnboardingState(
    val steps: List<OnboardingStep>,
    val currentIndex: Int,
    val projectsRoots: List<String>,
    val sampleRoots: List<String>,
    val pluginFolders: List<String>,
    val canContinue: Boolean,
)

sealed interface OnboardingStep {
    data object Welcome : OnboardingStep

    data object ProjectsRoots : OnboardingStep

    data object SampleRoots : OnboardingStep

    data object PluginFolders : OnboardingStep

    data object Done : OnboardingStep
    // Future: data object CloudSignIn — drop in when v1.2 cloud lands.
}

/**
 * One-shot side effects emitted by [OnboardingViewModel.events] for the screen to render
 * transiently (e.g. a snackbar). Distinct from [OnboardingState] which is the persistent
 * UI model.
 */
sealed interface OnboardingEvent {
    data class PersistenceFailed(
        val message: String,
    ) : OnboardingEvent
}

sealed interface OnboardingIntent {
    data class AddProjectsRoot(
        val path: String,
    ) : OnboardingIntent

    data class RemoveProjectsRoot(
        val path: String,
    ) : OnboardingIntent

    data class AddSampleRoot(
        val path: String,
    ) : OnboardingIntent

    data class RemoveSampleRoot(
        val path: String,
    ) : OnboardingIntent

    data class AddPluginFolder(
        val path: String,
    ) : OnboardingIntent

    data class RemovePluginFolder(
        val path: String,
    ) : OnboardingIntent

    data object UsePluginDefaults : OnboardingIntent

    data object Continue : OnboardingIntent

    data object Skip : OnboardingIntent

    data object SkipAllUseDefaults : OnboardingIntent

    data object Finish : OnboardingIntent
}

/**
 * Side-effect hook the VM calls after onboarding completes (Task 7). Pulled behind an
 * interface so commonTest can supply a fake without dragging in the desktop scan engine.
 */
interface ScanTrigger {
    fun triggerScan()
}

@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class OnboardingViewModel(
    private val repository: SettingsRepository,
    private val scanTrigger: ScanTrigger,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /**
     * One-shot side-effect channel for non-blocking failure surfacing during [finish].
     * The plan calls for a toast on persistence failure — the rest of the unwind still runs,
     * but the user is told something didn't stick. Replay = 0 (events are observed live);
     * extraBufferCapacity = 4 (one per persistence call) with [BufferOverflow.DROP_OLDEST]
     * so a slow collector can't backpressure the unwind.
     */
    private val _events =
        MutableSharedFlow<OnboardingEvent>(
            replay = 0,
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: Flow<OnboardingEvent> = _events.asSharedFlow()

    /**
     * Guards [OnboardingIntent.Finish] from running twice. The UI should disable the "Open
     * Sketchbook" button after the first click, but the model is idempotent regardless so a
     * stray double-dispatch can't double-scan or double-mark-complete.
     */
    private var finished = false

    fun dispatch(intent: OnboardingIntent) {
        when (intent) {
            is OnboardingIntent.AddProjectsRoot -> {
                _state.update { s ->
                    s.copy(projectsRoots = (s.projectsRoots + intent.path).distinct()).recomputeCanContinue()
                }
            }

            is OnboardingIntent.RemoveProjectsRoot -> {
                _state.update { s ->
                    s.copy(projectsRoots = s.projectsRoots - intent.path).recomputeCanContinue()
                }
            }

            is OnboardingIntent.AddSampleRoot -> {
                _state.update { s ->
                    s.copy(sampleRoots = (s.sampleRoots + intent.path).distinct())
                }
            }

            is OnboardingIntent.RemoveSampleRoot -> {
                _state.update { s ->
                    s.copy(sampleRoots = s.sampleRoots - intent.path)
                }
            }

            is OnboardingIntent.AddPluginFolder -> {
                _state.update { s ->
                    s.copy(pluginFolders = (s.pluginFolders + intent.path).distinct())
                }
            }

            is OnboardingIntent.RemovePluginFolder -> {
                _state.update { s ->
                    s.copy(pluginFolders = s.pluginFolders - intent.path)
                }
            }

            OnboardingIntent.UsePluginDefaults -> {
                _state.update {
                    it.copy(pluginFolders = defaultPluginFolders())
                }
            }

            OnboardingIntent.Continue -> {
                advance()
            }

            OnboardingIntent.Skip -> {
                skip()
            }

            OnboardingIntent.SkipAllUseDefaults -> {
                _state.update { s ->
                    // SkipAll is a *navigation* shortcut — preserve every entered list and only fill
                    // pluginFolders with OS defaults if the user emptied them. Persistence happens in
                    // Finish, not here. The user can still review on Done before committing.
                    val pluginFolders = if (s.pluginFolders.isEmpty()) defaultPluginFolders() else s.pluginFolders
                    s
                        .copy(
                            pluginFolders = pluginFolders,
                            currentIndex = s.steps.indexOf(OnboardingStep.Done),
                        ).recomputeCanContinue()
                }
            }

            OnboardingIntent.Finish -> {
                finish()
            }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        val s = _state.value
        viewModelScope.launch {
            // Fail-soft: each persistence call is wrapped so a single store failure can't block the
            // rest. The user has already committed mentally by tapping "Open Sketchbook" — the UI
            // is unwinding, not pausing for retry. Failures are emitted on [events] so the
            // screen can surface a non-blocking toast instead of swallowing them silently.
            s.projectsRoots.forEach { path ->
                runCatchingCancellable { repository.upsertRoot(LibraryRoot.Projects(path)) }
                    .onFailure { emitFailure("Couldn't save Projects folder: $path") }
            }
            s.sampleRoots.forEach { path ->
                runCatchingCancellable { repository.upsertRoot(LibraryRoot.UserSamples(path)) }
                    .onFailure { emitFailure("Couldn't save Samples folder: $path") }
            }
            runCatchingCancellable { repository.setPluginFolders(s.pluginFolders) }
                .onFailure { emitFailure("Couldn't save plugin folders") }
            runCatchingCancellable {
                repository.markFirstRunComplete(
                    OnboardingSkipFlags(samplesSkipped = s.sampleRoots.isEmpty()),
                )
            }.onFailure { emitFailure("Couldn't mark setup complete — Sketchbook may show this again on next launch") }
            // Scan kick-off goes last so it observes the just-flushed roots.
            scanTrigger.triggerScan()
        }
    }

    private fun emitFailure(message: String) {
        // tryEmit because we configured the SharedFlow with a buffer + DROP_OLDEST; this never
        // suspends and never throws, even if no one is collecting yet.
        _events.tryEmit(OnboardingEvent.PersistenceFailed(message))
    }

    private fun advance() =
        _state.update { s ->
            if (s.currentIndex >= s.steps.lastIndex) {
                s
            } else {
                s.copy(currentIndex = s.currentIndex + 1).recomputeCanContinue()
            }
        }

    private fun skip() =
        _state.update { s ->
            // Welcome (index 0), ProjectsRoots (required), and Done (terminal) are not skippable.
            when (s.steps[s.currentIndex]) {
                OnboardingStep.Welcome,
                OnboardingStep.ProjectsRoots,
                OnboardingStep.Done,
                -> s

                OnboardingStep.SampleRoots,
                OnboardingStep.PluginFolders,
                -> s.copy(currentIndex = s.currentIndex + 1).recomputeCanContinue()
            }
        }

    private fun initialState(): OnboardingState {
        val steps =
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.ProjectsRoots,
                OnboardingStep.SampleRoots,
                OnboardingStep.PluginFolders,
                OnboardingStep.Done,
            )
        return OnboardingState(
            steps = steps,
            currentIndex = 0,
            projectsRoots = emptyList(),
            sampleRoots = emptyList(),
            pluginFolders = defaultPluginFolders(),
            canContinue = true, // Welcome is always continuable.
        )
    }

    private fun OnboardingState.recomputeCanContinue(): OnboardingState =
        copy(
            canContinue =
                when (steps[currentIndex]) {
                    OnboardingStep.ProjectsRoots -> projectsRoots.isNotEmpty()
                    else -> true
                },
        )
}
