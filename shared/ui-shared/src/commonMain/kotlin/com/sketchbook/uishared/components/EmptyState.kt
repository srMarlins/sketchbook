package com.sketchbook.uishared.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.uishared.theme.AppTheme

/**
 * "Nothing here yet" panel. Used by every list/grid screen when the data set is empty after
 * applying filters. Keeps copy short — long copy reads as a debug message, not an empty state.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(
        modifier = modifier.padding(PaddingValues(AppTheme.spacing.xl)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(title, style = AppTheme.typography.title)
        }
        if (hint != null) {
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(hint, style = AppTheme.typography.body)
            }
        }
    }
}
