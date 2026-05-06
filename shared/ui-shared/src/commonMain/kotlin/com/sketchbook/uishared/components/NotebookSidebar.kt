package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme
import kotlin.random.Random

/**
 * Compact notebook-spine sidebar. Sunken paper background with a subtle vertical fiber
 * pattern, a tiny 3-hole binding strip at the top, and the active item rendered as a paper
 * tab overhanging the right edge. Tightened metrics vs the previous pass — ~200dp wide,
 * 32dp row height, smaller 6px holes, less padding.
 */
@Composable
fun NotebookSidebar(
    title: String,
    items: List<SidebarItem>,
    onSelect: (SidebarItem) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 204.dp,
    statusText: String? = null,
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val ruleColor = remember(colors.isDark) {
        if (colors.isDark) Color(0x10F5ECD8) else Color(0x14281C12)
    }
    val edgeHighlight = remember(colors.isDark) {
        if (colors.isDark) Color(0x33F5ECD8) else Color(0x66FFFAEE)
    }
    val edgeShadow = remember(colors.isDark) {
        if (colors.isDark) Color(0x88000000) else Color(0x22281C12)
    }

    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(colors.surfaceSunken)
            .drawBehind {
                // Vertical fibers — long thin strokes at low alpha for a paper-grain feel.
                val rng = Random(0xC0FFEE)
                val cell = 18f * density.density
                val cols = (size.width / cell).toInt() + 1
                val rows = (size.height / cell).toInt() + 1
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if (rng.nextFloat() < 0.18f) {
                            val x = c * cell + rng.nextFloat() * cell
                            val y = r * cell + rng.nextFloat() * cell
                            val len = (4f + rng.nextFloat() * 8f) * density.density
                            drawLine(
                                color = ruleColor,
                                start = Offset(x, y),
                                end = Offset(x, y + len),
                                strokeWidth = 0.6f * density.density,
                            )
                        }
                    }
                }
                // Right-edge paper highlight + drop shadow falling onto the right pane.
                val edgeW = 1f * density.density
                drawRect(
                    color = edgeHighlight,
                    topLeft = Offset(size.width - edgeW, 0f),
                    size = Size(edgeW, size.height),
                )
                val shadowW = 8f * density.density
                drawRect(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(0f to edgeShadow, 1f to Color.Transparent),
                        startX = size.width,
                        endX = size.width + shadowW,
                    ),
                    topLeft = Offset(size.width, 0f),
                    size = Size(shadowW, size.height),
                )
            },
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(top = 12.dp, bottom = 12.dp),
        ) {
            BindingHoles(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(8.dp),
            )
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ProvideContentColor(colors.inkPrimary) {
                    Text(
                        title,
                        style = AppTheme.typography.bodyEmphasis.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
                            letterSpacing = androidx.compose.ui.unit.TextUnit(-0.1f, androidx.compose.ui.unit.TextUnitType.Sp),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (item in items) {
                    SidebarRow(item = item, onClick = { onSelect(item) })
                }
            }
            Spacer(Modifier.weight(1f))
            if (statusText != null) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ProvideContentColor(colors.inkMuted) {
                        Text(
                            statusText,
                            style = AppTheme.typography.mono.copy(
                                fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                        )
                    }
                }
            }
        }
    }
}

data class SidebarItem(
    val id: String,
    val label: String,
    val active: Boolean,
    val badge: String? = null,
)

@Composable
private fun BindingHoles(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val holeColor = if (colors.isDark) Color(0xFF0B0908) else Color(0xFFD8C9A6)
    val holeShadow = if (colors.isDark) Color(0x88000000) else Color(0x33281C12)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(holeColor)
                    .drawBehind {
                        drawCircle(
                            color = holeShadow,
                            radius = size.minDimension / 2f - 0.5f,
                            center = Offset(size.width / 2f, size.height / 2f),
                        )
                    },
            )
        }
    }
}

@Composable
private fun SidebarRow(item: SidebarItem, onClick: () -> Unit) {
    val colors = AppTheme.colors
    // Active row: a tab that overhangs the right edge with a soft drop. Inactive: flat.
    val pillShape = RoundedCornerShape(
        topStart = 0.dp, bottomStart = 0.dp,
        topEnd = AppTheme.spacing.cornerCard, bottomEnd = AppTheme.spacing.cornerCard,
    )
    val activeBg = if (item.active) colors.surfaceCard else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = if (item.active) 0.dp else 8.dp)
            .clip(pillShape)
            .background(activeBg)
            .drawBehind {
                if (item.active) {
                    // Terracotta strip on the left edge.
                    drawLine(
                        color = colors.accentAction,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 3f,
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideContentColor(if (item.active) colors.inkPrimary else colors.inkSecondary) {
            Text(
                item.label,
                style = if (item.active) AppTheme.typography.bodyEmphasis else AppTheme.typography.body,
                modifier = Modifier.weight(1f),
            )
        }
        if (item.badge != null) {
            Badge(
                color = colors.accentSoft,
                padding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                ProvideContentColor(colors.inkPrimary) {
                    Text(item.badge, style = AppTheme.typography.caption)
                }
            }
        }
    }
}
