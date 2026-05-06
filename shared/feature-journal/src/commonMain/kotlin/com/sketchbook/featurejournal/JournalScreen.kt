package com.sketchbook.featurejournal

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.RowItem
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

@Composable
fun JournalScreen(
    holder: JournalStateHolder,
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
        Text("Journal", style = AppTheme.typography.title)
        if (state.entries.isEmpty()) {
            EmptyState(
                title = if (state.loading) "Loading…" else "No entries yet",
                hint = "Move/rename/archive/tag actions land here.",
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            items(
                state.entries,
                key = { entry -> entry.sequence ?: entry.timestamp.toEpochMilliseconds() },
            ) { entry ->
                EntryRow(entry, onClick = {
                    holder.dispatch(JournalStateHolder.Intent.OpenProject(entry.projectId))
                })
            }
        }
    }
}

@Composable
private fun EntryRow(entry: JournalEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        RowItem(
            modifier = Modifier.weight(1f),
            title = "${actionType(entry.action)} · project ${entry.projectId.value}",
            subtitle = "${entry.timestamp}",
        )
    }
}

private fun actionType(action: ActionRecord): String = when (action) {
    is ActionRecord.Move -> "move"
    is ActionRecord.Rename -> "rename"
    is ActionRecord.Archive -> if (action.isArchived) "archive" else "unarchive"
    is ActionRecord.SetTags -> "tag"
    is ActionRecord.ForceTakeLock -> "force-take lock"
    is ActionRecord.PushConflict -> "push conflict"
}
