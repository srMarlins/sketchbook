package com.sketchbook.featureonboarding.anim

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Step-progress dots. The active dot animates a radial ink-fill from center to full radius
 * on step change; inactive dots stay as a thin hollow ring drawn in [com.sketchbook.uishared.theme.AppColors.inkFaint].
 *
 * Replaces the inline `Box(.background())` indicator that lived in `OnboardingScreen.kt`. The
 * Canvas form gives us the radial growth without pulling in a shape morph library.
 */
@Composable
fun InkDots(count: Int, currentIndex: Int, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            // Per-dot animator keyed implicitly by `target` — recomposes with a fresh tween
            // when the active index changes.
            val target = if (i == currentIndex) 1f else 0f
            val fill by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 280, easing = EmphasizedDecelerate),
                label = "ink-dot-$i",
            )
            Canvas(modifier = Modifier.size(8.dp)) {
                val r = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                // Always draw the hollow ring so the dot has a stable silhouette while the
                // filled core grows/shrinks during transitions.
                drawCircle(
                    color = colors.inkFaint,
                    radius = r,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                )
                if (fill > 0f) {
                    drawCircle(
                        color = colors.inkPrimary,
                        radius = r * fill,
                        center = center,
                    )
                }
            }
        }
    }
}
