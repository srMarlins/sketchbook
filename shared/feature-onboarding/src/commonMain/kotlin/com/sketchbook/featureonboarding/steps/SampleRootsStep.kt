package com.sketchbook.featureonboarding.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Onboarding step where the user adds zero or more sample folders. Samples are optional, so
 * Continue is always enabled and a Skip text-link sits next to it for users who don't keep
 * a managed sample library.
 *
 * @param paths Currently selected sample roots. Rendered as a stack of small paper cards.
 * @param onAddPath Called with the folder path the user chose via the picker.
 * @param onRemovePath Called when the user clicks the × on a row.
 * @param onPickFolder Native folder picker hook. Returns null if the user cancelled.
 * @param onContinue Advances the flow. Wired to `OnboardingIntent.Continue`.
 * @param onSkip Skips this step. Wired to `OnboardingIntent.Skip`.
 */
@Composable
fun SampleRootsStep(
    paths: List<String>,
    onAddPath: (String) -> Unit,
    onRemovePath: (String) -> Unit,
    onPickFolder: () -> String?,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
    ) {
        ProvideContentColor(colors.inkPrimary) {
            Text(
                text = "Sample folders?",
                style = AppTheme.typography.title,
            )
        }

        if (paths.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                for (path in paths) {
                    FolderRow(
                        path = path,
                        onRemove = { onRemovePath(path) },
                    )
                }
            }
        }

        Button(
            onClick = {
                val picked = onPickFolder()
                if (picked != null) onAddPath(picked)
            },
            variant = ButtonVariant.Secondary,
        ) {
            Text("+ Add folder")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Button(
                onClick = onContinue,
                variant = ButtonVariant.Primary,
            ) {
                Text("Continue")
            }
            // Text-link Skip — wrapped in a clickable Box with padding so the click target
            // isn't a one-pixel-tall string. Caption + inkMuted matches the footer's "Skip
            // all" affordance so the two read as the same kind of action.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.spacing.cornerInput))
                    .clickable(onClick = onSkip)
                    .padding(
                        horizontal = AppTheme.spacing.sm,
                        vertical = AppTheme.spacing.xs,
                    ),
            ) {
                ProvideContentColor(colors.inkMuted) {
                    Text(
                        text = "Skip",
                        style = AppTheme.typography.caption,
                    )
                }
            }
        }
    }
}
