package com.sketchbook.featuretimeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.MaterializationProgress
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

@Composable
fun TimelineScreen(
    vm: TimelineViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
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
            onToggleShowAll = { vm.dispatch(TimelineViewModel.Intent.ToggleShowAll) },
        )
        // visibleGroups does filter+sort+groupBy+sortedMap on every call. The state ticks
        // frequently while a rewind is in progress; recompute only when the inputs that
        // actually feed the result change.
        val groups = remember(state.history, state.showAll) { vm.visibleGroups(state) }
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
                                rawLabel = snap.label,
                                host = snap.hostName,
                                files = snap.fileCount,
                                bytes = snap.totalBytes,
                                newBytes = snap.newBytes,
                                onRewind = { vm.dispatch(TimelineViewModel.Intent.RequestRewind(snap.rev)) },
                                onCommitLabel = { newLabel ->
                                    vm.dispatch(TimelineViewModel.Intent.RelabelSnapshot(snap.rev, newLabel))
                                },
                            )
                        }
                    }
                }
            }
        }
        state.pendingRewind?.let { rev ->
            ConfirmRewindDialog(
                rev = rev,
                progress = state.rewindProgress,
                onCancel = { vm.dispatch(TimelineViewModel.Intent.CancelRewind) },
                onConfirm = { vm.dispatch(TimelineViewModel.Intent.ConfirmRewind(rev)) },
            )
        }
    }
}

@Composable
private fun Header(
    state: TimelineViewModel.State,
    onToggleShowAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        Text("History", style = AppTheme.typography.title, modifier = Modifier.weight(1f))
        Button(
            onClick = onToggleShowAll,
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
    rawLabel: String?,
    host: String,
    files: Int,
    bytes: Long,
    newBytes: Long,
    onRewind: () -> Unit,
    onCommitLabel: (String?) -> Unit,
) {
    val isBranch = kind == SnapshotKind.Branch
    val indent = if (isBranch) AppTheme.spacing.lg else AppTheme.spacing.sm
    val displayLabel = rawLabel ?: "rev ${rev.value}"
    val subtitle = "$host · $files files · ${humanBytes(newBytes)} new of ${humanBytes(bytes)}"

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = indent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        when (kind) {
            SnapshotKind.Branch -> {
                Badge(color = AppTheme.colors.accentAction) {
                    Text("branch", style = AppTheme.typography.caption)
                }
            }

            SnapshotKind.Named -> {
                Badge(color = AppTheme.colors.pinGreen) {
                    Text("named", style = AppTheme.typography.caption)
                }
            }

            SnapshotKind.Auto -> {
                Badge(color = AppTheme.colors.accentSecondary) {
                    Text("auto", style = AppTheme.typography.caption)
                }
            }
        }
        EditableLabelCell(
            modifier = Modifier.weight(1f),
            displayLabel = displayLabel,
            // Pass the *raw* (possibly-null) label as the seed for edit mode so the user starts
            // with their actual stored value, not the synthetic "rev N" placeholder.
            initialEditValue = rawLabel ?: "",
            subtitle = subtitle,
            onCommit = { typed ->
                // Empty/blank → clear (null). Anything else → trim & persist.
                val cleaned = typed.trim().ifEmpty { null }
                // Only fire if the value actually changed; cuts journal-entry spam from
                // accidental click-blur.
                if (cleaned != rawLabel) onCommitLabel(cleaned)
            },
        )
        Button(onClick = onRewind, variant = ButtonVariant.Ghost) { Text("Rewind") }
    }
}

/**
 * Click the title to swap the row into an inline `BasicTextField`. Enter or focus-loss commit;
 * Esc reverts the buffer to the original and exits edit mode. Mirrors the project-rename
 * pattern (click-the-text-to-edit) — no separate pencil icon, since the codebase doesn't have
 * one and adding one would break the no-new-icons constraint.
 */
@Composable
private fun EditableLabelCell(
    modifier: Modifier,
    displayLabel: String,
    initialEditValue: String,
    subtitle: String,
    onCommit: (String) -> Unit,
) {
    val colors = AppTheme.colors
    var editing by remember { mutableStateOf(false) }
    var buffer by remember(initialEditValue) { mutableStateOf(initialEditValue) }
    val focusRequester = remember { FocusRequester() }

    if (editing) {
        // Auto-focus the field on entry. requestFocus() needs to run after the field has been
        // attached to the composition, so we route it through LaunchedEffect.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(modifier = modifier) {
            BasicTextField(
                value = buffer,
                onValueChange = { buffer = it },
                singleLine = true,
                textStyle = AppTheme.typography.bodyEmphasis.copy(color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.inkPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(onDone = {
                        onCommit(buffer)
                        editing = false
                    }),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            // Esc cancels: drop the buffer, exit edit mode, no commit.
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                buffer = initialEditValue
                                editing = false
                                true
                            } else {
                                false
                            }
                        }.onFocusChanged { focus ->
                            // Blur commits — but only if we're still in edit mode (Esc may have just
                            // flipped editing to false above, and we don't want a double-commit).
                            if (!focus.isFocused && editing) {
                                onCommit(buffer)
                                editing = false
                            }
                        },
            )
            Text(subtitle, style = AppTheme.typography.caption)
        }
    } else {
        Column(
            modifier =
                modifier.clickable {
                    buffer = initialEditValue
                    editing = true
                },
        ) {
            Box {
                Text(displayLabel, style = AppTheme.typography.bodyEmphasis)
            }
            Text(subtitle, style = AppTheme.typography.caption)
        }
    }
}

@Composable
private fun ConfirmRewindDialog(
    rev: SnapshotRev,
    progress: MaterializationProgress?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(color = AppTheme.colors.surfacePanel, padding = PaddingValues(AppTheme.spacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
            Text("Rewind to rev ${rev.value}?", style = AppTheme.typography.title)
            Text("This rewrites the working tree. Unsaved local changes turn into a branch.")
            progress?.let { ProgressLine(it) }
            val inFlight = progress != null && progress !is MaterializationProgress.Done && progress !is MaterializationProgress.Failed
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                Button(onClick = onCancel, variant = ButtonVariant.Ghost) { Text("Cancel") }
                Button(onClick = onConfirm, variant = ButtonVariant.Primary) {
                    Text(if (inFlight) "Rewinding…" else "Rewind")
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(progress: MaterializationProgress) {
    val text =
        when (progress) {
            is MaterializationProgress.Started -> {
                "Starting…"
            }

            is MaterializationProgress.Downloading -> {
                val pct =
                    if (progress.bytesTotal > 0) {
                        ((progress.bytesDone * 100) / progress.bytesTotal).coerceIn(0L, 100L)
                    } else {
                        0L
                    }
                "Downloading blobs · $pct% (${progress.blobsRemaining} remaining)"
            }

            is MaterializationProgress.WritingFiles -> {
                "Writing files · ${progress.filesDone}/${progress.filesTotal}"
            }

            is MaterializationProgress.Done -> {
                "Done."
            }

            is MaterializationProgress.Failed -> {
                "Failed: ${progress.reason}"
            }
        }
    Text(text, style = AppTheme.typography.caption)
}

private fun humanBytes(b: Long): String =
    when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> "${b / (1024 * 1024)} MB"
        else -> "${b / (1024L * 1024 * 1024)} GB"
    }
