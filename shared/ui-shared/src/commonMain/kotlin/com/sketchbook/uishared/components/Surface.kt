package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Paper-card surface. Layered edges so the card reads as a real piece of paper:
 *
 *  1. Soft warm drop-shadow underneath (when [elevation] > 0).
 *  2. A 1px hairline border at the bottom and right, slightly darker — the "drawn" edge.
 *  3. A 1px highlight at the top, slightly lighter — sunlight catching the top edge.
 *  4. Background fill.
 *
 * The combination is what differentiates a paper-feel card from a flat material rectangle —
 * each individual layer is subtle but together they read as a real edge.
 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.surfaceCard,
    padding: PaddingValues = PaddingValues(AppTheme.spacing.md),
    elevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val cornerDp = AppTheme.spacing.cornerCard
    val shape = RoundedCornerShape(cornerDp)
    val edgeDark = if (colors.isDark) Color(0x55000000) else Color(0x14281C12)
    val edgeLight = if (colors.isDark) Color(0x14F5ECD8) else Color(0x66FFFAEE)
    Box(
        modifier =
            modifier
                .let { if (elevation > 0.dp) it.shadow(elevation, shape, clip = false) else it }
                .clip(shape)
                .background(color)
                .drawBehind {
                    val r = cornerDp.toPx()
                    // Bottom + right hairline edge: drawn as a 1px stroke offset by 0.5px so it
                    // lives entirely inside the corner radius.
                    drawRoundRect(
                        color = edgeDark,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(r, r),
                        style = Stroke(width = 1f),
                    )
                    // Top highlight — second stroke offset 1px down so only the top arc shows
                    // through. Subtle but reads as paper.
                    drawRoundRect(
                        color = edgeLight,
                        topLeft = Offset(0.5f, 0.5f),
                        size = Size(size.width - 1f, size.height - 1f),
                        cornerRadius = CornerRadius(r - 0.5f, r - 0.5f),
                        style = Stroke(width = 0.6f),
                    )
                }.padding(padding),
    ) {
        content()
    }
}
