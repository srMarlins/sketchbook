package com.sketchbook.uishared.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Persistent 3dp activity strip across the top of the content pane. Three states:
 *
 *  - **Idle** — faint hairline rule. Establishes the band's existence so when activity arrives
 *    nothing materializes from nowhere.
 *  - **Scanning** — terracotta sweeps glide across.
 *  - **Syncing** — blue sweeps glide across, slower.
 *
 * Two sweep layers are 180° out of phase. Each layer's `head` position is driven by a smooth
 * ease-in-out cubic (`0.45, 0, 0.55, 1`) so the gradient accelerates in, peaks midway, and
 * decelerates at the right edge — no hard wrap. The head includes a 8% leading + 30% trailing
 * tail in a 4-stop gradient so the light reads as ink moving rather than a ramp.
 *
 * A thin constant accent of `alpha = 0.08` pools across the whole bar while active, so even
 * during the gap between sweep peaks the bar reads as "lit".
 */
enum class ActivityState { Idle, Scanning, Syncing }

@Composable
fun ActivityBar(
    state: ActivityState,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val ease = remember { CubicBezierEasing(0.45f, 0f, 0.55f, 1f) }
    val transition = rememberInfiniteTransition(label = "activity")
    val durationMs = if (state == ActivityState.Syncing) 2000 else 1400
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = ease),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val accent: Color = when (state) {
        ActivityState.Idle -> Color.Transparent
        ActivityState.Scanning -> colors.accentAction
        ActivityState.Syncing -> colors.pinBlue
    }
    val baseFill = colors.ruleLine
    val pool = if (state == ActivityState.Idle) Color.Transparent else accent.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(baseFill)
            .drawBehind {
                if (state == ActivityState.Idle) return@drawBehind
                // Faint constant accent pool so the bar reads "lit" between sweeps.
                drawRect(color = pool, topLeft = Offset(0f, 0f), size = size)
                drawSweep(phase, accent)
                // Second sweep, 180° out of phase, gives the bar a continuous "in motion" feel.
                drawSweep((phase + 0.5f) % 1f, accent.copy(alpha = 0.55f))
            },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSweep(phase: Float, accent: Color) {
    val w = size.width
    // Sweep is wider than the visible bar so the head can enter/leave smoothly. Width is 36%
    // of the bar; head travels from -width/2 to w + width/2, i.e. fully off-screen on both
    // sides at phase 0/1. The travel is already eased by the caller's animateFloat spec.
    val sweepWidth = w * 0.36f
    val travel = w + sweepWidth
    val center = phase * travel - sweepWidth / 2f
    val left = (center - sweepWidth / 2f)
    val right = (center + sweepWidth / 2f)
    val visibleLeft = left.coerceAtLeast(-sweepWidth)
    val visibleRight = right.coerceAtMost(w + sweepWidth)
    if (visibleRight <= 0f || visibleLeft >= w) return
    drawRect(
        Brush.horizontalGradient(
            // 4-stop gradient: leading tail, peak, trailing tail. The peak is at 55% (slightly
            // forward of center) so the head reads as the wet ink leading the dry tail.
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.18f to accent.copy(alpha = 0.35f),
                0.55f to accent,
                0.85f to accent.copy(alpha = 0.45f),
                1f to Color.Transparent,
            ),
            startX = left,
            endX = right,
        ),
        topLeft = Offset(visibleLeft.coerceAtLeast(0f), 0f),
        size = Size(
            width = (visibleRight.coerceAtMost(w) - visibleLeft.coerceAtLeast(0f)).coerceAtLeast(0f),
            height = size.height,
        ),
    )
}
