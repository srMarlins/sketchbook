package com.sketchbook.uishared.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Root theme provider. Wrap the app's content in `AppTheme { ... }` to make `LocalAppColors`,
 * `LocalAppTypography`, and `LocalAppSpacing` available downstream.
 *
 * Theme is selected by the caller (`dark = true|false`); we never auto-detect because the app
 * persists a per-user preference (PR-17 settings module).
 */
@Composable
fun AppTheme(
    dark: Boolean = false,
    colors: AppColors = if (dark) AppColors.Dark else AppColors.Light,
    typography: AppTypography = AppTypography.Default,
    spacing: AppSpacing = AppSpacing.Default,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalAppTypography provides typography,
        LocalAppSpacing provides spacing,
        content = content,
    )
}

object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
    val typography: AppTypography
        @Composable get() = LocalAppTypography.current
    val spacing: AppSpacing
        @Composable get() = LocalAppSpacing.current
}
