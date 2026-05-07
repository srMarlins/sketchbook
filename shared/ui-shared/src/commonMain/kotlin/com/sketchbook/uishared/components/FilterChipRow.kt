package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Single option in a [FilterChipRow]. [count] is rendered as a parenthesised caption next
 * to [label] when non-null (e.g. "Pending (12)").
 */
data class FilterChipOption<T>(
    val value: T,
    val label: String,
    val count: Int? = null,
)

/**
 * Stateless single-select chip row. Generic over [T] so it can drive any sealed-class /
 * enum filter (source, action-type, date-range, etc.) without bespoke per-screen widgets.
 *
 * Selection is owned by the caller — this component just renders [options] and emits
 * [onSelected] when the user taps a chip. Selected fill uses `accentSoft`, unselected uses
 * `surfaceCard`; both are existing palette tokens.
 */
@Composable
fun <T> FilterChipRow(
    options: List<FilterChipOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Horizontal scroll so chip rows never wrap into a second line and chip labels never break
    // mid-word when the parent column squeezes (the inbox columns can shrink to ~250dp).
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (opt in options) {
            val isSelected = opt.value == selected
            val shape = RoundedCornerShape(AppTheme.spacing.cornerSmall)
            val bg = if (isSelected) AppTheme.colors.accentSoft else AppTheme.colors.surfaceCard
            val fg = if (isSelected) AppTheme.colors.inkPrimary else AppTheme.colors.inkSecondary
            Row(
                modifier =
                    Modifier
                        .clip(shape)
                        .background(bg)
                        .border(1.dp, AppTheme.colors.ruleLine, shape)
                        .clickable { onSelected(opt.value) }
                        .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProvideContentColor(fg) {
                    Text(opt.label, style = AppTheme.typography.body, softWrap = false, maxLines = 1)
                    if (opt.count != null) {
                        Text("(${opt.count})", style = AppTheme.typography.caption, softWrap = false, maxLines = 1)
                    }
                }
            }
        }
    }
}
