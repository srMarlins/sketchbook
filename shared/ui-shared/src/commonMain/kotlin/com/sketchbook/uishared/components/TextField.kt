package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.sketchbook.uishared.theme.AppTheme

/**
 * Stateless single-line text field. State is hoisted; the caller owns [value] and updates it via
 * [onChange]. Slots for leading/trailing decorations (search icon, clear button, etc).
 */
@Composable
fun TextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppTheme.spacing.cornerSmall)
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfacePanel)
            .border(width = AppTheme.spacing.ruleHairline, color = colors.ruleLine, shape = shape)
            .padding(PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        if (leading != null) ProvideContentColor(colors.inkMuted) { leading() }
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = AppTheme.typography.body.copy(color = colors.inkPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.inkPrimary),
            )
            if (value.isEmpty() && placeholder != null) {
                ProvideContentColor(colors.inkMuted) {
                    Text(text = placeholder)
                }
            }
        }
        if (trailing != null) ProvideContentColor(colors.inkMuted) { trailing() }
    }
}
