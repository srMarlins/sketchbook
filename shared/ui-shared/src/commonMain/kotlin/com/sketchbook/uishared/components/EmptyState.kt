package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * "Nothing here yet" panel rendered as a tinted-cream card so it reads like a sticky note
 * placed on the page rather than dead space. Centered horizontally; vertical placement is
 * the caller's choice (typically `Box(contentAlignment = Center)`).
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier.fillMaxWidth().padding(AppTheme.spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(AppTheme.spacing.cornerCard))
                    .background(colors.tintCream)
                    .padding(PaddingValues(horizontal = AppTheme.spacing.xl, vertical = AppTheme.spacing.lg)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            ProvideContentColor(colors.inkPrimary) {
                Text(title, style = AppTheme.typography.title)
            }
            if (hint != null) {
                ProvideContentColor(colors.inkMuted) {
                    Text(hint, style = AppTheme.typography.body)
                }
            }
        }
    }
}
