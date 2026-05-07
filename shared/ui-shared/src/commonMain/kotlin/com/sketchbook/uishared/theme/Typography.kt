package com.sketchbook.uishared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Three font families: display (handwritten — Permanent Marker), mono (Space Mono), sans (Inter).
 * Fonts are bundled by the desktop app (PR-18). Until then [display]/[mono] fall back to
 * Compose's generic families so this module compiles and tests run on any host.
 */
@Immutable
data class AppTypography(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyEmphasis: TextStyle,
    val mono: TextStyle,
    val caption: TextStyle,
) {
    companion object {
        val Default: AppTypography =
            AppTypography(
                display = TextStyle(fontFamily = FontFamily.Cursive, fontSize = 32.sp, fontWeight = FontWeight.Normal),
                title = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Normal),
                bodyEmphasis = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Normal),
                caption = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Normal),
            )
    }
}

val LocalAppTypography = staticCompositionLocalOf<AppTypography> { AppTypography.Default }
