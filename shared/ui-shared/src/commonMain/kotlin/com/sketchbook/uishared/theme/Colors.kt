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
        val Light: AppColors = run {
            val paperBase = Color(0xFFF8F1E3)
            val paperRaised = Color(0xFFFFFAEE)
            val paperSunken = Color(0xFFEFE4CB)
            val tintBlue = Color(0xFFE7EEF2)
            val tintRose = Color(0xFFF5E4DD)
            val tintSage = Color(0xFFE8EEE0)
            val tintCream = Color(0xFFF5ECD6)
            val accent = Color(0xFFB54A3A) // terracotta
            val accentPositive = Color(0xFF4F7A4A) // sage green
            val accentWarning = Color(0xFFC79030) // mustard
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
                ruleMargin = Color(0xFFC47A8B),
                ruleBlue = Color(0xFFB9C8D6),
                accentAction = accent,
                accentSoft = Color(0xFFE3A799),
                accentSecondary = Color(0xFF8C6F64),
                accentPositive = accentPositive,
                accentWarning = accentWarning,
                accentDanger = accent,
                pinGreen = accentPositive,
                pinBlue = Color(0xFF5480E4),
                pinOrange = accentWarning,
                pinPurple = Color(0xFFD86CE4),
                pinRed = accent,
                pinYellow = accentWarning,
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

        val Dark: AppColors = Light.copy(
            surfacePage = Color(0xFF1A1714),
            surfaceCard = Color(0xFF383330),
            surfaceSunken = Color(0xFF131110),
            surfacePanel = Color(0xFF383330),
            tintBlue = Color(0xFF36505F),
            tintRose = Color(0xFF5A3A35),
            tintSage = Color(0xFF3A4F37),
            tintCream = Color(0xFF564633),
            surfaceOverlay = Color(0x59080503),
            inkPrimary = Color(0xFFF5ECD8),
            inkSecondary = Color(0xFFD6C9AF),
            inkMuted = Color(0xFFA39884),
            inkFaint = Color(0xFF5C5448),
            inkOnFill = Color(0xFF1D1A17),
            ruleLine = Color(0x24DCC8AA),
            ruleLineStrong = Color(0x3DDCC8AA),
            ruleMargin = Color(0xFFB8615F),
            ruleBlue = Color(0xFF4A5A6A),
            accentAction = Color(0xFFD97757),
            accentSoft = Color(0xFF6B3B30),
            accentSecondary = Color(0xFFA08A7C),
            accentPositive = Color(0xFF82A878),
            accentWarning = Color(0xFFE0B260),
            accentDanger = Color(0xFFD97757),
            tintOverlay = Color(0x8C281C12),
            isDark = true,
        )
    }
}

/**
 * Ableton 14-color palette. Index 1..14 follows Live's "Color Index" exactly so a stored
 * `colorTag` round-trips. Index 0 = no color.
 */
val AbletonPalette: Map<Int, Color> = mapOf(
    1 to Color(0xFFFF94A6), 2 to Color(0xFFFFA529), 3 to Color(0xFFCC9927), 4 to Color(0xFFF7F47C),
    5 to Color(0xFFBBFF26), 6 to Color(0xFF1AFF2F), 7 to Color(0xFF25FFA8), 8 to Color(0xFF5CFFE8),
    9 to Color(0xFF8BC5FF), 10 to Color(0xFF5480E4), 11 to Color(0xFF92A7FF), 12 to Color(0xFFD86CE4),
    13 to Color(0xFFE553A0), 14 to Color(0xFFFFFFFF),
)

/**
 * Pre-computed `light`/`dark` decision per Ableton color (WCAG-AA pass at 4.5:1 against
 * `inkOnStripLight`/`inkOnStripDark`). Mirrors `web/src/theme/contrast-table.ts`.
 */
val AbletonContrast: Map<Int, OnStrip> = mapOf(
    1 to OnStrip.Dark, 2 to OnStrip.Dark, 3 to OnStrip.Dark, 4 to OnStrip.Dark, 5 to OnStrip.Dark,
    6 to OnStrip.Light, 7 to OnStrip.Dark, 8 to OnStrip.Light, 9 to OnStrip.Light,
    10 to OnStrip.Light, 11 to OnStrip.Dark, 12 to OnStrip.Light, 13 to OnStrip.Light, 14 to OnStrip.Dark,
)

enum class OnStrip { Light, Dark }

val LocalAppColors = staticCompositionLocalOf<AppColors> { AppColors.Light }
