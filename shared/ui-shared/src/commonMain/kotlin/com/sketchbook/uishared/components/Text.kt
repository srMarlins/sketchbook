package com.sketchbook.uishared.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.sketchbook.uishared.theme.AppTheme

/**
 * Plain text wrapper. Defaults to `body` typography + `LocalContentColor` so callers don't
 * have to wire color/style every time. Forwards `overflow` / `maxLines` to BasicText.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AppTheme.typography.body,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
) {
    val color = LocalContentColor.current
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        overflow = overflow,
        maxLines = maxLines,
    )
}
