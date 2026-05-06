package com.sketchbook.desktop.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.featurejournal.JournalBody
import com.sketchbook.featurejournal.JournalDetailPane
import com.sketchbook.featurejournal.JournalViewModel
import com.sketchbook.featureneedsattention.NeedsAttentionBody
import com.sketchbook.featureneedsattention.NeedsAttentionDetailPane
import com.sketchbook.featureneedsattention.NeedsAttentionDetailTarget
import com.sketchbook.featureneedsattention.NeedsAttentionViewModel
import com.sketchbook.featureproposals.ProposalDetailPane
import com.sketchbook.featureproposals.ProposalsBody
import com.sketchbook.featureproposals.ProposalsViewModel
import com.sketchbook.repo.JournalEntry
import com.sketchbook.uishared.components.FilterChipOption
import com.sketchbook.uishared.components.FilterChipRow
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.theme.AppTheme

/**
 * Unified Inbox surface — single screen that hosts the three queue bodies (Proposals, Needs
 * Attention, Journal) under a shared chrome (page header + tab strip + right-side detail pane).
 * Each tab keeps its own filter state inside its VM, so switching tabs preserves what the user
 * had narrowed.
 *
 * Detail pane is hoisted here so it survives tab switches at the right edge of the window — the
 * sealed [InboxDetailTarget] keeps the pane type-safe across queue kinds.
 */
@Composable
fun InboxScreen(
    proposalsVm: ProposalsViewModel,
    needsAttentionVm: NeedsAttentionViewModel,
    journalVm: JournalViewModel,
    initialTab: InboxTab,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(initialTab) }
    var detail by remember { mutableStateOf<InboxDetailTarget?>(null) }

    val proposalsState by proposalsVm.state.collectAsStateWithLifecycle()
    val attentionState by needsAttentionVm.state.collectAsStateWithLifecycle()
    val journalState by journalVm.state.collectAsStateWithLifecycle()

    val pendingProposals = proposalsState.pending.size
    val pendingAttention = attentionState.macEntries.size +
        attentionState.missingByConfidence.autoMatch.size +
        attentionState.missingByConfidence.multiCandidate.size +
        attentionState.missingByConfidence.noCandidate.size
    val journalCount = journalState.rows.size

    val tabOptions = remember(pendingProposals, pendingAttention, journalCount) {
        listOf(
            FilterChipOption(InboxTab.Proposals, label("Proposals", pendingProposals)),
            FilterChipOption(InboxTab.NeedsAttention, label("Attention", pendingAttention)),
            FilterChipOption(InboxTab.Journal, label("Journal", journalCount)),
        )
    }
    val onOpenProposal: (String) -> Unit = remember {
        { id -> detail = InboxDetailTarget.Proposal(id) }
    }
    val onOpenAttention: (NeedsAttentionDetailTarget) -> Unit = remember {
        { target -> detail = InboxDetailTarget.Attention(target) }
    }
    val onOpenJournal: (JournalEntry, String) -> Unit = remember {
        { entry, name -> detail = InboxDetailTarget.JournalEntry(entry, name) }
    }
    val dismissDetail: () -> Unit = remember { { detail = null } }

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
                title = "Inbox",
                actions = {
                    FilterChipRow(
                        options = tabOptions,
                        selected = tab,
                        onSelected = { tab = it },
                    )
                },
            )
            when (tab) {
                InboxTab.Proposals -> ProposalsBody(
                    vm = proposalsVm,
                    onOpen = onOpenProposal,
                )
                InboxTab.NeedsAttention -> NeedsAttentionBody(
                    vm = needsAttentionVm,
                    onOpen = onOpenAttention,
                )
                InboxTab.Journal -> JournalBody(
                    vm = journalVm,
                    onOpen = onOpenJournal,
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

private fun label(name: String, count: Int): String =
    if (count > 0) "$name · $count" else name

@Stable
enum class InboxTab { Proposals, NeedsAttention, Journal }

/**
 * Sealed target for the inbox's right-side detail pane. Each branch wraps the data the
 * corresponding feature's detail-pane composable already needs — no per-feature state is read
 * from the VM here, so a refresh that drops the underlying row simply renders the empty/missing
 * placeholder built into each pane.
 */
sealed interface InboxDetailTarget {
    data class Proposal(val proposalId: String) : InboxDetailTarget
    data class Attention(val target: NeedsAttentionDetailTarget) : InboxDetailTarget
    data class JournalEntry(
        val entry: com.sketchbook.repo.JournalEntry,
        val projectName: String,
    ) : InboxDetailTarget
}
