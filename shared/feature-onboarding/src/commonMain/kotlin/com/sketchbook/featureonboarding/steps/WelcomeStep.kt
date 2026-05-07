package com.sketchbook.featureonboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * First onboarding step. Just a heading, a calm subhead, and the "Get started" CTA.
 * The VM dispatch happens in `OnboardingScreen`; this composable only takes a callback
 * so it stays trivial to preview/test.
 */
@Composable
fun WelcomeStep(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
    ) {
        ProvideContentColor(colors.inkPrimary) {
            Text(
                text = "Welcome to Sketchbook.",
                style = AppTheme.typography.title,
            )
        }
        ProvideContentColor(colors.inkMuted) {
            Text(
                text = "Point it at your library and it'll do the rest. Takes a minute.",
                style = AppTheme.typography.body,
            )
        }
        Button(
            onClick = onContinue,
            variant = ButtonVariant.Primary,
        ) {
            Text("Get started")
        }
    }
}
