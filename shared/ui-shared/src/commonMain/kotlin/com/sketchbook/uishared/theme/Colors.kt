package com.sketchbook.uishared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Stationery palette mirrored from `web/src/theme/tokens.css`. The web app set the visual
 * language; the desktop app must look like its native cousin, so every surface/ink/accent
 * value below is the same hex the React app used.
 *
 * Palette is intentionally hand-rolled (no Material3 ColorScheme): it lets the visual language
 * stay paper-like instead of inheriting Material elevation/tonal surface noise.
 */
@Immutable
data class AppColors(
    // Surfaces
    val surfacePage: Color,
    val surfaceCard: Color,
    val surfaceSunken: Color,
    val surfacePanel: Color,
    val tintBlue: Color,
    val tintRose: Color,
    val tintSage: Color,
    val tintCream: Color,
    val surfaceOverlay: Color,
    // Ink
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val inkOnFill: Color,
    // Rules
    val ruleLine: Color,
    val ruleLineStrong: Color,
    val ruleMargin: Color,
    val ruleBlue: Color,
    // Accents
    val accentAction: Color,
    val accentSoft: Color,
    val accentSecondary: Color,
    val accentPositive: Color,
    val accentWarning: Color,
    val accentDanger: Color,
    // Pin colors (legacy aliases — mapped to accent palette)
    val pinGreen: Color,
    val pinBlue: Color,
    val pinOrange: Color,
    val pinPurple: Color,
    val pinRed: Color,
    val pinYellow: Color,
    // Legacy alias kept for callers still using it; render the same as surfaceCard.
    val surfaceCorkboard: Color,
    val surfaceKraft: Color,
    val surfaceStripBase: Color,
    val surfaceDesk: Color,
    val inkOnStripLight: Color,
    val inkOnStripDark: Color,
    val tintOverlay: Color,
    val isDark: Boolean,
) {
    companion object {
        val Light: AppColors =
            run {
                val paperBase = Color(0xFFF8F1E3)
                val paperRaised = Color(0xFFFFFAEE)
                val paperSunken = Color(0xFFEFE4CB)
                // 10-color sunset/sea palette mapped onto the semantic accent slots. The vivid hues
                // do the talking; the paper surfaces and ink tones stay calm so the accents pop
                // without the overall canvas feeling neon.
                val crimson = Color(0xFFF94144) // destructive only (Reject, Conflict, pinRed)
                val orange = Color(0xFFF3722C) // rule margin — sunset accent strip
                val brightOrange = Color(0xFFF8961E) // pinOrange
                val saffron = Color(0xFFF9C74F) // accentWarning, pinYellow
                val pistachio = Color(0xFF90BE6D) // accentPositive, pinGreen
                val zomp = Color(0xFF43AA8B) // primary CTA — fresh, distinctly not red
                val slateBlue = Color(0xFF577590) // accentSecondary
                val celadonBlue = Color(0xFF277DA1) // pinBlue
                val tintBlue = Color(0xFFE3EEF3) // pale celadon wash
                val tintRose = Color(0xFFFCE0D5) // pale orange wash
                val tintSage = Color(0xFFE6F1D7) // pale pistachio wash
                val tintCream = Color(0xFFFDEFC4) // pale saffron wash
                AppColors(
                    surfacePage = paperBase,
                    surfaceCard = paperRaised,
                    surfaceSunken = paperSunken,
                    surfacePanel = paperRaised,
                    tintBlue = tintBlue,
                    tintRose = tintRose,
                    tintSage = tintSage,
                    tintCream = tintCream,
                    surfaceOverlay = Color(0x40281C12),
                    inkPrimary = Color(0xFF2B2521),
                    inkSecondary = Color(0xFF4A3F37),
                    inkMuted = Color(0xFF7A6B5E),
                    inkFaint = Color(0xFFB3A797),
                    inkOnFill = Color(0xFFF8F1E3),
                    ruleLine = Color(0x24281C12),
                    ruleLineStrong = Color(0x383C3228),
                    ruleMargin = orange,
                    ruleBlue = Color(0xFFB9C8D6),
                    accentAction = zomp,
                    accentSoft = Color(0xFFCDEADC), // pale zomp selection
                    accentSecondary = slateBlue,
                    accentPositive = pistachio,
                    accentWarning = saffron,
                    accentDanger = crimson,
                    pinGreen = pistachio,
                    pinBlue = celadonBlue,
                    pinOrange = brightOrange,
                    pinPurple = Color(0xFFD86CE4),
                    pinRed = crimson,
                    pinYellow = saffron,
                    surfaceCorkboard = paperSunken,
                    surfaceKraft = tintCream,
                    surfaceStripBase = paperRaised,
                    surfaceDesk = paperBase,
                    inkOnStripLight = Color(0xFF2B2521),
                    inkOnStripDark = Color(0xFFF8F1E3),
                    tintOverlay = Color.Transparent,
                    isDark = false,
                )
            }

        val Dark: AppColors =
            Light.copy(
                surfacePage = Color(0xFF1A1714),
                surfaceCard = Color(0xFF383330),
                surfaceSunken = Color(0xFF131110),
                surfacePanel = Color(0xFF383330),
                tintBlue = Color(0xFF2C4A55), // deep celadon wash
                tintRose = Color(0xFF6A3F2E), // deep orange wash
                tintSage = Color(0xFF4B5C3A), // deep pistachio wash
                tintCream = Color(0xFF5C4B23), // deep saffron wash
                surfaceOverlay = Color(0x59080503),
                inkPrimary = Color(0xFFF5ECD8),
                inkSecondary = Color(0xFFD6C9AF),
                inkMuted = Color(0xFFA39884),
                inkFaint = Color(0xFF5C5448),
                inkOnFill = Color(0xFF1D1A17),
                ruleLine = Color(0x24DCC8AA),
                ruleLineStrong = Color(0x3DDCC8AA),
                ruleMargin = Color(0xFFF3722C), // sunset orange — same as light mode, vivid on dark too
                ruleBlue = Color(0xFF4A5A6A),
                accentAction = Color(0xFF5DC4A4), // brightened zomp for dark surface
                accentSoft = Color(0xFF2C5447), // deep zomp selection
                accentSecondary = Color(0xFF8AA0BB), // brightened slate
                accentPositive = Color(0xFFAFD58A), // brightened pistachio
                accentWarning = Color(0xFFFFD773), // brightened saffron
                accentDanger = Color(0xFFFF6669), // brightened crimson
                tintOverlay = Color(0x8C281C12),
                isDark = true,
            )
    }
}

