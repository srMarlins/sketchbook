package com.sketchbook.featureonboarding.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.sketchbook.featureonboarding.anim.StampCard
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * One folder row used by the onboarding step composables (Projects, Samples, Plugins). Mirrors
 * the `LibraryRootCard` shape from `SettingsScreen` (paper surface + path text + trailing remove
 * glyph) without extracting that helper to ui-shared — the settings card carries Badge + alias
 * affordances we don't need here, and pulling it out is out of scope.
 *
 * File-internal so each step shares the same pattern without each pasting the same 30 lines.
 */
@Composable
internal fun FolderRow(path: String, onRemove: () -> Unit) {
    val colors = AppTheme.colors
    // Stamp animation: scale 0.92 → 1.0 + 180ms fade. Wrapped here (not at the callsite)
    // so every step gets it for free. Each row is keyed by `path` upstream via the
    // for-loop's source list — Compose's structural identity gives a fresh StampCard
    // for each new folder, which is exactly what we want.
    StampCard(modifier = Modifier.fillMaxWidth()) {
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
}
