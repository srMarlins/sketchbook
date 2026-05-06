package com.sketchbook.desktop.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.featurejournal.JournalDetailPane
import com.sketchbook.featurejournal.JournalFilterBar
import com.sketchbook.featurejournal.JournalViewModel
import com.sketchbook.featurejournal.journalItems
import com.sketchbook.featureneedsattention.NeedsAttentionDetailPane
import com.sketchbook.featureneedsattention.NeedsAttentionDetailTarget
import com.sketchbook.featureneedsattention.NeedsAttentionFilterBar
import com.sketchbook.featureneedsattention.NeedsAttentionViewModel
import com.sketchbook.featureneedsattention.needsAttentionItems
import com.sketchbook.featureproposals.ProposalDetailPane
import com.sketchbook.featureproposals.ProposalsFilterBar
import com.sketchbook.featureproposals.ProposalsViewModel
import com.sketchbook.featureproposals.proposalsItems
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Unified Inbox surface — three columns side-by-side, each with its own scroll. The columns are:
 *  - **Suggested** — AI/code-proposed actions awaiting approve/reject (proposals queue).
 *  - **Issues** — auto-detected problems (Mac-imported projects, missing samples) awaiting repair.
 *  - **History** — chronological log of completed actions, with single + bulk undo.
 *
 * Suggested vs Issues stay separate because the user's mental model differs: "should this happen?"
 * (decide) vs "how do I unbreak this?" (fix). Inline affordances also differ (✓/✗ vs ↻ Repair).
 */
