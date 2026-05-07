package com.sketchbook.uishared.components

import androidx.compose.runtime.Composable
import com.sketchbook.uishared.theme.AppTheme

/**
 * Stable, non-themed identifier the row UI maps to a real `AppColors` token. Lives in
 * `ui-shared` so feature modules (proposals, journal, future surfaces) can share the same set
 * without duplicating an enum or coupling them through their formatters.
 */
enum class VerbTint { Action, Add, Remove, Repair, Neutral }

/**
 * Compact colored pill — used by Proposals rows for the action verb (MOVE, RENAME, ARCHIVE) and
 * by Journal rows for the same action surface so the two queues read consistently. Colors come
 * from [AppTheme] tints; `Neutral` falls back to the sunken surface so unknown action types
 * still render as a pill (just uncolored).
 */
@Composable
fun VerbPill(
    verb: String,
    tint: VerbTint,
) {
    val bg =
        when (tint) {
            VerbTint.Action -> AppTheme.colors.tintBlue
            VerbTint.Add -> AppTheme.colors.tintSage
            VerbTint.Remove -> AppTheme.colors.tintRose
            VerbTint.Repair -> AppTheme.colors.tintCream
            VerbTint.Neutral -> AppTheme.colors.surfaceSunken
        }
    Badge(color = bg) {
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(verb.uppercase(), style = AppTheme.typography.caption, softWrap = false, maxLines = 1)
        }
    }
}
