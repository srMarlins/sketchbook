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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.sketchbook.core.PluginFormat
import com.sketchbook.repo.MissingPluginRow
import com.sketchbook.repo.MissingPluginSummary
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * PR-T: Home coverage chip — surfaces "N plugins missing affecting M projects" when the presence
 * probe found anything missing. Hidden entirely when the summary is null/loading or both counts
 * are zero, so the chrome stays quiet for users whose libraries are healthy.
 *
 * Visual idiom matches the existing Browse FilterChip / sidebar HealthChip pill — rounded-50
 * border, mono caption, `surfaceCard` tint. Foreground number uses `accentDanger` (already used
 * for missing-sample state) so the color signals the same "things are broken" semantics across
 * the app — no new color token introduced.
 *
 * Click opens [MissingPluginsPopup] listing each missing plugin with its affected-project count.
 * V1 tap-on-row dismisses the popup; drilling into the affected projects per-plugin is deferred
 * to a follow-up — the existing `selectProjectsUsingPlugin` query already supports it but the
 * Home → ProjectList intent wiring isn't there yet.
 */
@Composable
fun MissingPluginsChip(
    summary: MissingPluginSummary?,
    coverage: List<MissingPluginRow>,
    modifier: Modifier = Modifier,
) {
    if (summary == null || summary.isEmpty) return

    val colors = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    val pluginsLabel = if (summary.missingPluginCount == 1) "plugin" else "plugins"
    val projectsLabel = if (summary.affectedProjects == 1) "project" else "projects"

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceCard)
                .border(1.dp, colors.ruleLineStrong, RoundedCornerShape(50))
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideContentColor(colors.accentDanger) {
                Text(
                    "${summary.missingPluginCount} $pluginsLabel missing".uppercase(),
                    style = AppTheme.typography.mono.copy(
                        fontSize = TextUnit(10f, TextUnitType.Sp),
                        letterSpacing = TextUnit(0.8f, TextUnitType.Sp),
                    ),
                )
            }
            Spacer(Modifier.padding(horizontal = 4.dp))
            ProvideContentColor(colors.inkSecondary) {
                Text(
                    "· ${summary.affectedProjects} $projectsLabel".uppercase(),
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
                MissingPluginsPopup(
                    coverage = coverage,
                    onRowClick = { open = false },
                )
            }
        }
    }
}

@Composable
private fun MissingPluginsPopup(
    coverage: List<MissingPluginRow>,
    onRowClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 360.dp)
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(AppTheme.spacing.cornerCard))
            .background(colors.surfaceCard)
            .border(1.dp, colors.ruleLineStrong, RoundedCornerShape(AppTheme.spacing.cornerCard))
            .padding(AppTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(colors.inkSecondary) {
            Text("Missing plugins", style = AppTheme.typography.bodyEmphasis)
        }
        Spacer(Modifier.height(2.dp))
        if (coverage.isEmpty()) {
            ProvideContentColor(colors.inkMuted) {
                Text("Loading…", style = AppTheme.typography.body)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (row in coverage) {
                    MissingPluginRowView(row = row, onClick = onRowClick)
                }
            }
        }
    }
}

@Composable
private fun MissingPluginRowView(
    row: MissingPluginRow,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val formatTag = when (row.format) {
        PluginFormat.Vst2 -> "VST2"
        PluginFormat.Vst3 -> "VST3"
        PluginFormat.Au -> "AU"
        PluginFormat.AbletonNative -> "Native"
        PluginFormat.Unknown -> ""
    }
    val countLabel = if (row.affectedProjects == 1) "1 project" else "${row.affectedProjects} projects"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.spacing.cornerSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideContentColor(colors.inkPrimary) {
            Text(
                if (formatTag.isEmpty()) row.name else "${row.name} ($formatTag)",
                style = AppTheme.typography.body,
                modifier = Modifier.weight(1f),
            )
        }
        ProvideContentColor(colors.accentDanger) {
            Text(
                countLabel,
                style = AppTheme.typography.mono.copy(
                    fontSize = TextUnit(11f, TextUnitType.Sp),
                ),
            )
        }
    }
}
