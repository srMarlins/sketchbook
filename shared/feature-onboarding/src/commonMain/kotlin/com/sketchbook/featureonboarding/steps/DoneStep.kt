package com.sketchbook.featureonboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.InkLoading
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Terminal onboarding step. Tells the user scanning is starting and offers the "Open
 * Sketchbook" CTA. `InkLoading` doesn't take a label, so we put the "Reading your library…"
 * copy in a sibling `Text` to its right.
 */
@Composable
fun DoneStep(
    onFinish: () -> Unit,
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
                text = "Done.",
                style = AppTheme.typography.title,
            )
        }
        ProvideContentColor(colors.inkMuted) {
            Text(
                text = "Scanning starts now — you can use Sketchbook while it runs.",
                style = AppTheme.typography.body,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            InkLoading()
            ProvideContentColor(colors.inkMuted) {
                Text(
                    text = "Reading your library…",
                    style = AppTheme.typography.body,
                )
            }
        }
        Button(
            onClick = onFinish,
            variant = ButtonVariant.Primary,
        ) {
            Text("Open Sketchbook")
        }
    }
}
