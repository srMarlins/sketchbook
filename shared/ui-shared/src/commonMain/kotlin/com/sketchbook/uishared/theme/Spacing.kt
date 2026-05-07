package com.sketchbook.uishared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing + radii. Card radius matches the web `--radius-card: 6px` so paper cards on the
 * desktop read the same as on the web.
 */
@Immutable
data class AppSpacing(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerCard: Dp,
    val cornerInput: Dp,
    val ruleHairline: Dp,
    val ruleLineHeight: Dp,
) {
    companion object {
        val Default: AppSpacing =
            AppSpacing(
                xs = 4.dp,
                sm = 8.dp,
                md = 12.dp,
                lg = 16.dp,
                xl = 24.dp,
                xxl = 32.dp,
                cornerSmall = 4.dp,
                cornerMedium = 8.dp,
                cornerCard = 6.dp,
                cornerInput = 4.dp,
                ruleHairline = 1.dp,
                ruleLineHeight = 28.dp,
            )
    }
}

val LocalAppSpacing = staticCompositionLocalOf<AppSpacing> { AppSpacing.Default }
