package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Single-line paper-recessed text field. Detail layers:
 *
 *  - Soft inner top-shadow gradient (~3dp tall) so the field reads as pressed into the page.
 *  - 1px hairline stroke (rule-line) on three edges; bottom edge is the accent terracotta on
 *    focus to mimic the notebook margin underline.
 *  - Subtle bottom highlight (off-white) where the recess catches paper.
 *
 * Click anywhere on the rendered surface focuses the input — wrapping the row in `clickable`
 * + `FocusRequester` instead of relying on BasicTextField's own click zone, which only
 * registers on the visible glyphs and feels broken on a wide field.
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
    val cornerDp = AppTheme.spacing.cornerInput
    val shape = RoundedCornerShape(cornerDp)
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val rowInteraction = remember { MutableInteractionSource() }
    val innerShadow = if (colors.isDark) Color(0x66000000) else Color(0x18281C12)
    val highlight = if (colors.isDark) Color(0x10F5ECD8) else Color(0x55FFFAEE)
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceCard)
            .drawBehind {
                val r = cornerDp.toPx()
                drawRect(
                    Brush.verticalGradient(
                        colorStops = arrayOf(0f to innerShadow, 1f to Color.Transparent),
                        startY = 0f,
                        endY = 4f * density,
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, 4f * density),
                )
                drawRect(
                    color = highlight,
                    topLeft = Offset(0f, size.height - 1f),
                    size = Size(size.width, 1f),
                )
                drawRoundRect(
                    color = colors.ruleLineStrong,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1f),
                )
                if (isFocused) {
                    drawLine(
                        color = colors.accentAction,
                        start = Offset(r * 0.8f, size.height - 0.5f),
                        end = Offset(size.width - r * 0.8f, size.height - 0.5f),
                        strokeWidth = 1.5f,
                    )
                }
            }
            .clickable(
                interactionSource = rowInteraction,
                indication = null,
                onClick = { focusRequester.requestFocus() },
            )
            .padding(PaddingValues(horizontal = AppTheme.spacing.md, vertical = 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        if (leading != null) ProvideContentColor(colors.inkMuted) { leading() }
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                interactionSource = interaction,
                textStyle = AppTheme.typography.body.copy(color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.inkPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
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
