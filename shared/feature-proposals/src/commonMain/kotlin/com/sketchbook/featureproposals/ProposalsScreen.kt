package com.sketchbook.featureproposals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.featureproposals.format.proposalLabel
import com.sketchbook.repo.Proposal
import com.sketchbook.repo.ProposalStatus
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
import com.sketchbook.uishared.components.VerbTint
import com.sketchbook.uishared.theme.AppTheme
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Proposals queue. Pending items cluster into action-category cards (Archive / Move / Tag /
 * Color / Other) with bulk approve/reject in each header; rows render a verb pill + bold target
 * + trailing detail + inline ✓/✗ for single-item action without drilling into the detail pane.
 *
 * `detailPane` is an optional slot the host wires (RootContent owns navigation). When null, the
 * row click is a no-op and the inline icons handle approve/reject without drill-in.
 *
 * Performance notes: row composables take only primitive/`@Immutable` args so Compose can skip
 * unchanged rows when the parent state changes. Per-row click lambdas capture stable values from
 * the dispatch surface and the proposal id only.
 */
@Composable
fun ProposalsBody(
    vm: ProposalsViewModel,
    onOpen: (proposalId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var resolvedExpanded by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<ProposalsViewModel.ProposalCategory, Boolean>() }

    val approve: (String) -> Unit = remember(vm) {
        { id -> vm.dispatch(ProposalsViewModel.Intent.Approve(id)) }
    }
    val reject: (String) -> Unit = remember(vm) {
        { id -> vm.dispatch(ProposalsViewModel.Intent.Reject(id)) }
    }
    val onSearch: (String) -> Unit = remember(vm) {
        { q -> vm.dispatch(ProposalsViewModel.Intent.SetSearch(q)) }
    }
    val onSourceFilter: (ProposalsViewModel.SourceFilter) -> Unit = remember(vm) {
        { f -> vm.dispatch(ProposalsViewModel.Intent.SetSourceFilter(f)) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        FilterChipRow(
            options = SOURCE_FILTER_OPTIONS,
            selected = state.sourceFilter,
            onSelected = onSourceFilter,
        )
        TextField(
            value = state.search,
            onChange = onSearch,
            placeholder = "Search rationale or paths…",
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.groups.isEmpty() && state.resolved.isEmpty()) {
            EmptyState(
                title = if (state.loading) "Loading…" else "No proposals",
                hint = "Claude submits a proposal via the MCP server when it wants to make a write.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg)) {
                items(state.groups, key = { "g-${it.category}" }) { group ->
                    ProposalGroupCard(
                        group = group,
                        projectNamesById = state.projectNamesById,
                        expanded = expanded[group.category] ?: true,
                        onToggle = {
                            expanded[group.category] = !(expanded[group.category] ?: true)
                        },
                        vm = vm,
                        onOpen = onOpen,
                        onApprove = approve,
                        onReject = reject,
                    )
                }
                if (state.resolved.isNotEmpty()) {
                    item(key = "resolved-card") {
                        ResolvedCard(
                            resolved = state.resolved,
                            projectNamesById = state.projectNamesById,
                            expanded = resolvedExpanded,
                            onToggle = { resolvedExpanded = !resolvedExpanded },
                            onOpen = onOpen,
                        )
                    }
                }
            }
        }
    }
}

private val SOURCE_FILTER_OPTIONS = listOf(
    FilterChipOption(ProposalsViewModel.SourceFilter.All, "All"),
    FilterChipOption(ProposalsViewModel.SourceFilter.Mcp, "MCP"),
    FilterChipOption(ProposalsViewModel.SourceFilter.Code, "Code"),
    FilterChipOption(ProposalsViewModel.SourceFilter.User, "User"),
)

@Composable
private fun ProposalGroupCard(
    group: ProposalsViewModel.ProposalGroup,
    projectNamesById: Map<Long, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    vm: ProposalsViewModel,
    onOpen: (String) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    // Snapshot the id list once per group identity — the lambdas captured by the bulk buttons
    // would otherwise re-allocate on every recomposition.
    val ids by remember(group) { derivedStateOf { group.proposals.map { it.proposalId } } }
    GroupCard(
        title = group.label,
        count = group.proposals.size,
        expanded = expanded,
        onToggle = onToggle,
        actions = {
            Button(
                onClick = { vm.dispatch(ProposalsViewModel.Intent.BulkApprove(ids)) },
                variant = ButtonVariant.Primary,
            ) { Text("Approve ${group.proposals.size}") }
            Button(
                onClick = { vm.dispatch(ProposalsViewModel.Intent.BulkReject(ids)) },
                variant = ButtonVariant.Ghost,
            ) { Text("Reject") }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val lastIndex = group.proposals.lastIndex
            group.proposals.forEachIndexed { idx, p ->
                key(p.proposalId) {
                    val rowData = remember(p, projectNamesById) {
                        rowDataFor(p, projectNamesById)
                    }
                    ProposalListRow(
                        data = rowData,
                        last = idx == lastIndex,
                        onOpen = onOpen,
                        onApprove = onApprove,
                        onReject = onReject,
                    )
                }
            }
        }
    }
}

/**
 * Pure-data view of a row. Computed once per (proposal, names) snapshot so the row composable's
 * args stay stable across parent recompositions that don't touch this proposal.
 */
@Immutable
private data class ProposalRowData(
    val proposalId: String,
    val verb: String,
    val verbTint: VerbTint,
    val target: String,
    val detail: String?,
    val extraActionCount: Int,
    val actor: String,
    val submittedAt: Instant,
)

private fun rowDataFor(p: Proposal, names: Map<Long, String>): ProposalRowData {
    val first = p.actions.firstOrNull()
    val label = first?.let { proposalLabel(it, names) }
    return ProposalRowData(
        proposalId = p.proposalId,
        verb = label?.verb.orEmpty(),
        verbTint = label?.tintHint ?: VerbTint.Neutral,
        target = label?.target.orEmpty().ifEmpty { "(empty)" },
        detail = label?.detail,
        extraActionCount = (p.actions.size - 1).coerceAtLeast(0),
        actor = p.actor,
        submittedAt = p.submittedAt,
    )
}

@Composable
private fun ProposalListRow(
    data: ProposalRowData,
    last: Boolean,
    onOpen: (String) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    val id = data.proposalId
    GroupRow(onClick = { onOpen(id) }, last = last) {
        if (data.verb.isNotEmpty()) VerbPill(data.verb, data.verbTint)
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(
                data.target,
                style = AppTheme.typography.bodyEmphasis,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (data.detail != null) {
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(data.detail, style = AppTheme.typography.caption, maxLines = 1)
            }
        }
        if (data.extraActionCount > 0) {
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text("+${data.extraActionCount}", style = AppTheme.typography.caption)
            }
        }
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text(
                "${data.actor} · ${relativeTime(data.submittedAt)}",
                style = AppTheme.typography.caption,
                maxLines = 1,
            )
        }
        IconAction(glyph = "✓", color = AppTheme.colors.accentPositive) { onApprove(id) }
        IconAction(glyph = "✗", color = AppTheme.colors.accentDanger) { onReject(id) }
    }
}

