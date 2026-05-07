package com.sketchbook.desktop

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.unit.sp
import com.sketchbook.uishared.theme.AppTypography
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

/**
 * Loads bundled Inter (variable) and Space Mono fonts off the desktop JAR's resources and
 * builds an [AppTypography] that uses them.
 *
 * Compose Desktop has no public `Font(data: ByteArray, ...)` overload, so we go through Skia
 * directly: `Typeface.makeFromData()` accepts WOFF2 because Skia compiles libwoff2 in. The
 * resulting Skia typeface is wrapped via `androidx.compose.ui.text.platform.Typeface` and
 * handed to `FontFamily`.
 *
 * Variable-weight axes aren't honoured by `FontFamily(Typeface)` — we get one weight per
 * family. For Inter we use the variable file at its default 400, which still looks dramatically
 * better than the system sans fallback. SemiBold on titles uses synthetic bold from Skia.
 *
 * If a resource is missing (stripped bundle, dev classpath weirdness), fall back to the
 * platform sans/mono so the app still renders.
 */
object Fonts {
    fun load(): AppTypography {
        val interFamily = loadFamily("/fonts/inter-variable.woff2") ?: FontFamily.SansSerif
        val monoFamily = loadFamily("/fonts/space-mono-400.woff2") ?: FontFamily.Monospace

        return AppTypography(
            display = TextStyle(fontFamily = interFamily, fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
            title = TextStyle(fontFamily = interFamily, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            body = TextStyle(fontFamily = interFamily, fontSize = 14.sp, fontWeight = FontWeight.Normal),
            bodyEmphasis = TextStyle(fontFamily = interFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            mono = TextStyle(fontFamily = monoFamily, fontSize = 13.sp, fontWeight = FontWeight.Normal),
            caption = TextStyle(fontFamily = interFamily, fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp),
        )
    }

    private fun loadFamily(resource: String): FontFamily? {
        val bytes = readResource(resource) ?: return null
        val skiaTypeface =
            runCatching {
                FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
            }.getOrNull() ?: return null
        return FontFamily(Typeface(skiaTypeface))
    }

    private fun readResource(path: String): ByteArray? {
        val stream = Fonts::class.java.getResourceAsStream(path) ?: return null
        return stream.use { it.readBytes() }
    }
}
