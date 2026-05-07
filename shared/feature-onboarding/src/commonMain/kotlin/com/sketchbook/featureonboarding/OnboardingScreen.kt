package com.sketchbook.featureonboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.featureonboarding.anim.EmphasizedDecelerate
import com.sketchbook.featureonboarding.anim.InkDots
import com.sketchbook.featureonboarding.steps.DoneStep
import com.sketchbook.featureonboarding.steps.PluginFoldersStep
import com.sketchbook.featureonboarding.steps.ProjectsRootsStep
import com.sketchbook.featureonboarding.steps.SampleRootsStep
import com.sketchbook.featureonboarding.steps.WelcomeStep
import com.sketchbook.uishared.components.BulkUndoSnackbar
import com.sketchbook.uishared.components.BulkUndoSnackbarState
import com.sketchbook.uishared.components.PaperPage
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Onboarding flow scaffold. Renders the dot indicator + animated step area + footer skip
 * link. Each branch in AnimatedContent now dispatches its own intents to the VM; richer
 * animations beyond the default cross-fade come in Task 14.
 *
 * The footer "Skip all" link is hidden on Welcome (no work to skip yet) and Done (already
 * at the terminal screen) so it only appears on the actual setup steps.
 *
 * @param onPickFolder Native folder picker hook. Wired through to the per-step composables
 *   so each one can request a folder without knowing about Swing/JFileChooser.
 * @param onPickFile Native file picker hook. Reserved for the future cloud sign-in step
 *   (service-account JSON). Currently unused.
 */
@Suppress("UnusedParameter")
@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel,
    onPickFolder: () -> String?,
    onPickFile: () -> String?,
    modifier: Modifier = Modifier,
) {
    // onPickFolder is wired through to the Projects/Samples/Plugins step composables;
    // onPickFile is reserved for the future cloud sign-in step. Both are present in the
    // public API now so callers (Task 13) can hook the platform-specific pickers once and
    // not need to revisit on each step landing.
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = rememberPersistenceFailureSnackbar(vm)
    PaperPage(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(PaddingValues(horizontal = AppTheme.spacing.xl, vertical = AppTheme.spacing.xl)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xl),
            ) {
                InkDots(count = state.steps.size, currentIndex = state.currentIndex)

                // Resolve 16dp → px once per recomposition; the AnimatedContent transitionSpec
                // captures it as an Int so the per-transition lambda doesn't need ambient density.
                val slidePx = with(LocalDensity.current) { 16.dp.toPx().toInt() }
                AnimatedContent(
                    targetState = state.currentIndex,
                    transitionSpec = {
                        // Page-turn accent: 16dp horizontal slide layered onto the existing
                        // fade. Forward-only (onboarding has no back button) so we always
                        // slide-from-right; if a back-step is added later, branch on
                        // `targetState > initialState`.
                        val enterFade = fadeIn(tween(280, easing = EmphasizedDecelerate))
                        val exitFade = fadeOut(tween(220))
                        val enterSlide = slideInHorizontally(
                            animationSpec = tween(280, easing = EmphasizedDecelerate),
                        ) { slidePx }
                        val exitSlide = slideOutHorizontally(
                            animationSpec = tween(220),
                        ) { -slidePx }
                        (enterFade + enterSlide) togetherWith (exitFade + exitSlide)
                    },
                    label = "onboarding-step",
                ) { index ->
                    when (state.steps[index]) {
                        OnboardingStep.Welcome ->
                            WelcomeStep(onContinue = { vm.dispatch(OnboardingIntent.Continue) })

                        OnboardingStep.Done ->
                            DoneStep(onFinish = { vm.dispatch(OnboardingIntent.Finish) })

                        OnboardingStep.ProjectsRoots -> ProjectsRootsStep(
                            paths = state.projectsRoots,
                            osDefaultSuggestion = remember { defaultProjectsRootSuggestion() },
                            onAddPath = { vm.dispatch(OnboardingIntent.AddProjectsRoot(it)) },
                            onRemovePath = { vm.dispatch(OnboardingIntent.RemoveProjectsRoot(it)) },
                            onPickFolder = onPickFolder,
                            onContinue = { vm.dispatch(OnboardingIntent.Continue) },
                            canContinue = state.canContinue,
                        )

                        OnboardingStep.SampleRoots -> SampleRootsStep(
                            paths = state.sampleRoots,
                            onAddPath = { vm.dispatch(OnboardingIntent.AddSampleRoot(it)) },
                            onRemovePath = { vm.dispatch(OnboardingIntent.RemoveSampleRoot(it)) },
                            onPickFolder = onPickFolder,
                            onContinue = { vm.dispatch(OnboardingIntent.Continue) },
                            onSkip = { vm.dispatch(OnboardingIntent.Skip) },
                        )

                        OnboardingStep.PluginFolders -> PluginFoldersStep(
                            paths = state.pluginFolders,
                            onAddPath = { vm.dispatch(OnboardingIntent.AddPluginFolder(it)) },
                            onRemovePath = { vm.dispatch(OnboardingIntent.RemovePluginFolder(it)) },
                            onUseDefaults = { vm.dispatch(OnboardingIntent.UsePluginDefaults) },
                            onPickFolder = onPickFolder,
                            onContinue = { vm.dispatch(OnboardingIntent.Continue) },
                            onSkip = { vm.dispatch(OnboardingIntent.Skip) },
                        )
                    }
                }

                FooterRow(
                    showSkipAll = shouldShowSkipAll(state),
                    onSkipAll = { vm.dispatch(OnboardingIntent.SkipAllUseDefaults) },
                )

                BulkUndoSnackbar(state = snackbar)
            }
        }
    }
}

@Composable
private fun FooterRow(showSkipAll: Boolean, onSkipAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSkipAll) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.spacing.cornerInput))
                    .clickable(onClick = onSkipAll)
                    .padding(
                        horizontal = AppTheme.spacing.sm,
                        vertical = AppTheme.spacing.xs,
                    ),
            ) {
                Text(
                    "Skip all and use defaults",
                    style = AppTheme.typography.caption,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Right slot reserved for the version string (later task).
    }
}

/**
 * Wires the VM's [OnboardingViewModel.events] into a 5s info-only snackbar. Surfacing
 * persistence failures (rather than swallowing them in `runCatching`) is what the design
 * doc means by "non-blocking toast". `BulkUndoSnackbarState` reused with `onUndo = null`
 * because the Finish unwind is one-shot — the user has already moved on.
 */
@Composable
private fun rememberPersistenceFailureSnackbar(vm: OnboardingViewModel): BulkUndoSnackbarState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { BulkUndoSnackbarState(scope) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is OnboardingEvent.PersistenceFailed -> state.show(event.message)
            }
        }
    }
    return state
}

private fun shouldShowSkipAll(state: OnboardingState): Boolean {
    if (state.steps.isEmpty()) return false
    val isWelcome = state.currentIndex == 0
    val isDone = state.currentIndex == state.steps.lastIndex
    return !isWelcome && !isDone
}
