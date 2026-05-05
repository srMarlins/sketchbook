package com.sketchbook.uishared.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sketchbook.uishared.theme.AppTheme

/**
 * Lease-lock indicator. The caller decides the [color] + [label] mapping (so domain semantics
 * stay out of `ui-shared`); a typical placement is the trailing slot of the project-detail
 * header, with [actionLabel] = `"Take"` / `"Force-take"` only when the user can act.
 */
@Composable
fun LockBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Badge(color = color) {
            Text(text = label, style = AppTheme.typography.caption)
        }
        if (detail != null) {
            Text(text = detail, style = AppTheme.typography.caption)
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, variant = ButtonVariant.Ghost) {
                Text(text = actionLabel)
            }
        }
    }
}
