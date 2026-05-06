package com.sketchbook.featurejournal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.sketchbook.featurejournal.format.JournalLabel
import com.sketchbook.featurejournal.format.journalLabel
import com.sketchbook.repo.JournalEntry
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.FilterChipOption
import com.sketchbook.uishared.components.FilterChipRow
import com.sketchbook.uishared.components.GroupCard
import com.sketchbook.uishared.components.GroupRow
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.components.TextField
import com.sketchbook.uishared.components.VerbPill
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
/** Filter chips + search field for the journal queue. */
@Composable
fun JournalFilterBar(
    state: JournalViewModel.State,
    onSearch: (String) -> Unit,
    onActionFilter: (JournalViewModel.ActionTypeFilter) -> Unit,
    onDateRange: (JournalViewModel.DateRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        FilterChipRow(
            options = DATE_RANGE_OPTIONS,
            selected = state.dateRange,
            onSelected = onDateRange,
        )
        FilterChipRow(
            options = ACTION_TYPE_OPTIONS,
            selected = state.actionTypeFilter,
            onSelected = onActionFilter,
        )
        TextField(
            value = state.search,
            onChange = onSearch,
            placeholder = "Search project name, paths, tags…",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Emits the journal queue's content (day-bucket cards) into a parent LazyColumn. See
 * [com.sketchbook.featureproposals.proposalsItems] for the rationale.
 */
fun LazyListScope.journalItems(
    state: JournalViewModel.State,
    dayExpanded: SnapshotStateMap<String, Boolean>,
    onOpen: (entry: JournalEntry, projectName: String) -> Unit,
    onUndo: (JournalEntry) -> Unit,
    onBulkUndo: () -> Unit,
) {
    if (state.rows.isEmpty()) {
        item(key = "j-empty") {
            EmptyState(
                title = if (state.loading) "Loading…"
                    else if (state.isNarrowed) "No matches"
                    else "No entries",
                hint = if (state.isNarrowed)
                    "Nothing matches the current filters. Clear them to see everything."
                else "Move/rename/archive/tag actions land here.",
            )
        }
        return
    }
    val showBulkUndoOnFirstDay = state.isNarrowed && state.invertibleEntries.isNotEmpty()
    val firstDayLabel = state.days.firstOrNull()?.label
    itemsIndexed(
        items = state.days,
        key = { _, day -> "j-g-${day.label}" },
    ) { _, day ->
        DayGroupCard(
            label = day.label,
            rows = day.rows,
            expanded = dayExpanded[day.label] ?: true,
            onToggle = { dayExpanded[day.label] = !(dayExpanded[day.label] ?: true) },
            onOpen = { entry ->
                val name = state.rows.firstOrNull { it.entry == entry }?.projectName
                    ?: "project #${entry.projectId.value}"
                onOpen(entry, name)
            },
            onUndo = onUndo,
            showBulkUndo = showBulkUndoOnFirstDay && day.label == firstDayLabel,
            bulkUndoCount = state.invertibleEntries.size,
            onBulkUndo = onBulkUndo,
        )
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
    onUndo: (JournalEntry) -> Unit,
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
                    Text("Undo $bulkUndoCount", softWrap = false, maxLines = 1)
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
                        onUndo = onUndo,
                    )
                }
            }
        }
    }
}

/**
 * Pure-data view of a row. Computed once per row identity so the row composable's args stay
 * stable across parent recompositions that don't touch this entry. Mirrors the proposals queue's
 * `ProposalRowData` shape — verb pill + bold target + caption detail — so the two surfaces look
 * and read the same way.
 */
@Immutable
private data class JournalRowData(
    val entry: JournalEntry,
    val label: JournalLabel,
    val projectName: String,
    val time: String,
    val isInvertible: Boolean,
)

private fun rowDataFor(row: JournalViewModel.JournalRow): JournalRowData = JournalRowData(
    entry = row.entry,
    label = journalLabel(row.entry.action),
    projectName = row.projectName,
    time = shortTime(row.entry.timestamp),
    isInvertible = row.isInvertible,
)

@Composable
private fun JournalRowItem(
    data: JournalRowData,
    last: Boolean,
    onOpen: (JournalEntry) -> Unit,
    onUndo: (JournalEntry) -> Unit,
) {
    val entry = data.entry
    val label = data.label
    GroupRow(onClick = { onOpen(entry) }, last = last) {
        VerbPill(label.verb, label.tintHint)
        Badge(color = AppTheme.colors.tintCream) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(data.projectName, style = AppTheme.typography.caption, maxLines = 1)
            }
        }
        if (label.target.isNotEmpty()) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(
                    label.target,
                    style = AppTheme.typography.bodyEmphasis,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // Archive/Unarchive rows have no separate target — the project chip already says
            // which project; no need for a "—" placeholder. Spacer keeps the trailing time +
            // undo aligned to the right edge.
            Spacer(modifier = Modifier.weight(1f))
        }
        if (label.detail != null) {
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(label.detail, style = AppTheme.typography.caption, maxLines = 1)
            }
        }
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text(data.time, style = AppTheme.typography.caption, maxLines = 1)
        }
        if (data.isInvertible) {
            UndoButton(onClick = { onUndo(entry) })
        }
    }
}

/**
 * Inline undo affordance — a tinted pill so the action is visible at row scan rather than
 * disappearing into the muted-glyph noise. Matches the proposals queue's ✓/✗ density.
 */
@Composable
private fun UndoButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppTheme.spacing.cornerSmall))
            .background(AppTheme.colors.tintCream)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xs),
    ) {
        ProvideContentColor(AppTheme.colors.accentAction) {
            Text("↶ Undo", style = AppTheme.typography.caption)
        }
    }
}

private fun shortTime(ts: Instant): String {
    // 2026-05-06T12:34:56.789Z → "12:34"
    val s = ts.toString()
    val tIdx = s.indexOf('T')
    return if (tIdx >= 0 && s.length >= tIdx + 6) s.substring(tIdx + 1, tIdx + 6) else s
}
