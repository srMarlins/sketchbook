package com.sketchbook.featureonboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.AppScope
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class OnboardingViewModel(private val repository: SettingsRepository, private val scanTrigger: ScanTrigger) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /**
     * Guards [OnboardingIntent.Finish] from running twice. The UI should disable the "Open
     * Sketchbook" button after the first click, but the model is idempotent regardless so a
     * stray double-dispatch can't double-scan or double-mark-complete.
     */
    private var finished = false

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

            OnboardingIntent.SkipAllUseDefaults -> _state.update { s ->
                // SkipAll is a *navigation* shortcut — preserve every entered list and only fill
                // pluginFolders with OS defaults if the user emptied them. Persistence happens in
                // Finish, not here. The user can still review on Done before committing.
                val pluginFolders = if (s.pluginFolders.isEmpty()) defaultPluginFolders() else s.pluginFolders
                s.copy(
                    pluginFolders = pluginFolders,
                    currentIndex = s.steps.indexOf(OnboardingStep.Done),
                ).recomputeCanContinue()
            }

            OnboardingIntent.Finish -> finish()
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        val s = _state.value
        viewModelScope.launch {
            // Fail-soft: each persistence call is wrapped so a single store failure can't block the
            // rest. The user has already committed mentally by tapping "Open Sketchbook" — the UI
            // is unwinding, not pausing for retry.
            s.projectsRoots.forEach { path ->
                runCatching { repository.upsertRoot(LibraryRoot.Projects(path)) }
            }
            s.sampleRoots.forEach { path ->
                runCatching { repository.upsertRoot(LibraryRoot.UserSamples(path)) }
            }
            runCatching { repository.setPluginFolders(s.pluginFolders) }
            runCatching {
                repository.markFirstRunComplete(
                    OnboardingSkipFlags(samplesSkipped = s.sampleRoots.isEmpty()),
                )
            }
            // Scan kick-off goes last so it observes the just-flushed roots.
            scanTrigger.triggerScan()
        }
    }

    private fun advance() = _state.update { s ->
        if (s.currentIndex >= s.steps.lastIndex) {
            s
        } else {
            s.copy(currentIndex = s.currentIndex + 1).recomputeCanContinue()
        }
    }

    private fun skip() = _state.update { s ->
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

    private fun OnboardingState.recomputeCanContinue(): OnboardingState = copy(
        canContinue = when (steps[currentIndex]) {
            OnboardingStep.ProjectsRoots -> projectsRoots.isNotEmpty()
            else -> true
        },
    )
}
