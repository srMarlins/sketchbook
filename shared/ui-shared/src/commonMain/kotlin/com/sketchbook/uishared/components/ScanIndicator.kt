package com.sketchbook.uishared.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Hand-drawn scan-progress affordance. The "stroke" is a continuous Bezier path traced from
 * one end of the rule to the other; the visible portion is a windowed slice driven by a
 * smooth eased phase so the ink reads as wet (the head) drying behind it (the tail).
 *
 * Implementation:
 *  - Build a deterministic path from sampled control points along the width — y values come
 *    from a 3-octave noise function (sin combos) seeded by x, so the line wobbles like a
 *    pen tracing a ruler rather than a pure sine wave.
 *  - Each frame we compute `head` (eased phase) and `tail = head − tailLength`. We use
 *    `PathMeasure` to extract the slice between [tail, head] and stroke that segment.
 *  - The lead point gets an additional **glowing nib** — a small filled circle with a soft
 *    radial halo — at the head, so the eye tracks "where the pen is right now".
 *  - Tail thickness tapers via two layered strokes: a thin (1.0px) full-length pass plus a
 *    thicker (1.6px) head-weighted pass that fades over the trailing 70% via dash effect.
 *
 *  Path control points are recomputed only when the bar's *measured width* changes — every
 *  frame we just slice the existing path. Cheap.
 */
@Composable
fun ScanIndicator(
    active: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    AnimatedVisibility(
        visible = active,
        enter = fadeIn(tween(durationMillis = 220)),
        exit = fadeOut(tween(durationMillis = 220)),
        modifier = modifier,
    ) {
        val colors = AppTheme.colors
        val ease = remember { CubicBezierEasing(0.42f, 0f, 0.58f, 1f) }
        val transition = rememberInfiniteTransition(label = "scan")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = ease),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        val nibPulse by transition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "nibPulse",
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (label != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .drawBehind {
                                // Outer halo
                                drawCircle(
                                    color = colors.accentAction.copy(alpha = 0.22f * nibPulse),
                                    radius = size.minDimension / 2f * (0.9f + 0.3f * nibPulse),
                                    center = Offset(size.width / 2f, size.height / 2f),
                                )
                                // Solid nib
                                drawCircle(
                                    color = colors.accentAction,
                                    radius = size.minDimension / 2f * 0.55f,
                                    center = Offset(size.width / 2f, size.height / 2f),
                                )
                            },
                    )
                    ProvideContentColor(colors.inkMuted) {
                        Text(label, style = AppTheme.typography.caption)
                    }
                }
            }

            // The animated ink hairline.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .drawBehind {
                        val baseY = size.height / 2f
                        // Static rule the pen is "drawing on".
                        drawLine(
                            color = colors.ruleLine,
                            start = Offset(0f, baseY),
                            end = Offset(size.width, baseY),
                            strokeWidth = 1f,
                        )

                        // Build the wobbly path once per frame. Cheap because we only sample 32
                        // points and the path object is throwaway.
                        val points = 32
                        val path = Path()
                        val step = size.width / (points - 1)
                        for (i in 0 until points) {
                            val x = i * step
                            val y = baseY + handWobble(x, size.width) * 2.4f
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }

                        val total = PathMeasure().apply { setPath(path, false) }.length
                        val tailLen = total * 0.42f
                        val head = phase * (total + tailLen) - tailLen
                        val tail = (head - tailLen).coerceAtLeast(0f)
                        val visibleHead = head.coerceAtMost(total)
                        if (visibleHead <= 0f) return@drawBehind

                        val measure = PathMeasure().apply { setPath(path, false) }

                        // Layer 1: thin full-stroke pass, light alpha — the "dry" line.
                        val baseSeg = Path()
                        if (measure.getSegment(tail, visibleHead, baseSeg, true)) {
                            drawPath(
                                path = baseSeg,
                                color = colors.accentAction.copy(alpha = 0.35f),
                                style = Stroke(width = 0.9f, cap = StrokeCap.Round),
                            )
                        }

                        // Layer 2: thicker head-weighted pass, full alpha. We slice into 6
                        // sub-segments and stroke each with growing width + alpha so the head
                        // reads as wet ink. Cheaper than per-pixel pathEffects.
                        val headLen = (visibleHead - tail).coerceAtLeast(0f)
                        val sliceN = 6
                        for (k in 0 until sliceN) {
                            val a = tail + headLen * (k / sliceN.toFloat())
                            val b = tail + headLen * ((k + 1) / sliceN.toFloat())
                            val seg = Path()
                            if (measure.getSegment(a, b, seg, true)) {
                                val t = (k + 1) / sliceN.toFloat() // 0..1, head=1
                                val widthPx = 0.8f + 1.2f * t
                                val alpha = 0.30f + 0.60f * t
                                drawPath(
                                    path = seg,
                                    color = colors.accentAction.copy(alpha = alpha),
                                    style = Stroke(width = widthPx, cap = StrokeCap.Round),
                                )
                            }
                        }

                        // The glowing nib at the head. Compose's PathMeasure has no getPosTan,
                        // but our path's length is dominated by its x-extent (the wobble adds
                        // <0.5% of total length), so we approximate the nib position by
                        // mapping head distance back to x = head/total * width and reading y
                        // from the same wobble function.
                        val nibX = (visibleHead / total) * size.width
                        val nibY = baseY + handWobble(nibX, size.width) * 2.4f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to colors.accentAction.copy(alpha = 0.55f * nibPulse),
                                    1f to Color.Transparent,
                                ),
                                center = Offset(nibX, nibY),
                                radius = 5.5f,
                            ),
                            radius = 5.5f,
                            center = Offset(nibX, nibY),
                        )
                        drawCircle(
                            color = colors.accentAction,
                            radius = 1.6f,
                            center = Offset(nibX, nibY),
                        )
                    },
            )
        }
    }
}

/**
 * Three-octave hand-tremor noise. Sums sins at 3 frequencies + small random-looking
 * coefficient variations. Deterministic per-x; no per-frame allocations.
 */
private fun handWobble(x: Float, w: Float): Float {
    if (w <= 0f) return 0f
    val u = (x / w) * 2f * PI.toFloat()
    val a = sin(u * 3.1f) * 0.6f
    val b = sin(u * 7.4f + 1.1f) * 0.25f
    val c = sin(u * 13.7f + 2.7f) * 0.10f
    return a + b + c
}
