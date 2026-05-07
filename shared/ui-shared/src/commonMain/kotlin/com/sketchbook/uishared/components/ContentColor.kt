package com.sketchbook.uishared.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Cascading content color. Components downstream resolve text/icon tint from `LocalContentColor`
 * so a primary button's white-on-red text doesn't require every callsite to pass a color
 * explicitly.
 */
val LocalContentColor = compositionLocalOf<Color> { Color.Black }

@Composable
fun ProvideContentColor(
    color: Color,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides color, content = content)
}
