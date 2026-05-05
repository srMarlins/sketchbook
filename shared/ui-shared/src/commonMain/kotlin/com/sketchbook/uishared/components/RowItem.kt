package com.sketchbook.uishared.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.uishared.theme.AppTheme

/**
 * One-line list row with optional leading/trailing slots and an optional subtitle. State-free —
 * pass `onClick` for click semantics and resolve hover/press tints in callers if they want them.
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
    val rowMod = if (onClick != null) modifier.clickable { onClick() } else modifier
    Row(
        modifier = rowMod.padding(PaddingValues(AppTheme.spacing.md)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f)) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(title, style = AppTheme.typography.bodyEmphasis)
            }
            if (subtitle != null) {
                ProvideContentColor(AppTheme.colors.inkMuted) {
                    Text(subtitle, style = AppTheme.typography.caption)
                }
            }
        }
        if (trailing != null) trailing()
    }
}
