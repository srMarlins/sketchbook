package com.sketchbook.featureonboarding.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.launch

/**
 * Wraps content with the folder-card "stamp" animation: scale 0.92 → 1.0 with a
 * `StiffnessMediumLow` spring, plus a 180ms alpha fade. Plays once per first composition
 * (the `Animatable`s only animate inside their `LaunchedEffect`), so a parent recomposition
 * doesn't replay the entrance.
 *
 * The keyed `LaunchedEffect` — keyed `Unit` — runs exactly once per call-site lifetime;
 * if the caller wants a fresh stamp for each new item, they should ensure the wrapper is
 * keyed (e.g. by using `key(path) { StampCard { ... } }`).
 */
@Composable
fun StampCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(0.92f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 180, easing = EmphasizedDecelerate),
            )
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .scale(scale.value)
            .alpha(alpha.value),
    ) {
        content()
    }
}