/**
 * Ableton 14-color palette. Index 1..14 follows Live's "Color Index" exactly so a stored
 * `colorTag` round-trips. Index 0 = no color.
 */
val AbletonPalette: Map<Int, Color> =
    mapOf(
        1 to Color(0xFFFF94A6),
        2 to Color(0xFFFFA529),
        3 to Color(0xFFCC9927),
        4 to Color(0xFFF7F47C),
        5 to Color(0xFFBBFF26),
        6 to Color(0xFF1AFF2F),
        7 to Color(0xFF25FFA8),
        8 to Color(0xFF5CFFE8),
        9 to Color(0xFF8BC5FF),
        10 to Color(0xFF5480E4),
        11 to Color(0xFF92A7FF),
        12 to Color(0xFFD86CE4),
        13 to Color(0xFFE553A0),
        14 to Color(0xFFFFFFFF),
    )

/**
 * Pre-computed `light`/`dark` decision per Ableton color (WCAG-AA pass at 4.5:1 against
 * `inkOnStripLight`/`inkOnStripDark`). Mirrors `web/src/theme/contrast-table.ts`.
 */
val AbletonContrast: Map<Int, OnStrip> =
    mapOf(
        1 to OnStrip.Dark,
        2 to OnStrip.Dark,
        3 to OnStrip.Dark,
        4 to OnStrip.Dark,
        5 to OnStrip.Dark,
        6 to OnStrip.Light,
        7 to OnStrip.Dark,
        8 to OnStrip.Light,
        9 to OnStrip.Light,
        10 to OnStrip.Light,
        11 to OnStrip.Dark,
        12 to OnStrip.Light,
        13 to OnStrip.Light,
        14 to OnStrip.Dark,
    )

enum class OnStrip { Light, Dark }

val LocalAppColors = staticCompositionLocalOf<AppColors> { AppColors.Light }
