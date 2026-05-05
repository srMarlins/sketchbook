package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AbletonPalette

/**
 * Round dot that displays an Ableton color tag (1..14). Index 0 / unknown renders as transparent
 * so the layout stays stable when a project hasn't been color-tagged yet.
 */
@Composable
fun Pill(
    colorIndex: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    val color = if (colorIndex != null) AbletonPalette[colorIndex] else null
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color ?: androidx.compose.ui.graphics.Color.Transparent),
    )
}
