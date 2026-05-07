package com.sketchbook.featuredetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.core.PluginFormat
import com.sketchbook.core.PluginRef
import com.sketchbook.repo.LockStatus
import com.sketchbook.repo.SampleEntry
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.LockBadge
import com.sketchbook.uishared.components.Pill
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.RowItem
import com.sketchbook.uishared.components.Tag
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Project-detail pane with tabs across the top and a body that swaps based on the selected tab.
 */
@Composable
fun ProjectDetailScreen(
    vm: ProjectDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    ProjectDetailContent(
        state = state,
        dispatch = vm::dispatch,
        modifier = modifier,
    )
}

@Composable
internal fun ProjectDetailContent(
    state: ProjectDetailViewModel.State,
    dispatch: (ProjectDetailViewModel.Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppTheme.colors.surfacePage)
                .padding(PaddingValues(AppTheme.spacing.md)),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        Header(
            state = state,
            onForceTakeLock = { dispatch(ProjectDetailViewModel.Intent.ForceTakeLock) },
            onOpenInLive = { dispatch(ProjectDetailViewModel.Intent.OpenInLive) },
        )
        Tabs(state.tab, onSelect = { dispatch(ProjectDetailViewModel.Intent.SelectTab(it)) })
        when (state.tab) {
            ProjectDetailViewModel.Tab.Overview -> OverviewTab(state)
            ProjectDetailViewModel.Tab.Tracks -> TracksTab(state)
            ProjectDetailViewModel.Tab.Samples -> SamplesTab(state.samples)
            ProjectDetailViewModel.Tab.Plugins -> PluginsTab(state.plugins)
            ProjectDetailViewModel.Tab.History -> HistoryTab(state)
        }
    }
}

@Composable
private fun Header(
    state: ProjectDetailViewModel.State,
    onForceTakeLock: () -> Unit,
    onOpenInLive: () -> Unit,
) {
    val row = state.row
    if (row == null) {
        Text(if (state.loading) "Loading…" else "Project not found", style = AppTheme.typography.title)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        Pill(colorIndex = row.colorTag, size = AppTheme.spacing.lg)
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = AppTheme.typography.display)
            Text(row.path.value, style = AppTheme.typography.caption)
        }
        LockSlot(state.lockStatus, onForceTakeLock = onForceTakeLock)
        Button(
            onClick = onOpenInLive,
            variant = ButtonVariant.Primary,
        ) { Text("Open in Live") }
    }
}

@Composable
private fun LockSlot(
    status: LockStatus,
    onForceTakeLock: () -> Unit,
) {
    when (status) {
        LockStatus.Free -> {
            Unit
        }

        // chrome stays clean when there's nothing to say
        is LockStatus.Ours -> {
            LockBadge(
                label = "editing here",
                color = AppTheme.colors.pinGreen,
            )
        }

        is LockStatus.HeldByOther -> {
            LockBadge(
                label = "locked",
                color = AppTheme.colors.accentSecondary,
                detail = status.ownerHostName,
                actionLabel = "Force-take",
                onAction = onForceTakeLock,
            )
        }

        is LockStatus.Stale -> {
            LockBadge(
                label = "stale lock",
                color = AppTheme.colors.pinOrange,
                detail = status.ownerHostName,
                actionLabel = "Take",
                onAction = onForceTakeLock,
            )
        }
    }
}

@Composable
private fun Tabs(
    current: ProjectDetailViewModel.Tab,
    onSelect: (ProjectDetailViewModel.Tab) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        ProjectDetailViewModel.Tab.values().forEach { tab ->
            val active = tab == current
            // Single CTA-green per page: keep the "Open in Live" button as the only filled
            // accent. Active tab reads via emphasis weight + a 2dp underline in ruleMargin
            // (the same notebook-margin red as the page header), staying visually quiet.
            val underline =
                if (active) {
                    Modifier.drawBehind {
                        drawLine(
                            color = colors.ruleMargin,
                            start =
                                androidx.compose.ui.geometry
                                    .Offset(0f, size.height - 1f),
                            end =
                                androidx.compose.ui.geometry
                                    .Offset(size.width, size.height - 1f),
                            strokeWidth = 1.5f,
                        )
                    }
                } else {
                    Modifier
                }
            ProvideContentColor(if (active) colors.inkPrimary else colors.inkSecondary) {
                Text(
                    text = tab.name,
                    style = if (active) AppTheme.typography.bodyEmphasis else AppTheme.typography.body,
                    modifier =
                        Modifier
                            .clickable { onSelect(tab) }
                            .padding(vertical = AppTheme.spacing.sm)
                            .then(underline),
                )
            }
        }
    }
}

