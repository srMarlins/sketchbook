package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme
import com.sketchbook.uishared.theme.LocalAppTypography

/**
 * Visual variant for [Button]. `Primary` is the terracotta CTA, `Secondary` is paper-edged,
 * `Ghost` is text-only.
 */
enum class ButtonVariant { Primary, Secondary, Ghost }

/**
 * Paper-feel button. Detail layers:
 *
 *  - Soft warm drop on Primary / hairline-only on Secondary.
 *  - 1px top highlight and 1px bottom dark-edge stroke ("drawn" paper edge).
 *  - On press, the button visually inverts: shadow shrinks, fill darkens slightly. Mirrors
 *    a piece of paper being pressed into the desk.
 *  - On hover, a faint bias toward the accent-soft tone.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val cornerDp = AppTheme.spacing.cornerInput
    val shape = RoundedCornerShape(cornerDp)
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val isHovered by interaction.collectIsHoveredAsState()

    val (baseBg, fg, edgeDark, edgeLight, baseElevation) = when (variant) {
        ButtonVariant.Primary -> Quintet(
            colors.accentAction,
            colors.inkOnFill,
            Color(0x33000000),
            Color(0x33FFFFFF),
            3.dp,
        )

        ButtonVariant.Secondary -> Quintet(
            colors.surfaceCard,
            colors.inkPrimary,
            colors.ruleLineStrong,
            if (colors.isDark) Color(0x14F5ECD8) else Color(0x55FFFAEE),
            1.dp,
        )

        ButtonVariant.Ghost -> Quintet(
            Color.Transparent,
            colors.inkPrimary,
            Color.Transparent,
            Color.Transparent,
            0.dp,
        )
    }

    val bg = when {
        !enabled -> baseBg.copy(alpha = baseBg.alpha * 0.4f)
        isPressed && variant == ButtonVariant.Primary -> blend(baseBg, Color.Black, 0.10f)
        isPressed && variant == ButtonVariant.Secondary -> blend(baseBg, colors.ruleLine, 0.30f)
        isHovered && variant == ButtonVariant.Secondary -> blend(baseBg, colors.accentSoft, 0.18f)
        isHovered && variant == ButtonVariant.Ghost -> colors.tintCream.copy(alpha = 0.6f)
        else -> baseBg
    }
    val effectiveFg = if (enabled) fg else fg.copy(alpha = 0.4f)
    val elevation = when {
        !enabled || variant == ButtonVariant.Ghost -> 0.dp
        isPressed -> (baseElevation - 1.dp).coerceAtLeast(0.dp)
        else -> baseElevation
    }

    val drawEdges: Modifier = if (variant == ButtonVariant.Ghost) {
        Modifier
    } else {
        Modifier.drawBehind {
            val r = cornerDp.toPx()
            drawRoundRect(
                color = edgeDark,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1f),
            )
            drawRoundRect(
                color = edgeLight,
                topLeft = Offset(0.5f, 0.5f),
                size = Size(size.width - 1f, size.height - 1f),
                cornerRadius = CornerRadius(r - 0.5f, r - 0.5f),
                style = Stroke(width = 0.6f),
            )
        }
    }

    val finalMod = modifier
        .let { if (elevation > 0.dp) it.shadow(elevation, shape, clip = false) else it }
        .clip(shape)
        .background(bg)
        .then(drawEdges)
        .clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
        ) { onClick() }
        .padding(PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm))

    Row(
        modifier = finalMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        if (leading != null) leading()
        CompositionLocalProvider(LocalAppTypography provides AppTheme.typography) {
            ProvideContentColor(effectiveFg) { content() }
        }
        if (trailing != null) trailing()
    }
}

private data class Quintet(val a: Color, val b: Color, val c: Color, val d: Color, val e: Dp)

private fun blend(a: Color, b: Color, t: Float): Color = Color(
    red = a.red * (1 - t) + b.red * t,
    green = a.green * (1 - t) + b.green * t,
    blue = a.blue * (1 - t) + b.blue * t,
    alpha = a.alpha,
)
