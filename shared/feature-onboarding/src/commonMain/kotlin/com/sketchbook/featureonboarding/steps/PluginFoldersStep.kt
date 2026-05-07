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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.sketchbook.featureonboarding.anim.TypingHeading
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Onboarding step for plugin scan folders. The VM pre-fills [paths] with `defaultPluginFolders()`
 * so the user lands on the OS conventions and only needs to add/remove if their setup is
 * non-standard. A "Use defaults" text-link resets the list to the OS defaults if the user has
 * cleared or edited it and wants to start over.
 *
 * Continue is always enabled (plugin folders are optional — if the list ends up empty, the
 * project health probe simply reports nothing). A Skip text-link sits next to Continue for
 * users who explicitly want to leave plugin tracking off.
 *
 * @param paths Plugin folders, pre-filled by the VM with OS defaults on entry.
 * @param onAddPath Called with the folder path the user chose via the picker.
 * @param onRemovePath Called when the user clicks the × on a row.
 * @param onUseDefaults Resets [paths] to the OS defaults. Wired to `OnboardingIntent.UsePluginDefaults`.
 * @param onPickFolder Native folder picker hook. Returns null if the user cancelled.
 * @param onContinue Advances the flow. Wired to `OnboardingIntent.Continue`.
 * @param onSkip Skips this step. Wired to `OnboardingIntent.Skip`.
 */
@Composable
fun PluginFoldersStep(
    paths: List<String>,
    onAddPath: (String) -> Unit,
    onRemovePath: (String) -> Unit,
    onUseDefaults: () -> Unit,
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
        TypingHeading(
            text = "Plugin folders.",
            style = AppTheme.typography.title,
            color = colors.inkPrimary,
        )
        ProvideContentColor(colors.inkMuted) {
            Text(
                text = "Used to flag projects with missing plugins.",
                style = AppTheme.typography.body,
            )
        }

        if (paths.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                for (path in paths) {
                    key(path) {
                        FolderRow(
                            path = path,
                            onRemove = { onRemovePath(path) },
                        )
                    }
                }
            }
        }

        // Add folder + Use defaults sit on the same row: Add is the primary affordance,
        // Use defaults is a small caption-styled tertiary action that resets the list. Putting
        // them adjacent makes it obvious they're both manipulating the same list.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            AddFolderButton(
                onPickFolder = onPickFolder,
                onAddPath = onAddPath,
                inkColor = colors.inkPrimary,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.spacing.cornerInput))
                    .clickable(onClick = onUseDefaults)
                    .padding(
                        horizontal = AppTheme.spacing.sm,
                        vertical = AppTheme.spacing.xs,
                    ),
            ) {
                ProvideContentColor(colors.inkMuted) {
                    Text(
                        text = "Use defaults",
                        style = AppTheme.typography.caption,
                    )
                }
            }
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
