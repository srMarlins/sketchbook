package com.sketchbook.featurejournal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.featurejournal.format.humanReadable
import com.sketchbook.repo.JournalEntry
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.FilterChipOption
import com.sketchbook.uishared.components.FilterChipRow
import com.sketchbook.uishared.components.GroupCard
import com.sketchbook.uishared.components.GroupRow
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.components.TextField
import com.sketchbook.uishared.theme.AppTheme
import kotlin.time.Instant

/**
 * Journal viewer with filters, search, and day grouping. Bulk-undo lives on the first day's card
 * header when filters are active and at least one row is invertible — most-narrow-result wins,
 * since users typically narrow first ("show me yesterday's renames") and then bulk-undo.
 *
 * `detailPane` is an optional slot the host wires (RootContent owns navigation). When null, row
 * clicks are no-ops and the screen renders read-only.
 *
 * Performance: each day card and the rows within it stay as a single LazyColumn item so
 * journal-volume (capped at 200 rows) stays cheap. Per-row composables take a remembered
 * @Immutable view so Compose skips unchanged rows on parent recomposition.
 */
@Composable
fun JournalScreen(
    vm: JournalViewModel,
    modifier: Modifier = Modifier,
    detailPane: @Composable ((entry: JournalEntry, projectName: String, dismiss: () -> Unit) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var openEntry by remember { mutableStateOf<JournalEntry?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val onSearch: (String) -> Unit = remember(vm) {
        { q -> vm.dispatch(JournalViewModel.Intent.SetSearch(q)) }
    }
    val onAction: (JournalViewModel.ActionTypeFilter) -> Unit = remember(vm) {
        { f -> vm.dispatch(JournalViewModel.Intent.SetActionTypeFilter(f)) }
    }
    val onDateRange: (JournalViewModel.DateRange) -> Unit = remember(vm) {
        { r -> vm.dispatch(JournalViewModel.Intent.SetDateRange(r)) }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(AppTheme.colors.surfacePage)
                .padding(PaddingValues(AppTheme.spacing.md)),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            PageHeader(
                title = "Journal",
                actions = {
                    FilterChipRow(
                        options = DATE_RANGE_OPTIONS,
                        selected = state.dateRange,
                        onSelected = onDateRange,
                    )
                },
            )
            FilterChipRow(
                options = ACTION_TYPE_OPTIONS,
                selected = state.actionTypeFilter,
                onSelected = onAction,
            )
            TextField(
                value = state.search,
                onChange = onSearch,
                placeholder = "Search project name, paths, tags…",
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.rows.isEmpty()) {
                EmptyState(
                    title = if (state.loading) "Loading…" else "No entries",
                    hint = "Move/rename/archive/tag actions land here.",
                )
                return@Column
            }

            val showBulkUndoOnFirstDay = state.isNarrowed && state.invertibleEntries.isNotEmpty()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg)) {
                state.days.forEachIndexed { idx, day ->
                    item(key = "g-${day.label}") {
                        DayGroupCard(
                            label = day.label,
                            rows = day.rows,
                            expanded = expanded[day.label] ?: true,
                            onToggle = { expanded[day.label] = !(expanded[day.label] ?: true) },
                            onOpen = { openEntry = it },
                            showBulkUndo = showBulkUndoOnFirstDay && idx == 0,
                            bulkUndoCount = state.invertibleEntries.size,
                            onBulkUndo = {
                                vm.dispatch(JournalViewModel.Intent.BulkUndo(state.invertibleEntries))
                            },
                        )
                    }
                }
            }
        }
        val entry = openEntry
        if (entry != null && detailPane != null) {
            val name = state.rows.firstOrNull { it.entry == entry }?.projectName
                ?: "project #${entry.projectId.value}"
            detailPane(entry, name) { openEntry = null }
        }
    }
}

private val DATE_RANGE_OPTIONS = listOf(
    FilterChipOption(JournalViewModel.DateRange.Today, "Today"),
    FilterChipOption(JournalViewModel.DateRange.Last7Days, "7d"),
    FilterChipOption(JournalViewModel.DateRange.Last30Days, "30d"),
    FilterChipOption(JournalViewModel.DateRange.AllTime, "All"),
)

private val ACTION_TYPE_OPTIONS = listOf(
    FilterChipOption(JournalViewModel.ActionTypeFilter.All, "All"),
    FilterChipOption(JournalViewModel.ActionTypeFilter.Move, "Move"),
    FilterChipOption(JournalViewModel.ActionTypeFilter.Rename, "Rename"),
    FilterChipOption(JournalViewModel.ActionTypeFilter.Archive, "Archive"),
    FilterChipOption(JournalViewModel.ActionTypeFilter.Tag, "Tag"),
    FilterChipOption(JournalViewModel.ActionTypeFilter.Lock, "Lock"),
    FilterChipOption(JournalViewModel.ActionTypeFilter.Conflict, "Conflict"),
)

@Composable
private fun DayGroupCard(
    label: String,
    rows: List<JournalViewModel.JournalRow>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (JournalEntry) -> Unit,
    showBulkUndo: Boolean,
    bulkUndoCount: Int,
    onBulkUndo: () -> Unit,
) {
    GroupCard(
        title = label,
        count = rows.size,
        expanded = expanded,
        onToggle = onToggle,
        actions = {
            if (showBulkUndo) {
                Button(onClick = onBulkUndo, variant = ButtonVariant.Secondary) {
                    Text("Undo $bulkUndoCount")
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val lastIndex = rows.lastIndex
            rows.forEachIndexed { idx, row ->
                androidx.compose.runtime.key(row.entry.sequence ?: row.entry.timestamp.toEpochMilliseconds()) {
                    val rowData = remember(row) { rowDataFor(row) }
                    JournalRowItem(
                        data = rowData,
                        last = idx == lastIndex,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

/**
 * Pure-data view of a row. Computed once per row identity so the row composable's args stay
 * stable across parent recompositions that don't touch this entry.
 */
@Immutable
private data class JournalRowData(
    val entry: JournalEntry,
    val text: String,
    val projectName: String,
    val time: String,
)

private fun rowDataFor(row: JournalViewModel.JournalRow): JournalRowData = JournalRowData(
    entry = row.entry,
    text = humanReadable(row.entry.action),
    projectName = row.projectName,
    time = shortTime(row.entry.timestamp),
)

@Composable
private fun JournalRowItem(
    data: JournalRowData,
    last: Boolean,
    onOpen: (JournalEntry) -> Unit,
) {
    val entry = data.entry
    GroupRow(onClick = { onOpen(entry) }, last = last) {
        Column(modifier = Modifier.weight(1f)) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(
                    data.text,
                    style = AppTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(
                    "${data.projectName} · ${data.time}",
                    style = AppTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun shortTime(ts: Instant): String {
    // 2026-05-06T12:34:56.789Z → "12:34"
    val s = ts.toString()
    val tIdx = s.indexOf('T')
    return if (tIdx >= 0 && s.length >= tIdx + 6) s.substring(tIdx + 1, tIdx + 6) else s
}