@Composable
fun InboxScreen(
    proposalsVm: ProposalsViewModel,
    needsAttentionVm: NeedsAttentionViewModel,
    journalVm: JournalViewModel,
    initialTab: InboxTab,
    modifier: Modifier = Modifier,
) {
    val proposalsState by proposalsVm.state.collectAsStateWithLifecycle()
    val attentionState by needsAttentionVm.state.collectAsStateWithLifecycle()
    val journalState by journalVm.state.collectAsStateWithLifecycle()

    val proposalGroupExpanded = remember {
        mutableStateMapOf<ProposalsViewModel.ProposalCategory, Boolean>()
    }
    var resolvedExpanded by remember { mutableStateOf(false) }
    val attentionCardExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val journalDayExpanded = remember { mutableStateMapOf<String, Boolean>() }

    var detail by remember { mutableStateOf<InboxDetailTarget?>(null) }

    val onApprove: (String) -> Unit = remember(proposalsVm) {
        { id -> proposalsVm.dispatch(ProposalsViewModel.Intent.Approve(id)) }
    }
    val onReject: (String) -> Unit = remember(proposalsVm) {
        { id -> proposalsVm.dispatch(ProposalsViewModel.Intent.Reject(id)) }
    }
    val onProposalsSearch: (String) -> Unit = remember(proposalsVm) {
        { q -> proposalsVm.dispatch(ProposalsViewModel.Intent.SetSearch(q)) }
    }
    val onSourceFilter: (ProposalsViewModel.SourceFilter) -> Unit = remember(proposalsVm) {
        { f -> proposalsVm.dispatch(ProposalsViewModel.Intent.SetSourceFilter(f)) }
    }
    val onOpenProposal: (String) -> Unit = remember {
        { id -> detail = InboxDetailTarget.Proposal(id) }
    }

    val onAttentionSearch: (String) -> Unit = remember(needsAttentionVm) {
        { q -> needsAttentionVm.dispatch(NeedsAttentionViewModel.Intent.SetSearch(q)) }
    }
    val onRepair: (com.sketchbook.core.ProjectId) -> Unit = remember(needsAttentionVm) {
        { id -> needsAttentionVm.dispatch(NeedsAttentionViewModel.Intent.RepairMacPaths(id)) }
    }
    val macIds by remember {
        derivedStateOf { attentionState.macEntries.map { it.finding.projectId } }
    }
    val onBulkRepair: () -> Unit = remember(needsAttentionVm, macIds) {
        { needsAttentionVm.dispatch(NeedsAttentionViewModel.Intent.BulkRepairMacPaths(macIds)) }
    }
    val onBulkApply: (List<MissingSampleFinding>) -> Unit = remember(needsAttentionVm) {
        { findings -> needsAttentionVm.dispatch(NeedsAttentionViewModel.Intent.BulkApplyAutoMatch(findings)) }
    }
    val onBulkDismiss: (List<MissingSampleFinding>) -> Unit = remember(needsAttentionVm) {
        { findings -> needsAttentionVm.dispatch(NeedsAttentionViewModel.Intent.BulkDismiss(findings)) }
    }
    val onOpenAttention: (NeedsAttentionDetailTarget) -> Unit = remember {
        { target -> detail = InboxDetailTarget.Attention(target) }
    }

    val onJournalSearch: (String) -> Unit = remember(journalVm) {
        { q -> journalVm.dispatch(JournalViewModel.Intent.SetSearch(q)) }
    }
    val onActionFilter: (JournalViewModel.ActionTypeFilter) -> Unit = remember(journalVm) {
        { f -> journalVm.dispatch(JournalViewModel.Intent.SetActionTypeFilter(f)) }
    }
    val onDateRange: (JournalViewModel.DateRange) -> Unit = remember(journalVm) {
        { r -> journalVm.dispatch(JournalViewModel.Intent.SetDateRange(r)) }
    }
    val onUndoOne: (JournalEntry) -> Unit = remember(journalVm) {
        { e -> journalVm.dispatch(JournalViewModel.Intent.UndoOne(e)) }
    }
    val onBulkUndo: () -> Unit = remember(journalVm, journalState.invertibleEntries) {
        { journalVm.dispatch(JournalViewModel.Intent.BulkUndo(journalState.invertibleEntries)) }
    }
    val onOpenJournal: (JournalEntry, String) -> Unit = remember {
        { entry, name -> detail = InboxDetailTarget.JournalEntry(entry, name) }
    }
    val onResolvedToggle: () -> Unit = remember { { resolvedExpanded = !resolvedExpanded } }
    val dismissDetail: () -> Unit = remember { { detail = null } }

    val pendingProposals = proposalsState.pending.size
    val pendingAttention = attentionState.macEntries.size +
        attentionState.missingByConfidence.autoMatch.size +
        attentionState.missingByConfidence.multiCandidate.size +
        attentionState.missingByConfidence.noCandidate.size
    val journalCount = journalState.rows.size

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Above WIDE_THRESHOLD we have room for 3 columns + a docked detail pane side-by-side.
        // Below it (or whenever the detail pane wouldn't leave columns at least 600dp), the
        // detail pane covers the columns area as a right-anchored sheet with a tap-to-dismiss
        // scrim — keeping all three columns reachable as soon as the user closes the detail.
        val totalWidth = maxWidth
        val canDockDetail = totalWidth >= WIDE_THRESHOLD

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(AppTheme.colors.surfacePage)
                    .padding(PaddingValues(AppTheme.spacing.md)),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            ) {
                PageHeader(title = "Inbox")
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                ) {
                    SuggestedColumn(
                        modifier = Modifier.fillMaxHeight().weight(1f),
                        state = proposalsState,
                        groupExpanded = proposalGroupExpanded,
                        resolvedExpanded = resolvedExpanded,
                        onResolvedToggle = onResolvedToggle,
                        vm = proposalsVm,
                        onOpen = onOpenProposal,
                        onApprove = onApprove,
                        onReject = onReject,
                        onSourceFilter = onSourceFilter,
                        onSearch = onProposalsSearch,
                        count = pendingProposals,
                    )
                    IssuesColumn(
                        modifier = Modifier.fillMaxHeight().weight(1f),
                        state = attentionState,
                        cardExpanded = attentionCardExpanded,
                        onOpen = onOpenAttention,
                        onRepair = onRepair,
                        onBulkRepair = onBulkRepair,
                        onBulkApply = onBulkApply,
                        onBulkDismiss = onBulkDismiss,
                        onSearch = onAttentionSearch,
                        count = pendingAttention,
                    )
                    HistoryColumn(
                        modifier = Modifier.fillMaxHeight().weight(1f),
                        state = journalState,
                        dayExpanded = journalDayExpanded,
                        onOpen = onOpenJournal,
                        onUndo = onUndoOne,
                        onBulkUndo = onBulkUndo,
                        onSearch = onJournalSearch,
                        onActionFilter = onActionFilter,
                        onDateRange = onDateRange,
                        count = journalCount,
                    )
                }
            }
            val d = detail
            if (d != null && canDockDetail) {
                DetailPaneSwitch(
                    detail = d,
                    proposalsVm = proposalsVm,
                    needsAttentionVm = needsAttentionVm,
                    journalVm = journalVm,
                    onDismiss = dismissDetail,
                )
            }
        }
        val d = detail
        if (d != null && !canDockDetail) {
            // Scrim catches taps outside the sheet to dismiss — same gesture as the ✕ button.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = dismissDetail),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) // empty space catches the scrim taps
                DetailPaneSwitch(
                    detail = d,
                    proposalsVm = proposalsVm,
                    needsAttentionVm = needsAttentionVm,
                    journalVm = journalVm,
                    onDismiss = dismissDetail,
                )
            }
        }
    }
}

/**
 * Dispatch a [InboxDetailTarget] to the right per-feature pane. Extracted so the docked and
 * overlay placements share a single rendering site.
 */
