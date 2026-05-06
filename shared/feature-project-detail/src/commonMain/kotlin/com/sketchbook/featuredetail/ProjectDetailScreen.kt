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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.sketchbook.uishared.components.RowItem
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Tag
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Project-detail pane with tabs across the top and a body that swaps based on the selected tab.
 */
@Composable
fun ProjectDetailScreen(
    holder: ProjectDetailStateHolder,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.surfacePage)
            .padding(PaddingValues(AppTheme.spacing.md)),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        Header(state, holder)
        Tabs(state.tab, onSelect = { holder.dispatch(ProjectDetailStateHolder.Intent.SelectTab(it)) })
        when (state.tab) {
            ProjectDetailStateHolder.Tab.Overview -> OverviewTab(state)
            ProjectDetailStateHolder.Tab.Tracks -> TracksTab(state)
            ProjectDetailStateHolder.Tab.Samples -> SamplesTab(state.samples)
            ProjectDetailStateHolder.Tab.Plugins -> PluginsTab(state.plugins)
            ProjectDetailStateHolder.Tab.History -> HistoryTab(state)
        }
    }
}

@Composable
private fun Header(state: ProjectDetailStateHolder.State, holder: ProjectDetailStateHolder) {
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
        LockSlot(state.lockStatus, holder)
        Button(
            onClick = { holder.dispatch(ProjectDetailStateHolder.Intent.OpenInLive) },
            variant = ButtonVariant.Primary,
        ) { Text("Open in Live") }
    }
}

@Composable
private fun LockSlot(status: LockStatus, holder: ProjectDetailStateHolder) {
    when (status) {
        LockStatus.Free -> Unit // chrome stays clean when there's nothing to say
        is LockStatus.Ours -> LockBadge(
            label = "editing here",
            color = AppTheme.colors.pinGreen,
        )
        is LockStatus.HeldByOther -> LockBadge(
            label = "locked",
            color = AppTheme.colors.accentSecondary,
            detail = status.ownerHostName,
            actionLabel = "Force-take",
            onAction = { holder.dispatch(ProjectDetailStateHolder.Intent.ForceTakeLock) },
        )
        is LockStatus.Stale -> LockBadge(
            label = "stale lock",
            color = AppTheme.colors.pinOrange,
            detail = status.ownerHostName,
            actionLabel = "Take",
            onAction = { holder.dispatch(ProjectDetailStateHolder.Intent.ForceTakeLock) },
        )
    }
}

@Composable
private fun Tabs(current: ProjectDetailStateHolder.Tab, onSelect: (ProjectDetailStateHolder.Tab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProjectDetailStateHolder.Tab.values().forEach { tab ->
            val active = tab == current
            val color = if (active) AppTheme.colors.accentAction else AppTheme.colors.surfacePanel
            Surface(
                color = color,
                padding = PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
                modifier = Modifier.clickable { onSelect(tab) },
            ) {
                Text(
                    text = tab.name,
                    style = if (active) AppTheme.typography.bodyEmphasis else AppTheme.typography.body,
                )
            }
        }
    }
}

@Composable
private fun OverviewTab(state: ProjectDetailStateHolder.State) {
    val row = state.row ?: return
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        row.tempo?.let { Text("Tempo: ${it.toInt()} bpm") }
        Text("Tracks: ${row.trackCount}")
        row.lastSavedLiveVersion?.let { Text("Last saved with: $it") }
        if (row.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                row.tags.forEach { Tag(label = it) }
            }
        }
    }
}

@Composable
private fun TracksTab(state: ProjectDetailStateHolder.State) {
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
            items(list, key = { "${track}-${it.name}-${it.format}" }) { p ->
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

private fun pluginFormatLabel(format: PluginFormat): String = when (format) {
    PluginFormat.Vst3 -> "VST3"
    PluginFormat.Vst2 -> "VST"
    PluginFormat.Au -> "AU"
    PluginFormat.AbletonNative -> "LIVE"
    PluginFormat.Unknown -> "?"
}

@Composable
private fun HistoryTab(state: ProjectDetailStateHolder.State) {
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