@Composable
private fun ResolvedCard(
    resolved: List<Proposal>,
    projectNamesById: Map<Long, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
) {
    GroupCard(
        title = "Resolved this session",
        count = resolved.size,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val lastIndex = resolved.lastIndex
            resolved.forEachIndexed { idx, p ->
                key(p.proposalId) {
                    val rowData = remember(p, projectNamesById) { rowDataFor(p, projectNamesById) }
                    ResolvedRow(
                        data = rowData,
                        status = p.status,
                        last = idx == lastIndex,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResolvedRow(
    data: ProposalRowData,
    status: ProposalStatus,
    last: Boolean,
    onOpen: (String) -> Unit,
) {
    val id = data.proposalId
    GroupRow(onClick = { onOpen(id) }, last = last) {
        val color = when (status) {
            ProposalStatus.Approved -> AppTheme.colors.pinGreen
            ProposalStatus.Rejected -> AppTheme.colors.accentAction
            else -> AppTheme.colors.accentSecondary
        }
        Badge(color = color) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(status.name.lowercase(), style = AppTheme.typography.caption)
            }
        }
        if (data.verb.isNotEmpty()) VerbPill(data.verb, data.verbTint)
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(
                data.target,
                style = AppTheme.typography.bodyEmphasis,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun IconAction(glyph: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppTheme.spacing.cornerSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xs),
    ) {
        ProvideContentColor(color) {
            Text(glyph, style = AppTheme.typography.bodyEmphasis)
        }
    }
}

private fun relativeTime(instant: Instant): String {
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val deltaSec = (nowMs - instant.toEpochMilliseconds()) / 1000L
    return when {
        deltaSec < 60 -> "${deltaSec}s ago"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}
