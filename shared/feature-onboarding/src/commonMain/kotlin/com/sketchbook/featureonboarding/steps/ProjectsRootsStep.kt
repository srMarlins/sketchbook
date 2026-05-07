package com.sketchbook.featureonboarding.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.sketchbook.featureonboarding.anim.TypingHeading
import com.sketchbook.featureonboarding.anim.inkUnderline
import com.sketchbook.featureonboarding.anim.rememberInkUnderlineActive
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
        TypingHeading(
            text = "Where are your Ableton projects?",
            style = AppTheme.typography.title,
            color = colors.inkPrimary,
        )
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
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(AppTheme.spacing.cornerInput))
                        .clickable { onAddPath(osDefaultSuggestion) },
            ) {
                Surface(
                    color = colors.tintCream,
                    padding =
                        PaddingValues(
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
                    // Key by path so each row's StampCard plays its scale+fade animation
                    // exactly once when the path is first added, and not when an unrelated
                    // path is removed (which would shift positional indices).
                    key(path) {
                        FolderRow(
                            path = path,
                            onRemove = { onRemovePath(path) },
                        )
                    }
                }
            }
        }

        AddFolderButton(
            onPickFolder = onPickFolder,
            onAddPath = onAddPath,
            inkColor = colors.inkPrimary,
        )

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
 * `+ Add folder` button wrapped in a hoverable Box so we can paint the ink-underline
 * accent on hover/focus. Done as a file-local helper so the three step composables can
 * share the same wiring without each one repeating the InteractionSource plumbing.
 *
 * The underline tracks the wrapper Box (which is the same width as the button's clickable
 * area) and reads hover via a `MutableInteractionSource` driven by `Modifier.hoverable`.
 */
@Composable
internal fun AddFolderButton(
    onPickFolder: () -> String?,
    onAddPath: (String) -> Unit,
    inkColor: androidx.compose.ui.graphics.Color,
) {
    val source = remember { MutableInteractionSource() }
    val active = rememberInkUnderlineActive(source)
    Box(
        modifier =
            Modifier
                .hoverable(source)
                .inkUnderline(active = active, color = inkColor),
    ) {
        Button(
            onClick = {
                val picked = onPickFolder()
                if (picked != null) onAddPath(picked)
            },
            variant = ButtonVariant.Secondary,
        ) {
            Text("+ Add folder")
        }
    }
}
