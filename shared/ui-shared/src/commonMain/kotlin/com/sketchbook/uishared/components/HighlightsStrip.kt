package com.sketchbook.uishared.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * The home highlights strip: six small chips above the project shelves, one per category, with
 * a colored dot + title + count. Mirrors `web/src/components/data/HighlightsStrip.tsx`.
 *
 * Empty categories stay in the row (dim) so the layout doesn't shift between scans.
 */
data class HighlightChip(
    val id: String,
    val title: String,
    val count: Int,
    val dotColor: Color,
    val tint: Color,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HighlightsStrip(
    chips: List<HighlightChip>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (chip in chips) {
            val empty = chip.count == 0
            val borderColor = if (empty) colors.ruleLine else colors.ruleLineStrong
            val tint = if (empty) colors.surfaceCard else chip.tint
            val alpha = if (empty) 0.55f else 1f
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(tint)
                        .border(1.dp, borderColor, RoundedCornerShape(50))
                        .clickable { onSelect(chip.id) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (empty) colors.ruleLineStrong else chip.dotColor),
                )
                ProvideContentColor(if (empty) colors.inkMuted else colors.inkSecondary) {
                    Text(
                        chip.title.uppercase(),
                        style =
                            AppTheme.typography.mono.copy(
                                fontSize =
                                    androidx.compose.ui.unit
                                        .TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                                letterSpacing =
                                    androidx.compose.ui.unit
                                        .TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                    )
                }
                ProvideContentColor(colors.inkMuted) {
                    Text(
                        "· ${chip.count}",
                        style =
                            AppTheme.typography.mono.copy(
                                fontSize =
                                    androidx.compose.ui.unit
                                        .TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                    )
                }
            }
        }
    }
}
