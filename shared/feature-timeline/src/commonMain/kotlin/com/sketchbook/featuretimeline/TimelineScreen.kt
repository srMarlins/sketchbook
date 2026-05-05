package com.sketchbook.featuretimeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.RowItem
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

@Composable
fun TimelineScreen(
    holder: TimelineStateHolder,
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
        val groups = holder.visibleGroups(state)
        if (groups.isEmpty()) {
            EmptyState(
                title = if (state.loading) "Loading…" else "No snapshots yet",
                hint = "Save the project in Live to start the history.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
                groups.forEach { group ->
                    item(key = "day-${group.date}") {
                        Text(
                            text = group.date.toString(),
                            style = AppTheme.typography.bodyEmphasis,
                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                        )
                    }
                    group.snapshots.forEach { snap ->
                        item(key = "snap-${snap.rev.value}") {
                            SnapshotRow(
                                rev = snap.rev,
                                kind = snap.kind,
                                label = snap.label ?: "rev ${snap.rev.value}",
                                host = snap.hostName,
                                files = snap.fileCount,
                                bytes = snap.totalBytes,
                                onRewind = { holder.dispatch(TimelineStateHolder.Intent.RequestRewind(snap.rev)) },
                            )
                        }
                    }
                }
            }
        }
        state.pendingRewind?.let { rev ->
            ConfirmRewindDialog(
                rev = rev,
                onCancel = { holder.dispatch(TimelineStateHolder.Intent.CancelRewind) },
                onConfirm = { holder.dispatch(TimelineStateHolder.Intent.ConfirmRewind(rev)) },
            )
        }
    }
}

@Composable
private fun Header(state: TimelineStateHolder.State, holder: TimelineStateHolder) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        Text("History", style = AppTheme.typography.title, modifier = Modifier.weight(1f))
        Button(
            onClick = { holder.dispatch(TimelineStateHolder.Intent.ToggleShowAll) },
            variant = ButtonVariant.Secondary,
        ) {
            Text(if (state.showAll) "Hide auto-saves" else "Show all saves")
        }
    }
}

@Composable
private fun SnapshotRow(
    rev: SnapshotRev,
    kind: SnapshotKind,
    label: String,
    host: String,
    files: Int,
    bytes: Long,
    onRewind: () -> Unit,
) {
    val isBranch = kind == SnapshotKind.Branch
    val indent = if (isBranch) AppTheme.spacing.lg else AppTheme.spacing.sm
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = indent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        when (kind) {
            SnapshotKind.Branch -> Badge(color = AppTheme.colors.accentAction) {
                Text("branch", style = AppTheme.typography.caption)
            }
            SnapshotKind.Named -> Badge(color = AppTheme.colors.pinGreen) {
                Text("named", style = AppTheme.typography.caption)
            }
            SnapshotKind.Auto -> Badge(color = AppTheme.colors.accentSecondary) {
                Text("auto", style = AppTheme.typography.caption)
            }
        }
        RowItem(
            modifier = Modifier.weight(1f),
            title = label,
            subtitle = "$host · $files files · ${humanBytes(bytes)}",
        )
        Button(onClick = onRewind, variant = ButtonVariant.Ghost) { Text("Rewind") }
    }
}

@Composable
private fun ConfirmRewindDialog(rev: SnapshotRev, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Surface(color = AppTheme.colors.surfacePanel, padding = PaddingValues(AppTheme.spacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
            Text("Rewind to rev ${rev.value}?", style = AppTheme.typography.title)
            Text("This rewrites the working tree. Unsaved local changes turn into a branch.")
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                Button(onClick = onCancel, variant = ButtonVariant.Ghost) { Text("Cancel") }
                Button(onClick = onConfirm, variant = ButtonVariant.Primary) { Text("Rewind") }
            }
        }
    }
}

private fun humanBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    b < 1024L * 1024 * 1024 -> "${b / (1024 * 1024)} MB"
    else -> "${b / (1024L * 1024 * 1024)} GB"
}
