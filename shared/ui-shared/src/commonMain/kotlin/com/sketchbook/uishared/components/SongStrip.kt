package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AbletonPalette
import com.sketchbook.uishared.theme.AppTheme

/**
 * Stationery-styled project row mirroring `web/src/components/data/SongStrip.tsx`. Two lines:
 *
 *  - Top: 6dp×28dp color bar (Ableton color tag), name + optional warning glyph, mono stats
 *    columns (bpm / meter / tracks / length / effort), relative timestamp.
 *  - Bottom (muted): parent directory + tag chips.
 *
 * Hover lifts to the sunken paper tone. Click anywhere on the row drills in via [onOpen].
 */
data class SongStripData(
    val id: Long,
    val name: String,
    val parentDir: String,
    val tempo: Double?,
    val timeSigNum: Int?,
    val timeSigDen: Int?,
    val trackCount: Int?,
    val lengthSeconds: Double?,
    /** 0..100 effort score (per the `compute_effort` reference impl). Null until parser fills it. */
    val effortScore: Int?,
    val lastModifiedRelative: String?,
    val colorTag: Int?,
    val tags: List<String>,
    val warning: String? = null,
    val sync: SongSyncBadge? = null,
    /** Number of `.als` variants in this project group. 1 = singleton (no version card treatment). */
    val variantCount: Int = 1,
)

/**
 * Per-row cloud-sync indicator: small glyph + tone. Mirrors the pip web/'s SongStrip carried
 * in its trailing slot.
 */
enum class SongSyncBadge(val glyph: String, val description: String) {
    Synced("☁︎", "synced"),
    Pending("☁︎↑", "upload pending"),
    Uploading("☁︎↑", "uploading"),
    Conflict("⚠", "conflict"),
    LocalOnly("⊘", "local only"),
    Unknown("·", "unknown"),
}

@Composable
fun SongStrip(
    data: SongStripData,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLaunch: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val cornerDp = AppTheme.spacing.cornerCard
    val shape = RoundedCornerShape(cornerDp)
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val bg = if (isHovered) colors.surfaceSunken else colors.surfaceCard
    val colorVar = data.colorTag?.let { AbletonPalette[it] } ?: colors.ruleLineStrong

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, colors.ruleLine, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Color bar
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colorVar),
            )
            // Name + warning
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProvideContentColor(colors.inkPrimary) {
                    Text(
                        text = data.name,
                        style = AppTheme.typography.bodyEmphasis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (data.warning != null) {
                    ProvideContentColor(colors.accentWarning) {
                        Text("⚠", style = AppTheme.typography.caption)
                    }
                }
            }
            // Mono stat columns
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stat("bpm", data.tempo?.let { it.toInt().toString() } ?: "—", 36.dp)
                Stat("meter", fmtTimeSig(data.timeSigNum, data.timeSigDen), 36.dp)
                Stat("tracks", data.trackCount?.toString() ?: "—", 38.dp)
                Stat("length", fmtSeconds(data.lengthSeconds), 42.dp)
                // Effort 0..100 (matches web SongStrip + Python compute_effort). >=60 hits the
                // forgotten-gem threshold so it earns a terracotta accent.
                Stat("effort", data.effortScore?.toString() ?: "—", 36.dp, accent = (data.effortScore ?: 0) >= 60)
            }
            Spacer(Modifier.width(4.dp))
            // Sync pip
            if (data.sync != null) SyncPip(data.sync)
            // Relative timestamp
            Box(modifier = Modifier.requiredWidthIn(min = 64.dp)) {
                ProvideContentColor(colors.inkMuted) {
                    Text(
                        data.lastModifiedRelative ?: "—",
                        style = AppTheme.typography.mono.copy(fontSize = 11.sp()),
                    )
                }
            }
            if (onLaunch != null) {
                LaunchIcon(onLaunch)
            }
        }
        Row(
            modifier = Modifier.padding(start = 20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProvideContentColor(colors.inkMuted) {
                Text(
                    data.parentDir,
                    style = AppTheme.typography.mono.copy(fontSize = 11.sp()),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            if (data.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in data.tags.take(3)) {
                        TagChip(t)
                    }
                    if (data.tags.size > 3) {
                        ProvideContentColor(colors.inkMuted) {
                            Text("+${data.tags.size - 3}", style = AppTheme.typography.caption)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    width: androidx.compose.ui.unit.Dp,
    accent: Boolean = false,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.widthIn(min = width),
        horizontalAlignment = Alignment.Start,
    ) {
        ProvideContentColor(colors.inkMuted) {
            Text(label, style = AppTheme.typography.mono.copy(fontSize = 9.sp(), letterSpacing = 0.5.sp()))
        }
        ProvideContentColor(if (accent) colors.accentAction else colors.inkSecondary) {
            Text(
                value,
                style = AppTheme.typography.mono.copy(
                    fontSize = 12.sp(),
                    fontWeight = if (accent) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                ),
            )
        }
    }
}

@Composable
private fun SyncPip(badge: SongSyncBadge) {
    val colors = AppTheme.colors
    val tint = when (badge) {
        SongSyncBadge.Synced -> colors.accentPositive
        SongSyncBadge.Pending,
        SongSyncBadge.Uploading -> colors.pinBlue
        SongSyncBadge.Conflict -> colors.accentWarning
        SongSyncBadge.LocalOnly -> colors.inkFaint
        SongSyncBadge.Unknown -> colors.inkFaint
    }
    Box(
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        ProvideContentColor(tint) {
            Text(
                badge.glyph,
                style = AppTheme.typography.mono.copy(fontSize = 13.sp()),
            )
        }
    }
}

@Composable
private fun TagChip(label: String) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.tintBlue)
            .border(1.dp, colors.ruleLine, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        ProvideContentColor(colors.inkSecondary) {
            Text(label, style = AppTheme.typography.mono.copy(fontSize = 10.sp()))
        }
    }
}

@Composable
private fun LaunchIcon(onLaunch: () -> Unit) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) colors.tintBlue else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onLaunch)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        ProvideContentColor(colors.inkSecondary) {
            Text("↗", style = AppTheme.typography.body)
        }
    }
}

private fun fmtSeconds(sec: Double?): String {
    if (sec == null) return "—"
    val total = sec.toInt()
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun fmtTimeSig(num: Int?, den: Int?): String =
    if (num == null || den == null) "—" else "$num/$den"

private fun Int.sp(): androidx.compose.ui.unit.TextUnit =
    androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

private fun Double.sp(): androidx.compose.ui.unit.TextUnit =
    androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
