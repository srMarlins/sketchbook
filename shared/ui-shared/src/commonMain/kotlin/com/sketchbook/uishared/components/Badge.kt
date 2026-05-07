package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.sketchbook.uishared.theme.AppTheme

/**
 * Small inline status indicator (e.g. `editing`, `auto-fork`, `held`). Color is the caller's
 * choice so domain semantics stay out of `ui-shared`.
 */
@Composable
fun Badge(
    color: Color,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xs),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(AppTheme.spacing.cornerSmall)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(color)
                .padding(padding),
    ) {
        ProvideContentColor(AppTheme.colors.inkOnStripDark) {
            content()
        }
    }
}
