package com.sketchbook.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.sketchbook.repo.LibraryHealth
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * PR-BB: library health chip shown at the bottom of the sidebar. Reuses the pill-chip idiom from
 * Browse's tempo/key filters (rounded-50 shape, mono caption, 1dp border, surfaceCard tint) so it
 * reads as a secondary stat rather than a new visual primitive.
 *
 * Threshold colors map to existing tokens — no new colors per `feedback_color_restraint`:
 *   * `>= 90%` → `accentAction` (terracotta)
 *   * `70–89%` → `accentWarning` (mustard)
 *   * `< 70%`  → `accentDanger`
 *
 * Click opens [HealthBreakdownPopup] with one bullet row per non-null signal. Each row is
 * clickable; for V1 the click only dismisses the popup (the projects-list filter pre-set is
 * tracked as bonus work in the PR-BB plan and is left to a follow-up to keep the diff focused).
 */
@Composable
fun HealthChip(
    health: LibraryHealth,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    val percent = (health.compositePercent * 100).toInt()
    val numberColor: Color = when {
        health.total <= 0 -> colors.inkMuted
        percent >= 90 -> colors.accentAction
        percent >= 70 -> colors.accentWarning
        else -> colors.accentDanger
    }
    val label = if (health.total <= 0) "Health: —" else "Health: $percent%"

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceCard)
                .border(1.dp, colors.ruleLineStrong, RoundedCornerShape(50))
                .clickable(enabled = health.total > 0) { open = !open }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideContentColor(colors.inkSecondary) {
                Text(
                    "Health: ".uppercase(),
                    style = AppTheme.typography.mono.copy(
                        fontSize = TextUnit(10f, TextUnitType.Sp),
                        letterSpacing = TextUnit(0.8f, TextUnitType.Sp),
                    ),
                )
            }
            ProvideContentColor(numberColor) {
                Text(
                    if (health.total <= 0) "—" else "$percent%",
                    style = AppTheme.typography.mono.copy(
                        fontSize = TextUnit(10f, TextUnitType.Sp),
                        letterSpacing = TextUnit(0.8f, TextUnitType.Sp),
                    ),
                )
            }
        }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true),
            ) {
                HealthBreakdownPopup(
                    health = health,
                    onRowClick = { open = false },
                )
            }
        }
    }
}

/**
 * Per-signal breakdown popup. Rows are rendered only for signals with data — `pluginInstalled`
 * and `stageNotStuck` are `null` until PR-T and PR-R land, so today the popup shows two rows.
 *
 * Click on a row dismisses the popup. Wiring the projects-list filter pre-set ("show me only the
 * unsynced projects") is tracked as a bonus in the PR-BB plan and intentionally deferred — adding
 * it requires a cross-screen intent the sidebar doesn't have today, and the plan explicitly
 * permits the click to be a no-op for V1.
 */
@Composable
private fun HealthBreakdownPopup(
    health: LibraryHealth,
    onRowClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 280.dp)
            .clip(RoundedCornerShape(AppTheme.spacing.cornerCard))
            .background(colors.surfaceCard)
            .border(1.dp, colors.ruleLineStrong, RoundedCornerShape(AppTheme.spacing.cornerCard))
            .padding(AppTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(colors.inkSecondary) {
            Text("Library health", style = AppTheme.typography.bodyEmphasis)
        }
        Spacer(Modifier.height(2.dp))
        BreakdownRow(
            label = "Synced",
            count = health.synced,
            total = health.total,
            onClick = onRowClick,
        )
        BreakdownRow(
            label = "Samples clean",
            count = health.sampleClean,
            total = health.total,
            onClick = onRowClick,
        )
        // PR-T plugs in here: BreakdownRow(label = "Plugins installed", count = health.pluginInstalled!!, …)
        health.pluginInstalled?.let {
            BreakdownRow(label = "Plugins installed", count = it, total = health.total, onClick = onRowClick)
        }
        // PR-R plugs in here: BreakdownRow(label = "Active (not stuck)", count = health.stageNotStuck!!, …)
        health.stageNotStuck?.let {
            BreakdownRow(label = "Active (not stuck)", count = it, total = health.total, onClick = onRowClick)
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    count: Int,
    total: Int,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val percent = if (total <= 0) 0 else ((count.toFloat() / total) * 100).toInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.spacing.cornerSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideContentColor(colors.inkPrimary) {
            Text("• $label", style = AppTheme.typography.body, modifier = Modifier.weight(1f))
        }
        ProvideContentColor(colors.inkMuted) {
            Text(
                "$percent% ($count / $total)",
                style = AppTheme.typography.mono.copy(
                    fontSize = TextUnit(11f, TextUnitType.Sp),
                ),
            )
        }
    }
}
