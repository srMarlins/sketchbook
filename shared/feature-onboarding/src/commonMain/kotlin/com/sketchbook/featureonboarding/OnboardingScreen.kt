package com.sketchbook.featureonboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.uishared.components.PaperPage
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Onboarding flow scaffold. Renders the dot indicator + animated step area + footer skip
 * link. Per-step content is placeholder text for now — Tasks 10-12 swap each branch in
 * AnimatedContent for the real composables. Animations beyond the default cross-fade come
 * in Task 14.
 *
 * The skip link is hidden on Welcome (no work to skip yet) and Done (already at the
 * terminal screen) so it only appears on the actual setup steps.
 *
 * @param onPickFolder Native folder picker hook. Wired through here so per-step composables
 *   added in later tasks can request a folder without each one knowing about Swing/JFileChooser.
 *   Currently unused at this scaffold level.
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
    // onPickFolder is wired through to per-step composables in Task 11; onPickFile is reserved
    // for the future cloud sign-in step. Both are present in the public API now so callers
    // (Task 13) can hook the platform-specific pickers once and not need to revisit on each
    // step landing.
    val state by vm.state.collectAsStateWithLifecycle()
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
                DotIndicator(total = state.steps.size, currentIndex = state.currentIndex)

                AnimatedContent(
                    targetState = state.currentIndex,
                    transitionSpec = {
                        fadeIn(tween(280)) togetherWith fadeOut(tween(220))
                    },
                    label = "onboarding-step",
                ) { index ->
                    StepPlaceholder(state.steps[index])
                }

                FooterRow(
                    showSkipAll = shouldShowSkipAll(state),
                    onSkipAll = { vm.dispatch(OnboardingIntent.SkipAllUseDefaults) },
                )
            }
        }
    }
}

@Composable
private fun DotIndicator(total: Int, currentIndex: Int) {
    val colors = AppTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until total) {
            val color = if (i == currentIndex) colors.inkPrimary else colors.inkFaint
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun StepPlaceholder(step: OnboardingStep) {
    val label = when (step) {
        OnboardingStep.Welcome -> "Step: Welcome"
        OnboardingStep.ProjectsRoots -> "Step: Projects roots"
        OnboardingStep.SampleRoots -> "Step: Sample roots"
        OnboardingStep.PluginFolders -> "Step: Plugin folders"
        OnboardingStep.Done -> "Step: Done"
    }
    Text(label, style = AppTheme.typography.title)
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

private fun shouldShowSkipAll(state: OnboardingState): Boolean {
    if (state.steps.isEmpty()) return false
    val isWelcome = state.currentIndex == 0
    val isDone = state.currentIndex == state.steps.lastIndex
    return !isWelcome && !isDone
}
