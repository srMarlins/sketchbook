package com.sketchbook.featureproposals

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.repo.Proposal
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Tag
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

@Composable
fun ProposalsScreen(
    vm: ProposalsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.surfacePage)
            .padding(PaddingValues(AppTheme.spacing.md)),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        Text("Proposals", style = AppTheme.typography.title)
        if (state.pending.isEmpty() && state.resolved.isEmpty()) {
            EmptyState(
                title = if (state.loading) "Loading…" else "No proposals",
                hint = "Claude submits a proposal via the MCP server when it wants to make a write.",
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
            if (state.pending.isNotEmpty()) {
                item(key = "header-pending") { Text("Pending", style = AppTheme.typography.bodyEmphasis) }
                state.pending.forEach { p ->
                    item(key = "p-${p.proposalId}") {
                        PendingCard(
                            p = p,
                            onApprove = { vm.dispatch(ProposalsViewModel.Intent.Approve(it)) },
                            onReject = { vm.dispatch(ProposalsViewModel.Intent.Reject(it)) },
                        )
                    }
                }
            }
            if (state.resolved.isNotEmpty()) {
                item(key = "header-resolved") { Text("Resolved", style = AppTheme.typography.bodyEmphasis) }
                state.resolved.forEach { p ->
                    item(key = "r-${p.proposalId}") { ResolvedCard(p) }
                }
            }
        }
    }
}

@Composable
private fun PendingCard(
    p: Proposal,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    Surface(color = AppTheme.colors.surfacePanel) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                Tag(label = p.actor)
                Text(p.proposalId, style = AppTheme.typography.caption, modifier = Modifier.weight(1f))
            }
            p.rationale?.takeIf { it.isNotBlank() }?.let { Text(it, style = AppTheme.typography.body) }
            ActionsList(p)
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                Button(
                    onClick = { onReject(p.proposalId) },
                    variant = ButtonVariant.Ghost,
                ) { Text("Reject") }
                Button(
                    onClick = { onApprove(p.proposalId) },
                    variant = ButtonVariant.Primary,
                ) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun ResolvedCard(p: com.sketchbook.repo.Proposal) {
    Surface(color = AppTheme.colors.surfacePanel) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                val color = when (p.status) {
                    com.sketchbook.repo.ProposalStatus.Approved -> AppTheme.colors.pinGreen
                    com.sketchbook.repo.ProposalStatus.Rejected -> AppTheme.colors.accentAction
                    else -> AppTheme.colors.accentSecondary
                }
                Badge(color = color) { Text(p.status.name.lowercase(), style = AppTheme.typography.caption) }
                Text(p.proposalId, style = AppTheme.typography.caption)
            }
            ActionsList(p)
        }
    }
}

@Composable
private fun ActionsList(p: Proposal) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        p.actions.forEach { a ->
            Text("• ${a.type} ${a.args}", style = AppTheme.typography.mono)
        }
    }
}
