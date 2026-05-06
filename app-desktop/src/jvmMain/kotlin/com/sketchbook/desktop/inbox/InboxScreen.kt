package com.sketchbook.desktop.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Unified Inbox surface. Single scrollable page with three accordion sections (Proposals,
 * Needs Attention, Journal). Each section header is sticky as you scroll inside it, so the
 * count + bulk action stay reachable. Sections delegate to per-feature `*Items` LazyListScope
 * extensions for content, so the whole page is one virtualized [LazyColumn] — even with 100+
 * mac-import rows the scroll stays cheap.
 *
 * Default state: Proposals + Needs Attention expanded (queues to act on); Journal collapsed
 * (historical reference). User can flip any.
 *
 * `initialTab` deep-links a section to be expanded — used by File-menu items and external nav
 * to land the user on the right section without clobbering their other expansion choices.
 */
@OptIn(ExperimentalFoundationApi::class)
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

    val sectionExpanded = remember {
        mutableStateMapOf(
            InboxTab.Proposals to (initialTab == InboxTab.Proposals || initialTab == InboxTab.NeedsAttention),
            InboxTab.NeedsAttention to (initialTab != InboxTab.Journal),
            InboxTab.Journal to (initialTab == InboxTab.Journal),
        )
    }
    // Inner-card / day expansion state lives at the inbox level so toggling a section
    // collapsed and back doesn't reset the user's deeper expansion choices.
    val proposalGroupExpanded = remember {
        mutableStateMapOf<ProposalsViewModel.ProposalCategory, Boolean>()
    }
    var resolvedExpanded by remember { mutableStateOf(false) }
    val attentionCardExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val journalDayExpanded = remember { mutableStateMapOf<String, Boolean>() }

    var detail by remember { mutableStateOf<InboxDetailTarget?>(null) }

    // Stable lambdas — hoisted out so the LazyColumn item lambdas don't reallocate per recomp.
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
                .background(AppTheme.colors.surfacePage),
        ) {
            PageHeader(
                title = "Inbox",
                modifier = Modifier.padding(
                    start = AppTheme.spacing.md,
                    end = AppTheme.spacing.md,
                    top = AppTheme.spacing.md,
                ),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PaddingValues(AppTheme.spacing.md)),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            ) {
                section(
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
                section(
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
                section(
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
 * Section block: sticky header (title + count + collapse toggle), optional filter bar inside
 * the expanded body, then the per-feature content items. Header stays pinned to the top of the
 * viewport while the user scrolls within the section, so the count + collapse toggle stay
 * reachable on long lists.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.section(
    tab: InboxTab,
    title: String,
    count: Int,
    sectionExpanded: SnapshotStateMap<InboxTab, Boolean>,
    filterBar: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val expanded = sectionExpanded[tab] ?: true
    stickyHeader(key = "section-header-$tab") {
        SectionHeader(
            title = title,
            count = count,
            expanded = expanded,
            onToggle = { sectionExpanded[tab] = !expanded },
        )
    }
    if (expanded) {
        item(key = "section-filter-$tab") {
            Box(modifier = Modifier.padding(top = AppTheme.spacing.xs)) {
                filterBar()
            }
        }
        content()
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
            .background(AppTheme.colors.surfacePage)
            .padding(vertical = AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onToggle)
                .padding(vertical = AppTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            ProvideContentColor(AppTheme.colors.inkSecondary) {
                Text(if (expanded) "▾" else "▸", style = AppTheme.typography.mono)
            }
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
}

/** Sticky-header helper alias so callers don't need the experimental opt-in at every site. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.stickyHeader(
    key: Any,
    content: @Composable () -> Unit,
) {
    stickyHeader(key = key, contentType = null) { content() }
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
