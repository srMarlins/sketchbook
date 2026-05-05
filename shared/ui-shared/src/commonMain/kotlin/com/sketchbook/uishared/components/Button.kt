package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.sketchbook.uishared.theme.AppTheme
import com.sketchbook.uishared.theme.LocalAppTypography

/** Visual variant for [Button]. */
enum class ButtonVariant { Primary, Secondary, Ghost }

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val (bg, fg) = when (variant) {
        ButtonVariant.Primary -> colors.accentAction to colors.surfacePage
        ButtonVariant.Secondary -> colors.surfacePanel to colors.inkPrimary
        ButtonVariant.Ghost -> Color.Transparent to colors.inkPrimary
    }
    val effectiveBg = if (enabled) bg else bg.copy(alpha = 0.4f)
    val effectiveFg = if (enabled) fg else fg.copy(alpha = 0.4f)
    val shape = RoundedCornerShape(AppTheme.spacing.cornerSmall)
    Row(
        modifier = modifier
            .clip(shape)
            .background(effectiveBg)
            .clickable(enabled = enabled) { onClick() }
            .padding(PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        if (leading != null) leading()
        // Slot uses bodyEmphasis with the resolved foreground color.
        CompositionLocalProvider(LocalAppTypography provides AppTheme.typography) {
            ProvideContentColor(effectiveFg) { content() }
        }
        if (trailing != null) trailing()
    }
}
