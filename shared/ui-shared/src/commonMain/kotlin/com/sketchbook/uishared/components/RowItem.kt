package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * One-line list row rendered as a paper card. Hairline edges + a faint hover-tint shift to
 * the warm cream-tinted background, the same treatment the web's project rows used.
 */
@Composable
fun RowItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val cornerDp = AppTheme.spacing.cornerCard
    val shape = RoundedCornerShape(cornerDp)
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val edgeDark = if (colors.isDark) Color(0x55000000) else Color(0x12281C12)
    val edgeLight = if (colors.isDark) Color(0x14F5ECD8) else Color(0x66FFFAEE)
    val bg = if (isHovered) colors.tintCream else colors.surfaceCard

    val rowMod = modifier
        .clip(shape)
        .background(bg)
        .drawBehind {
            val r = cornerDp.toPx()
            drawRoundRect(
                color = edgeDark,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1f),
            )
            drawRoundRect(
                color = edgeLight,
                topLeft = Offset(0.5f, 0.5f),
                size = Size(size.width - 1f, size.height - 1f),
                cornerRadius = CornerRadius(r - 0.5f, r - 0.5f),
                style = Stroke(width = 0.6f),
            )
        }
        .let {
            if (onClick != null) {
                it.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
            } else {
                it
            }
        }
    Row(
        modifier = rowMod.padding(PaddingValues(horizontal = AppTheme.spacing.md, vertical = 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f)) {
            ProvideContentColor(colors.inkPrimary) {
                Text(title, style = AppTheme.typography.bodyEmphasis)
            }
            if (subtitle != null) {
                ProvideContentColor(colors.inkMuted) {
                    Text(subtitle, style = AppTheme.typography.caption)
                }
            }
        }
        if (trailing != null) trailing()
    }
}
