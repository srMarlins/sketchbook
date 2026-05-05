package com.sketchbook.uishared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Stationery-aesthetic palette per `docs/plans/2026-05-04-sketchbook-ui.md` §3. Light theme is
 * cream/parchment with walnut accents; dark theme deepens the walnut and tints overlays.
 *
 * Palette is intentionally hand-rolled (no Material3 ColorScheme): it lets the visual language
 * stay paper-like instead of inheriting Material elevation/tonal surface noise.
 */
@Immutable
data class AppColors(
    val surfaceDesk: Color,
    val surfacePage: Color,
    val surfaceStripBase: Color,
    val surfaceCorkboard: Color,
    val surfacePanel: Color,
    val surfaceKraft: Color,
    val tintOverlay: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val inkOnStripLight: Color,
    val inkOnStripDark: Color,
    val ruleLine: Color,
    val accentAction: Color,
    val accentSecondary: Color,
    val pinGreen: Color,
    val pinBlue: Color,
    val pinOrange: Color,
    val pinPurple: Color,
    val pinRed: Color,
    val pinYellow: Color,
    val isDark: Boolean,
) {
    companion object {
        val Light: AppColors = AppColors(
            surfaceDesk = Color(0xFF6B4A2B),
            surfacePage = Color(0xFFF5EFDF),
            surfaceStripBase = Color(0xFFFFFFFF),
            surfaceCorkboard = Color(0xFFC4A373),
            surfacePanel = Color(0xFFF5EFDF),
            surfaceKraft = Color(0xFFC8A472),
            tintOverlay = Color.Transparent,
            inkPrimary = Color(0xFF2A2422),
            inkSecondary = Color(0xFF3A3430),
            inkMuted = Color(0xFF6B605A),
            inkOnStripLight = Color(0xFF1A1614),
            inkOnStripDark = Color(0xFFF8F4EC),
            ruleLine = Color(0x29281C12), // rgba(60,50,40,0.16) approximated via 0x29 alpha
            accentAction = Color(0xFFD63A3A),
            accentSecondary = Color(0xFF9A9692),
            pinGreen = Color(0xFF4CAF50),
            pinBlue = Color(0xFF3A7BD5),
            pinOrange = Color(0xFFF29128),
            pinPurple = Color(0xFF8A4FBF),
            pinRed = Color(0xFFD63A3A),
            pinYellow = Color(0xFFF0C419),
            isDark = false,
        )

        val Dark: AppColors = Light.copy(
            surfacePage = Color(0xFFF5EFDF),
            surfaceDesk = Color(0xFF3D2814),
            tintOverlay = Color(0x8C281C12), // rgba(40,28,18,0.55)
            isDark = true,
        )
    }
}

/**
 * Ableton 14-color palette. Index 1..14 follows Live's "Color Index" exactly so a stored
 * `colorTag` round-trips. Index 0 = no color.
 */
val AbletonPalette: Map<Int, Color> = mapOf(
    1 to Color(0xFFFF6F6F), 2 to Color(0xFFFF9933), 3 to Color(0xFFFFCC33), 4 to Color(0xFFFFFF66),
    5 to Color(0xFFC2FF66), 6 to Color(0xFF66CC66), 7 to Color(0xFF66CCFF), 8 to Color(0xFF6699FF),
    9 to Color(0xFF9966FF), 10 to Color(0xFFCC66CC), 11 to Color(0xFFFF66CC), 12 to Color(0xFFCC9966),
    13 to Color(0xFF999999), 14 to Color(0xFFD8D8D8),
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
