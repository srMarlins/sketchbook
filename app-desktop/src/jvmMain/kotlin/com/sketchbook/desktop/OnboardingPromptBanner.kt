package com.sketchbook.desktop

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
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Soft re-prompt banner shown above the projects list on Home when the user deferred a step
 * during onboarding. Looks like a small `tintCream` paper card — same visual language as
 * `FolderRow` and the home dashboard cards. Clicking the body navigates to Settings; the
 * trailing `×` glyph sticky-dismisses the prompt without navigating.
 *
 * Copy is fixed per [prompt] variant and intentionally short — banners must fit on a single
 * line on a normal-width window.
 */
@Composable
fun OnboardingPromptBanner(
    prompt: OnboardingPrompt,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val copy = when (prompt) {
        OnboardingPrompt.AddSamples ->
            "Add a samples folder later? Powers the missing-files finder."
    }
    Surface(
        color = colors.tintCream,
        padding = PaddingValues(AppTheme.spacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            ProvideContentColor(colors.inkPrimary) {
                Text(
                    text = copy,
                    style = AppTheme.typography.body,
                    modifier = Modifier.weight(1f),
                )
            }
            // Glyph-as-icon close affordance — same pattern as FolderRow's trailing ×.
            // Wrapped in its own clickable Box so taps on the × don't bubble up to the row's
            // navigate-to-Settings handler.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.spacing.cornerInput))
                    .clickable(onClick = onDismiss)
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
