package com.sketchbook.featureonboarding.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Heading that types itself in word-by-word on first composition. 60ms stagger between
 * words, 250ms fade per word; an 8-word heading lands in ~670ms which is at the upper end
 * of the design doc's 600ms target but still under the 400ms-per-element ceiling.
 *
 * Word splitting is whitespace-only; punctuation stays attached to the preceding word.
 *
 * @param text Heading text. Will be split on whitespace into separate animated words.
 * @param style Text style (typically `AppTheme.typography.title`).
 * @param color Resolved ink color — passed in so callers don't need to wrap this in a
 *   `ProvideContentColor` for the underlying `BasicText`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypingHeading(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // Remember-by-text so when the parent step re-enters with the same heading we reuse
    // the same word-list (no re-splitting on every recomposition). Splitting on \s+ also
    // collapses any double-spaces that snuck into the copy.
    val words = remember(text) { text.split(Regex("\\s+")).filter { it.isNotEmpty() } }
    // One animatable per word, keyed by the heading text so a step swap animates fresh.
    val alphas = remember(text) { List(words.size) { Animatable(0f) } }

    LaunchedEffect(text) {
        words.indices.forEach { i ->
            launch {
                kotlinx.coroutines.delay(i * 60L)
                alphas[i].animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 250, easing = EmphasizedDecelerate),
                )
            }
        }
    }

    // FlowRow so a long heading wraps onto a second line on narrow content widths instead of
    // clipping. Each word is its own BasicText so alpha animates per-word independently.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        words.forEachIndexed { i, w ->
            BasicText(
                text = w,
                style = style.copy(color = color.copy(alpha = color.alpha * alphas[i].value)),
            )
        }
    }
}
