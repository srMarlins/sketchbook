package com.sketchbook.featureonboarding

import androidx.lifecycle.ViewModel
import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

sealed interface OnboardingIntent {
    data class AddProjectsRoot(val path: String) : OnboardingIntent
    data class RemoveProjectsRoot(val path: String) : OnboardingIntent
    data class AddSampleRoot(val path: String) : OnboardingIntent
    data class RemoveSampleRoot(val path: String) : OnboardingIntent
    data class AddPluginFolder(val path: String) : OnboardingIntent
    data class RemovePluginFolder(val path: String) : OnboardingIntent
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

@Inject
class OnboardingViewModel(
    @Suppress("UnusedPrivateProperty") private val repository: SettingsRepository,
    @Suppress("UnusedPrivateProperty") private val scanTrigger: ScanTrigger,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun dispatch(intent: OnboardingIntent) {
        when (intent) {
            is OnboardingIntent.AddProjectsRoot -> _state.update { s ->
                s.copy(projectsRoots = (s.projectsRoots + intent.path).distinct()).recomputeCanContinue()
            }
            is OnboardingIntent.RemoveProjectsRoot -> _state.update { s ->
                s.copy(projectsRoots = s.projectsRoots - intent.path).recomputeCanContinue()
            }
            is OnboardingIntent.AddSampleRoot -> _state.update { s ->
                s.copy(sampleRoots = (s.sampleRoots + intent.path).distinct())
            }
            is OnboardingIntent.RemoveSampleRoot -> _state.update { s ->
                s.copy(sampleRoots = s.sampleRoots - intent.path)
            }
            is OnboardingIntent.AddPluginFolder -> _state.update { s ->
                s.copy(pluginFolders = (s.pluginFolders + intent.path).distinct())
            }
            is OnboardingIntent.RemovePluginFolder -> _state.update { s ->
                s.copy(pluginFolders = s.pluginFolders - intent.path)
            }
            OnboardingIntent.UsePluginDefaults -> _state.update {
                it.copy(pluginFolders = defaultPluginFolders())
            }
            OnboardingIntent.Continue -> advance()
            OnboardingIntent.Skip -> skip()
            OnboardingIntent.SkipAllUseDefaults -> {
                // Stub for Task 7 (jumps to Done preserving state, persists, triggers scan).
            }
            OnboardingIntent.Finish -> {
                // Stub for Task 7 (persists everything + triggers scan).
            }
        }
    }

    private fun advance() = _state.update { s ->
        if (s.currentIndex >= s.steps.lastIndex) s
        else s.copy(currentIndex = s.currentIndex + 1).recomputeCanContinue()
    }

    private fun skip() = _state.update { s ->
        // Welcome (index 0), ProjectsRoots (required), and Done (terminal) are not skippable.
        when (s.steps[s.currentIndex]) {
            OnboardingStep.Welcome,
            OnboardingStep.ProjectsRoots,
            OnboardingStep.Done -> s

            OnboardingStep.SampleRoots,
            OnboardingStep.PluginFolders -> s.copy(currentIndex = s.currentIndex + 1).recomputeCanContinue()
        }
    }

    private fun initialState(): OnboardingState {
        val steps = listOf(
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
            canContinue = when (steps[currentIndex]) {
                OnboardingStep.ProjectsRoots -> projectsRoots.isNotEmpty()
                else -> true
            },
        )
}