@Composable
private fun DetailPaneSwitch(
    detail: InboxDetailTarget,
    proposalsVm: ProposalsViewModel,
    needsAttentionVm: NeedsAttentionViewModel,
    journalVm: JournalViewModel,
    onDismiss: () -> Unit,
) {
    when (detail) {
        is InboxDetailTarget.Proposal -> ProposalDetailPane(
            proposalId = detail.proposalId,
            vm = proposalsVm,
            onDismiss = onDismiss,
        )

        is InboxDetailTarget.Attention -> NeedsAttentionDetailPane(
            target = detail.target,
            vm = needsAttentionVm,
            onDismiss = onDismiss,
        )

        is InboxDetailTarget.JournalEntry -> JournalDetailPane(
            entry = detail.entry,
            projectName = detail.projectName,
            vm = journalVm,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Width threshold for docking the detail pane next to the columns. Chosen so each column gets
 * at least ~200dp after the detail pane's max width (420dp) is subtracted: 3*200 + 420 ≈ 1020.
 * Below this the detail pane overlays the columns instead of squishing them.
 */
private val WIDE_THRESHOLD = 1080.dp

@Composable
private fun SuggestedColumn(
    modifier: Modifier,
    state: ProposalsViewModel.State,
    groupExpanded: SnapshotStateMap<ProposalsViewModel.ProposalCategory, Boolean>,
    resolvedExpanded: Boolean,
    onResolvedToggle: () -> Unit,
    vm: ProposalsViewModel,
    onOpen: (String) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onSourceFilter: (ProposalsViewModel.SourceFilter) -> Unit,
    onSearch: (String) -> Unit,
    count: Int,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ColumnHeader(title = "Suggested", count = count)
        ProposalsFilterBar(state = state, onSourceFilter = onSourceFilter, onSearch = onSearch)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            proposalsItems(
                state = state,
                groupExpanded = groupExpanded,
                resolvedExpanded = resolvedExpanded,
                onResolvedToggle = onResolvedToggle,
                vm = vm,
                onOpen = onOpen,
                onApprove = onApprove,
                onReject = onReject,
            )
        }
    }
}

@Composable
private fun IssuesColumn(
    modifier: Modifier,
    state: NeedsAttentionViewModel.State,
    cardExpanded: SnapshotStateMap<String, Boolean>,
    onOpen: (NeedsAttentionDetailTarget) -> Unit,
    onRepair: (com.sketchbook.core.ProjectId) -> Unit,
    onBulkRepair: () -> Unit,
    onBulkApply: (List<MissingSampleFinding>) -> Unit,
    onBulkDismiss: (List<MissingSampleFinding>) -> Unit,
    onSearch: (String) -> Unit,
    count: Int,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ColumnHeader(title = "Issues", count = count)
        NeedsAttentionFilterBar(state = state, onSearch = onSearch)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            needsAttentionItems(
                state = state,
                cardExpanded = cardExpanded,
                onOpen = onOpen,
                onRepair = onRepair,
                onBulkRepair = onBulkRepair,
                onBulkApply = onBulkApply,
                onBulkDismiss = onBulkDismiss,
            )
        }
    }
}

@Composable
private fun HistoryColumn(
    modifier: Modifier,
    state: JournalViewModel.State,
    dayExpanded: SnapshotStateMap<String, Boolean>,
    onOpen: (JournalEntry, String) -> Unit,
    onUndo: (JournalEntry) -> Unit,
    onBulkUndo: () -> Unit,
    onSearch: (String) -> Unit,
    onActionFilter: (JournalViewModel.ActionTypeFilter) -> Unit,
    onDateRange: (JournalViewModel.DateRange) -> Unit,
    count: Int,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ColumnHeader(title = "History", count = count)
        JournalFilterBar(
            state = state,
            onSearch = onSearch,
            onActionFilter = onActionFilter,
            onDateRange = onDateRange,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            journalItems(
                state = state,
                dayExpanded = dayExpanded,
                onOpen = onOpen,
                onUndo = onUndo,
                onBulkUndo = onBulkUndo,
            )
        }
    }
}

/**
 * Per-column header — queue title + count badge. No collapse toggle: in 3-up mode collapsing
 * doesn't free useful real estate, and in horizontal-scroll mode the user already needs to
 * scroll, so collapsing would be one more axis of state to manage for no win.
 */
@Composable
private fun ColumnHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .height(18.dp)
                .background(AppTheme.colors.ruleMargin)
                .padding(horizontal = 1.dp),
        )
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(title, style = AppTheme.typography.title)
        }
        if (count > 0) {
            Badge(color = AppTheme.colors.tintCream) {
                ProvideContentColor(AppTheme.colors.inkSecondary) {
                    Text(count.toString(), style = AppTheme.typography.caption)
                }
            }
        }
    }
}

enum class InboxTab { Proposals, NeedsAttention, Journal }

sealed interface InboxDetailTarget {
    data class Proposal(val proposalId: String) : InboxDetailTarget
    data class Attention(val target: NeedsAttentionDetailTarget) : InboxDetailTarget
    data class JournalEntry(
        val entry: com.sketchbook.repo.JournalEntry,
        val projectName: String,
    ) : InboxDetailTarget
}