@Composable
private fun OverviewTab(state: ProjectDetailViewModel.State) {
    val row = state.row ?: return
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
        FactGrid {
            row.tempo?.let { Fact(label = "tempo", value = "${it.toInt()} bpm") }
            row.key?.let { Fact(label = "key", value = it) }
            row.effectiveStage?.let { Fact(label = "stage", value = it.displayName) }
            row.effortScore?.let { Fact(label = "effort", value = it.toString()) }
            Fact(label = "tracks", value = row.trackCount.toString())
            row.lastSavedLiveVersion?.let { Fact(label = "saved with", value = "Live $it") }
        }
        if (row.missingSampleCount > 0) {
            ProvideContentColor(colors.accentSecondary) {
                Text(
                    "${row.missingSampleCount} missing sample${if (row.missingSampleCount == 1) "" else "s"} — see Samples tab.",
                    style = AppTheme.typography.body,
                )
            }
        }
        if (row.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                row.tags.forEach { Tag(label = it) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FactGrid(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        content = content,
    )
}

@Composable
private fun Fact(
    label: String,
    value: String,
) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ProvideContentColor(colors.inkMuted) {
            Text(label, style = AppTheme.typography.caption)
        }
        ProvideContentColor(colors.inkPrimary) {
            Text(value, style = AppTheme.typography.bodyEmphasis)
        }
    }
}

@Composable
private fun TracksTab(state: ProjectDetailViewModel.State) {
    val row = state.row
    if (row == null || row.trackCount <= 0) {
        EmptyState(
            title = "No tracks parsed",
            hint = "Track counts populate after the parser runs over this `.als`.",
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        Text("Total tracks: ${row.trackCount}", style = AppTheme.typography.body)
        row.tempo?.let { Text("Tempo: ${it.toInt()} bpm", style = AppTheme.typography.body) }
        row.lastSavedLiveVersion?.let { Text("Last saved with: $it", style = AppTheme.typography.body) }
    }
}

@Composable
private fun SamplesTab(samples: List<SampleEntry>) {
    if (samples.isEmpty()) {
        EmptyState(
            title = "No samples referenced",
            hint = "Either this project is synth-only or the parser hasn't run yet.",
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        items(samples, key = { it.rawPath }) { s ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                Badge(color = if (s.isMissing) AppTheme.colors.accentSecondary else AppTheme.colors.pinGreen) {
                    Text(if (s.isMissing) "missing" else "found", style = AppTheme.typography.caption)
                }
                RowItem(
                    modifier = Modifier.weight(1f),
                    title = s.displayName,
                    subtitle = s.rawPath,
                )
            }
        }
    }
}

@Composable
private fun PluginsTab(plugins: List<PluginRef>) {
    if (plugins.isEmpty()) {
        EmptyState(
            title = "No plugins detected",
            hint = "Either the project is plugin-free or the parser hasn't run yet.",
        )
        return
    }
    val byTrack = plugins.groupBy { it.trackName ?: "(master)" }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        byTrack.forEach { (track, list) ->
            item(key = "track-$track") {
                Text(track, style = AppTheme.typography.bodyEmphasis)
            }
            items(list, key = { "$track-${it.name}-${it.format}" }) { p ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                ) {
                    Badge(color = AppTheme.colors.accentAction) {
                        Text(pluginFormatLabel(p.format), style = AppTheme.typography.caption)
                    }
                    Text(p.name, style = AppTheme.typography.body)
                }
            }
        }
    }
}

private fun pluginFormatLabel(format: PluginFormat): String =
    when (format) {
        PluginFormat.Vst3 -> "VST3"
        PluginFormat.Vst2 -> "VST"
        PluginFormat.Au -> "AU"
        PluginFormat.AbletonNative -> "LIVE"
        PluginFormat.Unknown -> "?"
    }

@Composable
private fun HistoryTab(state: ProjectDetailViewModel.State) {
    if (state.history.isEmpty()) {
        EmptyState(title = "No snapshots yet", hint = "Save the project in Live to start the history.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        items(state.history, key = { it.rev.value }) { snap ->
            RowItem(
                title = snap.label ?: "rev ${snap.rev.value}",
                subtitle = "${snap.kind.name.lowercase()} · ${snap.hostName} · ${snap.fileCount} files",
            )
        }
    }
}
