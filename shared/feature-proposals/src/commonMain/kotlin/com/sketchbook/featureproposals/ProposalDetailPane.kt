package com.sketchbook.featureproposals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.featureproposals.format.proposalLabel
import com.sketchbook.repo.ProposalAction
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.DetailPane
import com.sketchbook.uishared.components.DetailPaneEmpty
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Tag
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.components.VerbPill
import com.sketchbook.uishared.theme.AppTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Right-pane content for the selected proposal. Renders a verb pill + resolved project name and
 * a labelled key/value table built from the deserialized JSON args, falling back to a generic
 * "Args" dump for unknown action types so a malformed proposal still shows something useful.
 *
 * Picks the proposal out of `state.pending + state.resolved` once and remembers it by id; if the
 * underlying list mutates the row away (approve/reject elsewhere) the pane shows the empty
 * placeholder until the host clears the selection.
 */
@Composable
fun ProposalDetailPane(
    proposalId: String,
    vm: ProposalsViewModel,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val proposal =
        remember(proposalId, state.pending, state.resolved) {
            (state.pending.asSequence() + state.resolved.asSequence())
                .firstOrNull { it.proposalId == proposalId }
        }
    if (proposal == null) {
        DetailPaneEmpty("Proposal not found")
        return
    }
    val showJson = remember(proposalId) { mutableStateMapOf<Int, Boolean>() }
    val names = state.projectNamesById

    DetailPane(
        title = "Proposal · ${proposal.proposalId}",
        onDismiss = onDismiss,
        body = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tag(label = proposal.actor)
                ProvideContentColor(AppTheme.colors.inkMuted) {
                    Text(relativeTime(proposal.submittedAt), style = AppTheme.typography.caption)
                }
            }
            proposal.rationale?.takeIf { it.isNotBlank() }?.let { rationale ->
                ProvideContentColor(AppTheme.colors.inkPrimary) {
                    Text(rationale, style = AppTheme.typography.body)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
                proposal.actions.forEachIndexed { idx, action ->
                    ActionDetail(
                        action = action,
                        projectNamesById = names,
                        expanded = showJson[idx] == true,
                        onToggle = { showJson[idx] = !(showJson[idx] ?: false) },
                    )
                }
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                Button(
                    onClick = {
                        vm.dispatch(ProposalsViewModel.Intent.Reject(proposal.proposalId))
                        onDismiss()
                    },
                    variant = ButtonVariant.Ghost,
                ) { Text("Reject") }
                Row(modifier = Modifier.weight(1f)) {}
                Button(
                    onClick = {
                        vm.dispatch(ProposalsViewModel.Intent.Approve(proposal.proposalId))
                        onDismiss()
                    },
                    variant = ButtonVariant.Primary,
                ) { Text("Approve") }
            }
        },
    )
}

@Composable
private fun ActionDetail(
    action: ProposalAction,
    projectNamesById: Map<Long, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val label = remember(action, projectNamesById) { proposalLabel(action, projectNamesById) }
    val fields = remember(action, projectNamesById) { actionFields(action, projectNamesById) }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            VerbPill(label.verb, label.tintHint)
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(
                    label.target.ifEmpty { "(no target)" },
                    style = AppTheme.typography.bodyEmphasis,
                    modifier = Modifier.weight(1f),
                )
            }
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(
                    if (expanded) "Hide JSON" else "Show JSON",
                    style = AppTheme.typography.caption,
                    modifier =
                        Modifier
                            .clickable(onClick = onToggle)
                            .padding(AppTheme.spacing.xs),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            fields.forEach { (key, value) -> LabelledRow(key, value) }
        }
        if (expanded) {
            ProvideContentColor(AppTheme.colors.inkSecondary) {
                Text(action.args.toString(), style = AppTheme.typography.mono)
            }
        }
    }
}

@Composable
private fun LabelledRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text(label, style = AppTheme.typography.caption, modifier = Modifier.width(120.dp))
        }
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(value, style = AppTheme.typography.body)
        }
    }
}

/**
 * Maps a deserialized [ProposalAction] to ordered key/value fields for the detail pane. Each
 * branch mirrors `proposalLabel`'s switch so any new action type lands in both places. Unknown
 * types fall back to a raw arg dump rather than going blank.
 */
private fun actionFields(
    action: ProposalAction,
    projectNamesById: Map<Long, String>,
): List<Pair<String, String>> {
    fun s(key: String): String? = (action.args[key] as? JsonPrimitive)?.contentOrNull

    fun arr(key: String): List<String> =
        (action.args[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    val pidLong = s("project_id")?.toLongOrNull()
    val resolvedName = pidLong?.let { projectNamesById[it] }
    val projectField =
        when {
            resolvedName != null -> listOf("Project" to "$resolvedName (#$pidLong)")
            pidLong != null -> listOf("Project" to "#$pidLong")
            else -> emptyList()
        }
    return when (action.type) {
        "MoveProject" -> {
            projectField +
                listOf(
                    "From" to (s("from") ?: "—"),
                    "To" to (s("to") ?: "—"),
                )
        }

        "RenameProject" -> {
            projectField +
                listOf(
                    "From" to ((s("from_") ?: s("from")) ?: "—"),
                    "To" to (s("to") ?: "—"),
                )
        }

        "ArchiveProject" -> {
            projectField + listOf("Action" to "Archive")
        }

        "SetTags" -> {
            projectField +
                listOf(
                    "Before" to arr("before").joinToString(", ").ifEmpty { "(none)" },
                    "After" to arr("after").joinToString(", ").ifEmpty { "(none)" },
                )
        }

        "SetColorTag" -> {
            projectField +
                listOf(
                    "Before" to (s("before") ?: "(none)"),
                    "After" to (s("after") ?: "(none)"),
                )
        }

        "Undo" -> {
            listOf("Action" to "Undo previous batch")
        }

        else -> {
            action.args.entries.map { (k, v) -> k to v.toString() }
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
