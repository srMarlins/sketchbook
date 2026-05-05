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
 * Paper-card surface. Defaults to the page-paper color; pass another [color] to render a kraft
 * panel, corkboard, etc. Uses small-corner rounding so cards still read as paper.
 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.surfacePage,
    padding: PaddingValues = PaddingValues(AppTheme.spacing.md),
    content: @Composable () -> Unit,
) {
    val corner = RoundedCornerShape(AppTheme.spacing.cornerSmall)
    Box(
        modifier = modifier
            .clip(corner)
            .background(color)
            .padding(padding),
    ) {
        content()
    }
}
