package com.sketchbook.uishared.components

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.foundation.layout.size as layoutSize

/**
 * Three-dot ink-pulse loading indicator. Each dot does three things in concert:
 *
 *  - **Scale** 0.6 → 1.0: the dot grows as the ink wells up.
 *  - **Alpha** 0.25 → 1.0: ink saturation rises.
 *  - **Bob** 1px down → 0: the dot settles upward as if surface tension catches it.
 *
 * Dots are 33% out of phase, so the wave reads as one continuous gesture rather than three
 * disconnected blinkers. Per-dot ease is `EaseInOutCubic`, which pins the rest position long
 * enough to register before the next pulse begins — a sin curve has no rest, so the eye
 * reads it as nervous.
 */
@Composable
fun InkLoading(
    modifier: Modifier = Modifier,
    diameter: Dp = 6.dp,
    spacing: Dp = 5.dp,
) {
    val colors = AppTheme.colors
    val transition = rememberInfiniteTransition(label = "ink-loading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "phase",
    )
    val bobMaxPx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.5.dp.toPx() }
    Box(
        modifier =
            modifier
                .layoutSize(width = diameter * 3 + spacing * 2 + 4.dp, height = diameter + 6.dp)
                .drawBehind {
                    val r = diameter.toPx() / 2f
                    val gap = spacing.toPx()
                    val centersX = listOf(r, r * 2 + gap + r, r * 4 + gap * 2 + r)
                    val baselineY = size.height / 2f
                    for ((i, cx) in centersX.withIndex()) {
                        // Each dot's local time is offset by (i / 3).
                        val local = ((phase - i / 3f) + 1f) % 1f
                        // Single-pulse profile: the dot wells up over [0, 0.5] and settles back
                        // over [0.5, 1.0]. Both halves shaped with EaseInOutCubic so the rest
                        // positions are held — no flat midline like a sin wave.
                        val pulse =
                            if (local < 0.5f) {
                                EaseInOutCubic.transform(local / 0.5f)
                            } else {
                                EaseInOutCubic.transform(1f - (local - 0.5f) / 0.5f)
                            }
                        val scale = 0.6f + 0.4f * pulse
                        val alpha = 0.25f + 0.75f * pulse
                        val bob = (1f - pulse) * bobMaxPx // dot is *lower* at rest, snaps up at peak
                        drawCircle(
                            color = colors.accentAction.copy(alpha = alpha),
                            radius = r * scale,
                            center = Offset(cx, baselineY + bob),
                        )
                    }
                },
    )
}
