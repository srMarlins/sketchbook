package com.sketchbook.uishared.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.sketchbook.uishared.theme.AppTheme

/**
 * Plain text wrapper. Defaults to `body` typography + `LocalContentColor` so callers don't
 * have to wire color/style every time.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AppTheme.typography.body,
) {
    val color = LocalContentColor.current
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
    )
}
