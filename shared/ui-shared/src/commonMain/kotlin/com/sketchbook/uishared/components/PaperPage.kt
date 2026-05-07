package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme
import kotlin.math.sin
import kotlin.random.Random

/**
 * Page-level container that paints the warm cream paper backdrop with three layered effects:
 *
 *  1. Two soft radial highlights — same gesture as the web's `body { background-image }`.
 *  2. A deterministic micro-speckle (hash-positioned dots, ~1px) that gives the surface a
 *     subtle paper-fiber feel without burning a real noise texture into a bitmap.
 *  3. A faint left-edge gradient where the page meets the sidebar binding.
 *
 * Speckle density is intentionally light (~1 dot per 110px²) so it reads only at close
 * inspection. Dot color is tuned to the surface tone with low alpha; on dark theme it shifts
 * to a warm highlight rather than a dark fleck.
 */
@Composable
fun PaperPage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val highlight =
        remember(colors.isDark) {
            if (colors.isDark) Color(0x33503C28) else Color(0x2EFFF0C8)
        }
    val pool =
        remember(colors.isDark) {
            if (colors.isDark) Color(0x66281E14) else Color(0x1AC8AA8C)
        }
    val speckleDark =
        remember(colors.isDark) {
            if (colors.isDark) Color(0x10F5ECD8) else Color(0x12281C12)
        }
    val speckleWarm =
        remember(colors.isDark) {
            if (colors.isDark) Color(0x14B8835A) else Color(0x18B8835A)
        }
    val bindingShadow =
        remember(colors.isDark) {
            if (colors.isDark) Color(0x55000000) else Color(0x12281C12)
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.surfacePage)
                .drawBehind {
                    // Two soft radials.
                    drawRect(
                        Brush.radialGradient(
                            colorStops = arrayOf(0f to highlight, 1f to Color.Transparent),
                            center = Offset(360f * density.density, 200f * density.density),
                            radius = 900f * density.density,
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colorStops = arrayOf(0f to pool, 1f to Color.Transparent),
                            center = Offset(size.width - 200f * density.density, size.height - 100f * density.density),
                            radius = 800f * density.density,
                        ),
                    )
                    // Deterministic speckle. Density tuned to ~1 dot per 110px² of canvas.
                    val rng = Random(0x5EED5EED)
                    val dotR = 0.6f * density.density
                    val cell = 14f * density.density
                    val cols = (size.width / cell).toInt() + 1
                    val rows = (size.height / cell).toInt() + 1
                    for (r in 0 until rows) {
                        for (c in 0 until cols) {
                            // Skip ~80% of cells; the rest get a jittered dot.
                            if (rng.nextFloat() < 0.20f) {
                                val jx = rng.nextFloat() * cell
                                val jy = rng.nextFloat() * cell
                                val color = if (rng.nextFloat() < 0.5f) speckleDark else speckleWarm
                                drawCircle(
                                    color = color,
                                    radius = dotR,
                                    center = Offset(c * cell + jx, r * cell + jy),
                                )
                            }
                        }
                    }
                }.drawBehind {
                    // Soft binding shadow on the left edge — falls off in the first 16dp.
                    val shadowWidth = 16f * density.density
                    drawRect(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(0f to bindingShadow, 1f to Color.Transparent),
                            startX = 0f,
                            endX = shadowWidth,
                        ),
                        size = Size(shadowWidth, size.height),
                    )
                },
    ) {
        content()
    }
}
