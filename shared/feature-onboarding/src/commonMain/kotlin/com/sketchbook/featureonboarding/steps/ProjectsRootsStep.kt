package com.sketchbook.featureonboarding.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Onboarding step where the user picks one or more Projects roots — the only required
 * library input in the flow. The Continue button stays disabled until [paths] is non-empty;
 * that guard lives in the VM via `canContinue`, this composable just reflects it.
 *
 * The composable is callback-only so it stays trivial to unit-test and preview without a VM.
 * Display shortening (e.g. replacing `$HOME` with `~`) is intentionally omitted: it would
 * require JVM-only `System.getProperty`, and the suggestions returned by
 * `defaultProjectsRootSuggestion()` are short enough to render verbatim across platforms.
 *
 * @param paths Currently selected Projects roots. Rendered as a stack of small paper cards.
 * @param osDefaultSuggestion OS-specific suggested folder, or null on platforms with no
 *   convention. The chip is hidden when null or already in [paths].
 * @param onAddPath Called with the folder path the user chose (chip click or picker).
 * @param onRemovePath Called when the user clicks the × on a row.
 * @param onPickFolder Native folder picker hook. Returns null if the user cancelled.
 * @param onContinue Advances the flow. Should be wired to `OnboardingIntent.Continue`.
 * @param canContinue Mirrors `OnboardingState.canContinue`; true once [paths] is non-empty.
 */
@Composable
fun ProjectsRootsStep(
    paths: List<String>,
    osDefaultSuggestion: String?,
    onAddPath: (String) -> Unit,
    onRemovePath: (String) -> Unit,
    onPickFolder: () -> String?,
    onContinue: () -> Unit,
    canContinue: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
    ) {
        ProvideContentColor(colors.inkPrimary) {
            Text(
                text = "Where are your Ableton projects?",
                style = AppTheme.typography.title,
            )
        }
        ProvideContentColor(colors.inkMuted) {
            Text(
                text = "Add one folder or several.",
                style = AppTheme.typography.body,
            )
        }

        if (osDefaultSuggestion != null && osDefaultSuggestion !in paths) {
            // Small clickable pill that drops the OS default into the list with a single tap.
            // Shown only when not already added so it can't duplicate an existing row.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.spacing.cornerInput))
                    .clickable { onAddPath(osDefaultSuggestion) },
            ) {
                Surface(
                    color = colors.tintCream,
                    padding = PaddingValues(
                        horizontal = AppTheme.spacing.sm,
                        vertical = AppTheme.spacing.xs,
                    ),
                ) {
                    Text(
                        text = "+ Use $osDefaultSuggestion",
                        style = AppTheme.typography.caption,
                    )
                }
            }
        }

        if (paths.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                for (path in paths) {
                    ProjectsRootRow(
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

        Button(
            onClick = onContinue,
            variant = ButtonVariant.Primary,
            enabled = canContinue,
        ) {
            Text("Continue")
        }
    }
}

/**
 * One Projects-root row. Mirrors the `LibraryRootCard` shape from `SettingsScreen` (paper
 * surface + path text + trailing remove glyph) without extracting that helper to ui-shared —
 * the settings card carries Badge + alias affordances we don't need here, and pulling it out
 * is out of scope for this task.
 */
@Composable
private fun ProjectsRootRow(path: String, onRemove: () -> Unit) {
    val colors = AppTheme.colors
    Surface(
        color = colors.tintCream,
        padding = PaddingValues(AppTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            ProvideContentColor(colors.inkPrimary) {
                Text(
                    text = path,
                    style = AppTheme.typography.body,
                    modifier = Modifier.weight(1f),
                )
            }
            // Glyph-as-icon — RootContent uses the same pattern; we don't have a real icon
            // system in ui-shared yet, and pulling one in for a single × is overkill.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.spacing.cornerInput))
                    .clickable(onClick = onRemove)
                    .padding(
                        horizontal = AppTheme.spacing.sm,
                        vertical = AppTheme.spacing.xs,
                    ),
            ) {
                ProvideContentColor(colors.inkMuted) {
                    Text(
                        text = "×",
                        style = AppTheme.typography.title,
                    )
                }
            }
        }
    }
}
