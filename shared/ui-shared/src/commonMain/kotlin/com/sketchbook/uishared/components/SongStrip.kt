package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
    /** PR-R: Stage classification rendered as a small inline chip. The label comes from the
     *  override-or-inferred stage; null = no chip drawn (no rule matched). */
    val stage: SongStageChip? = null,
)

/**
 * PR-R: stage-classification chip rendered next to the project name. The five stages map to
 * five existing palette tokens (no new colors). Carrying both the label and the tone here keeps
 * the chip rendering pure — the upstream feature module decides override-vs-inferred and just
 * passes the rendered shape through.
 */
data class SongStageChip(
    val label: String,
    val tone: SongStageTone,
)

enum class SongStageTone { Sketch, InProgress, Mixing, Done, Stuck }

/**
 * Per-row cloud-sync indicator: small glyph + tone. Mirrors the pip web/'s SongStrip carried
 * in its trailing slot.
 */
enum class SongSyncBadge(
    val glyph: String,
    val description: String,
) {
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
    val colorVar = data.colorTag?.let { AbletonPalette[it] } ?: colors.ruleLineStrong
    val isGroup = data.variantCount > 1
    val borderColor = if (data.warning != null) colors.accentDanger.copy(alpha = 0.4f) else colors.ruleLine

    Box(modifier = modifier.fillMaxWidth()) {
        if (isGroup) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = 3.dp, y = 3.dp)
                        .clip(shape)
                        .background(colors.surfaceSunken)
                        .border(1.dp, colors.ruleLine, shape),
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surfaceCard)
                    .border(1.dp, borderColor, shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpen,
                    ).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val widthDp = maxWidth.value
                val showLength = widthDp >= 880f
                val showMeter = widthDp >= 760f
                val showTracks = widthDp >= 640f
                val showBpm = widthDp >= 520f
                val tagsLimit = if (widthDp >= 760f) 3 else 2

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Color bar
                        Box(
                            modifier =
                                Modifier
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
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (data.warning != null) {
                                ProvideContentColor(colors.accentWarning) {
                                    Text("⚠", style = AppTheme.typography.caption)
                                }
                            }
                        }
                        if (data.stage != null) {
                            StageChip(data.stage)
                        }
                        if (data.variantCount > 1) {
                            VersionPill(count = data.variantCount)
                        }
                        // Mono stat columns
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (showBpm) Stat("bpm", data.tempo?.let { it.toInt().toString() } ?: "—", 36.dp)
                            if (showMeter) Stat("meter", fmtTimeSig(data.timeSigNum, data.timeSigDen), 36.dp)
                            if (showTracks) Stat("tracks", data.trackCount?.toString() ?: "—", 38.dp)
                            if (showLength) Stat("length", fmtSeconds(data.lengthSeconds), 42.dp)
                            // Effort 0..100 (matches web SongStrip + Python compute_effort). >=60 hits the
                            // forgotten-gem threshold so it earns a terracotta accent.
                            Stat(
                                label = "effort",
                                value = data.effortScore?.toString() ?: "—",
                                width = 36.dp,
                                accent = (data.effortScore ?: 0) >= 60,
                                empty = data.effortScore == null || data.effortScore == 0,
                            )
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
                                for (t in data.tags.take(tagsLimit)) {
                                    TagChip(t)
                                }
                                if (data.tags.size > tagsLimit) {
                                    ProvideContentColor(colors.inkMuted) {
                                        Text("+${data.tags.size - tagsLimit}", style = AppTheme.typography.caption)
                                    }
                                }
                            }
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
    empty: Boolean = false,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.widthIn(min = width),
        horizontalAlignment = Alignment.Start,
    ) {
        ProvideContentColor(colors.inkMuted) {
            Text(label, style = AppTheme.typography.mono.copy(fontSize = 9.sp(), letterSpacing = 0.5.sp()))
        }
        val tone =
            when {
                empty -> colors.inkFaint
                accent -> colors.accentAction
                else -> colors.inkSecondary
            }
        ProvideContentColor(tone) {
            Text(
                value,
                style =
                    AppTheme.typography.mono.copy(
                        fontSize = 12.sp(),
                        fontWeight =
                            if (accent) {
                                androidx.compose.ui.text.font.FontWeight.SemiBold
                            } else {
                                androidx.compose.ui.text.font.FontWeight.Normal
                            },
                    ),
            )
        }
    }
}

@Composable
private fun SyncPip(badge: SongSyncBadge) {
    val colors = AppTheme.colors
    val tint =
        when (badge) {
            SongSyncBadge.Synced -> colors.accentPositive

            SongSyncBadge.Pending,
            SongSyncBadge.Uploading,
            -> colors.pinBlue

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

/**
 * PR-R: stage classification chip. Same pill geometry as [VersionPill] (50% rounded, 1dp ruleLine
 * border, mono caption type) so the chip family stays visually coherent on the row. The five
 * stages map to existing palette tokens — no new colors introduced.
 */
@Composable
private fun StageChip(chip: SongStageChip) {
    val colors = AppTheme.colors
    val tint =
        when (chip.tone) {
            SongStageTone.Sketch -> colors.tintCream
            SongStageTone.InProgress -> colors.accentAction
            SongStageTone.Mixing -> colors.accentSecondary
            SongStageTone.Done -> colors.accentPositive
            SongStageTone.Stuck -> colors.accentDanger
        }
    // Sketch is the only "tint" tone (already light); the four accent tones are saturated, so
    // ink-on-tint readability flips to the kraft-cream surface for legibility. ruleLine border
    // stays uniform across both modes per `feedback_color_restraint`.
    val isLight = chip.tone == SongStageTone.Sketch
    val fg = if (isLight) colors.inkSecondary else colors.surfaceCard
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(tint)
                .border(1.dp, colors.ruleLine, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ProvideContentColor(fg) {
            Text(
                chip.label,
                style =
                    AppTheme.typography.mono.copy(
                        fontSize = 10.sp(),
                        letterSpacing = 0.5.sp(),
                    ),
            )
        }
    }
}

@Composable
private fun VersionPill(count: Int) {
    val colors = AppTheme.colors
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.tintCream)
                .border(1.dp, colors.ruleLine, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ProvideContentColor(colors.inkSecondary) {
            Text(
                "v$count",
                style = AppTheme.typography.mono.copy(fontSize = 11.sp()),
            )
        }
    }
}

@Composable
private fun TagChip(label: String) {
    val colors = AppTheme.colors
    Box(
        modifier =
            Modifier
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
        modifier =
            Modifier
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

private fun fmtTimeSig(
    num: Int?,
    den: Int?,
): String = if (num == null || den == null) "—" else "$num/$den"

private fun Int.sp(): androidx.compose.ui.unit.TextUnit =
    androidx.compose.ui.unit
        .TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

private fun Double.sp(): androidx.compose.ui.unit.TextUnit =
    androidx.compose.ui.unit
        .TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
