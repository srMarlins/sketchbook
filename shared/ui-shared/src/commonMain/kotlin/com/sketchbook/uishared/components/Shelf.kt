package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Section header for a project shelf. Mirrors the web/'s `ShelfStrip` header — title +
 * description on the left, see-all link on the right. Both pieces are visually subdued so the
 * row content reads as the focus.
 */
@Composable
fun ShelfHeader(
    title: String,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ProvideContentColor(colors.inkPrimary) {
                Text(
                    title,
                    style = AppTheme.typography.bodyEmphasis.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                )
            }
            if (subtitle != null) {
                ProvideContentColor(colors.inkMuted) {
                    Text(subtitle, style = AppTheme.typography.caption)
                }
            }
        }
        if (onShuffle != null) {
            ShelfHeaderAction(label = "↻ SHUFFLE", onClick = onShuffle)
        }
        if (onSeeAll != null) {
            ShelfHeaderAction(label = "SEE ALL →", onClick = onSeeAll)
        }
    }
}

@Composable
private fun ShelfHeaderAction(label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        ProvideContentColor(colors.inkMuted) {
            Text(
                label,
                style = AppTheme.typography.mono.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
    }
}
