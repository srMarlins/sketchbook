package com.sketchbook.featureonboarding.anim

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * Composable modifier that draws an animated ink underline that grows left-to-right when
 * [active] is true and shrinks back when false. 220ms tween, `EmphasizedDecelerate` easing.
 *
 * Used for the `+ Add folder` button on hover/focus — onboarding has no text fields right
 * now, so the focused-input case is moot for v1. The line is drawn 1.5px above the bottom
 * edge of the modified box in the supplied [color].
 */
@Composable
fun Modifier.inkUnderline(active: Boolean, color: Color): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = EmphasizedDecelerate),
        label = "ink-underline",
    )
    return this.drawBehind {
        if (progress > 0f) {
            val y = size.height - 1f
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width * progress, y),
                strokeWidth = 1.5f,
            )
        }
    }
}

/**
 * Convenience: read hover+focus state from an [InteractionSource] and emit `active = true`
 * when either is set. Wired by the `+ Add folder` callsites that have no text-field cursor
 * to track but do want the underline on pointer hover.
 */
@Composable
fun rememberInkUnderlineActive(source: InteractionSource): Boolean {
    val isHovered by source.collectIsHoveredAsState()
    val isFocused by source.collectIsFocusedAsState()
    return isHovered || isFocused
}
