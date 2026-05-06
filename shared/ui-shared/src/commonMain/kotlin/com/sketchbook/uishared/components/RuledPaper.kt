package com.sketchbook.uishared.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Modifier extension that paints horizontal blue ruling and (optionally) the red vertical
 * margin behind a column of content — the same gesture as a notebook page.
 *
 * Lines render at [lineHeight] intervals (defaults to the theme's `ruleLineHeight`, ~28dp,
 * matching `bodyEmphasis` line-height + breathing). Margin is the terracotta vertical line
 * drawn at [marginX] from the left.
 */
fun Modifier.ruledPaper(
    showRules: Boolean = true,
    showMargin: Boolean = true,
    lineHeight: Dp? = null,
    marginX: Dp = 36.dp,
): Modifier = composed {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val resolvedLineHeight = lineHeight ?: AppTheme.spacing.ruleLineHeight
    val rule = colors.ruleBlue.copy(alpha = if (colors.isDark) 0.18f else 0.55f)
    val margin = colors.ruleMargin.copy(alpha = if (colors.isDark) 0.45f else 0.65f)
    val lineHeightPx = with(density) { resolvedLineHeight.toPx() }
    val marginPx = with(density) { marginX.toPx() }
    drawBehind {
        if (showRules) {
            var y = lineHeightPx
            while (y < size.height) {
                drawLine(
                    color = rule,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += lineHeightPx
            }
        }
        if (showMargin) {
            drawLine(
                color = margin,
                start = Offset(marginPx, 0f),
                end = Offset(marginPx, size.height),
                strokeWidth = 1.5f,
            )
        }
    }
}
