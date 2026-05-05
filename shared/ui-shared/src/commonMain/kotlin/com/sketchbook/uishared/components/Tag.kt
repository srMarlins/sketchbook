package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.sketchbook.uishared.theme.AppTheme

/**
 * Inline label chip (`drum-loop`, `key:Cm`, etc). [onRemove] turns it into a removable tag with
 * a trailing affordance the caller renders.
 */
@Composable
fun Tag(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.surfaceKraft,
    onRemove: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(AppTheme.spacing.cornerSmall)
    Row(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.25f))
            .border(width = AppTheme.spacing.ruleHairline, color = color, shape = shape)
            .padding(PaddingValues(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xs)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(label, style = AppTheme.typography.caption)
        }
        if (onRemove != null) {
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(
                    text = "×",
                    style = AppTheme.typography.bodyEmphasis,
                    modifier = Modifier.clickable { onRemove() },
                )
            }
        }
    }
}
