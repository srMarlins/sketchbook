package com.sketchbook.desktop.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
 * Unified Inbox surface — all three queues visible at once. Vertical layout: each expanded
 * section takes equal share of the available height (`weight(1f)`) with its own internal
 * scroll, so the user sees Proposals, Needs Attention, and Journal simultaneously without
 * digging. Collapsed sections shrink to just their header bar; the freed vertical space
 * redistributes to the remaining expanded sections.
 *
 * Each section's header always shows title + count + collapse toggle, so even when collapsed
 * the user sees what's in each queue at a glance.
 *
 * `initialTab` deep-links from File-menu / external nav: that tab is forced expanded on entry,
 * other tabs keep their default state.
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

    // All three default expanded so the user sees all queues at once. `initialTab` is honored
    // by forcing that section open even if it would have defaulted closed in the future.
    val sectionExpanded = remember {
        mutableStateMapOf(
            InboxTab.Proposals to true,
            InboxTab.NeedsAttention to true,
            InboxTab.Journal to true,
        ).also { it[initialTab] = true }
    }
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

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(AppTheme.colors.surfacePage)
                .padding(PaddingValues(AppTheme.spacing.md)),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            PageHeader(title = "Inbox")
            Section(
                tab = InboxTab.Proposals,
                title = "Proposals",
                count = pendingProposals,
                sectionExpanded = sectionExpanded,
                filterBar = {
                    ProposalsFilterBar(
                        state = proposalsState,
                        onSourceFilter = onSourceFilter,
                        onSearch = onProposalsSearch,
                    )
                },
            ) {
                proposalsItems(
                    state = proposalsState,
                    groupExpanded = proposalGroupExpanded,
                    resolvedExpanded = resolvedExpanded,
                    onResolvedToggle = onResolvedToggle,
                    vm = proposalsVm,
                    onOpen = onOpenProposal,
                    onApprove = onApprove,
                    onReject = onReject,
                )
            }
            Section(
                tab = InboxTab.NeedsAttention,
                title = "Needs Attention",
                count = pendingAttention,
                sectionExpanded = sectionExpanded,
                filterBar = {
                    NeedsAttentionFilterBar(
                        state = attentionState,
                        onSearch = onAttentionSearch,
                    )
                },
            ) {
                needsAttentionItems(
                    state = attentionState,
                    cardExpanded = attentionCardExpanded,
                    onOpen = onOpenAttention,
                    onRepair = onRepair,
                    onBulkRepair = onBulkRepair,
                    onBulkApply = onBulkApply,
                    onBulkDismiss = onBulkDismiss,
                )
            }
            Section(
                tab = InboxTab.Journal,
                title = "Journal",
                count = journalCount,
                sectionExpanded = sectionExpanded,
                filterBar = {
                    JournalFilterBar(
                        state = journalState,
                        onSearch = onJournalSearch,
                        onActionFilter = onActionFilter,
                        onDateRange = onDateRange,
                    )
                },
            ) {
                journalItems(
                    state = journalState,
                    dayExpanded = journalDayExpanded,
                    onOpen = onOpenJournal,
                    onUndo = onUndoOne,
                    onBulkUndo = onBulkUndo,
                )
            }
        }
        when (val d = detail) {
            is InboxDetailTarget.Proposal -> ProposalDetailPane(
                proposalId = d.proposalId,
                vm = proposalsVm,
                onDismiss = dismissDetail,
            )
            is InboxDetailTarget.Attention -> NeedsAttentionDetailPane(
                target = d.target,
                vm = needsAttentionVm,
                onDismiss = dismissDetail,
            )
            is InboxDetailTarget.JournalEntry -> JournalDetailPane(
                entry = d.entry,
                projectName = d.projectName,
                vm = journalVm,
                onDismiss = dismissDetail,
            )
            null -> Unit
        }
    }
}

/**
 * One queue section. Header is always visible with title + count + collapse toggle. When
 * expanded, the section claims `weight(1f)` of the parent Column so all expanded sections share
 * the available vertical space evenly; the body has its own internal LazyColumn for scrolling.
 * When collapsed, the section is just the header bar — no weight, no body — and other expanded
 * sections grow to fill the freed space.
 */
@Composable
private fun ColumnScope.Section(
    tab: InboxTab,
    title: String,
    count: Int,
    sectionExpanded: SnapshotStateMap<InboxTab, Boolean>,
    filterBar: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val expanded = sectionExpanded[tab] ?: true
    val onToggle: () -> Unit = { sectionExpanded[tab] = !expanded }
    if (expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            SectionHeader(
                title = title,
                count = count,
                expanded = true,
                onToggle = onToggle,
            )
            filterBar()
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            ) {
                content()
            }
        }
    } else {
        SectionHeader(
            title = title,
            count = count,
            expanded = false,
            onToggle = onToggle,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.tintCream)
            .clickable(onClick = onToggle)
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .height(18.dp)
                .background(AppTheme.colors.ruleMargin)
                .padding(horizontal = 1.dp),
        )
        ProvideContentColor(AppTheme.colors.inkSecondary) {
            Text(if (expanded) "▾" else "▸", style = AppTheme.typography.mono)
        }
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(title, style = AppTheme.typography.bodyEmphasis)
        }
        if (count > 0) {
            Badge(color = AppTheme.colors.surfaceCard) {
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
