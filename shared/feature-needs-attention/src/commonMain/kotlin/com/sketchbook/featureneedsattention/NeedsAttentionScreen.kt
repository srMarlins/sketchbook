package com.sketchbook.featureneedsattention

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.SampleCandidate
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

@Composable
fun NeedsAttentionScreen(
    holder: NeedsAttentionStateHolder,
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
        Text("Needs attention", style = AppTheme.typography.title)
        if (state.macImports.isEmpty() && state.missingSamples.isEmpty()) {
            EmptyState(
                title = if (state.loading) "Scanning…" else "All clear",
                hint = "No Mac-imported projects or missing samples found.",
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
            if (state.macImports.isNotEmpty()) {
                item(key = "header-mac") {
                    Text("Mac-imported (${state.macImports.size})", style = AppTheme.typography.bodyEmphasis)
                }
                state.macImports.forEach { f ->
                    item(key = "mac-${f.projectId.value}") { MacImportCard(f, holder) }
                }
            }
            if (state.missingSamples.isNotEmpty()) {
                item(key = "header-missing") {
                    val suffix = if (state.missingSamplesTruncated) " (${state.missingSamples.size} of ${state.missingSamplesTotal})" else " (${state.missingSamplesTotal})"
                    Text("Missing samples$suffix", style = AppTheme.typography.bodyEmphasis)
                }
                state.missingSamples.forEach { f ->
                    item(key = "missing-${f.projectId.value}-${f.missingPath.hashCode()}") {
                        MissingSampleCard(f, holder)
                    }
                }
            }
        }
    }
}

@Composable
private fun MacImportCard(f: MacImportFinding, holder: NeedsAttentionStateHolder) {
    Surface(color = AppTheme.colors.surfacePanel) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                if (f.projectInfoMissing) {
                    Badge(color = AppTheme.colors.accentAction) {
                        Text("no Project Info/", style = AppTheme.typography.caption)
                    }
                }
                if (f.macPathsCount > 0) {
                    Badge(color = AppTheme.colors.pinOrange) {
                        Text("${f.macPathsCount} mac paths", style = AppTheme.typography.caption)
                    }
                }
            }
            Text(f.name, style = AppTheme.typography.bodyEmphasis)
            Text(f.path, style = AppTheme.typography.caption)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // PR-W W5 — actually rewrites the .als through the patcher pipe (was previously
                // a no-op "Acknowledge" that only marked the catalog finding). Same Ghost variant
                // as before — palette stays uniform per feedback_color_restraint, and per the
                // task spec we only relabel the existing button (no new buttons / icons / colors).
                // `RepairRepository.acknowledgeMacImport` is preserved for callers that want the
                // dismiss-without-rewriting path (e.g. MCP tools, future "ignore" flow).
                Button(
                    onClick = { holder.dispatch(NeedsAttentionStateHolder.Intent.RepairMacPaths(f.projectId)) },
                    variant = ButtonVariant.Ghost,
                ) { Text("Repair paths") }
            }
        }
    }
}

@Composable
private fun MissingSampleCard(f: MissingSampleFinding, holder: NeedsAttentionStateHolder) {
    // Local expand toggle for the "N possible matches" affordance — kept inline (per
    // feedback_layer_dont_redesign) rather than spawning a modal/picker.
    var picking by remember(f.projectId.value, f.missingPath) { mutableStateOf(false) }
    Surface(color = AppTheme.colors.surfacePanel) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            Text(f.projectName, style = AppTheme.typography.bodyEmphasis)
            Text(f.missingPath, style = AppTheme.typography.mono)
            // High-confidence: filename+size matches a single sample. Inline chip + Apply.
            f.autoMatch?.let { match ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                ) {
                    Badge(color = AppTheme.colors.pinGreen) {
                        Text("auto-match", style = AppTheme.typography.caption)
                    }
                    Text(
                        match.path,
                        style = AppTheme.typography.caption,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            holder.dispatch(
                                NeedsAttentionStateHolder.Intent.ApplyMatch(
                                    projectId = f.projectId,
                                    missingPath = f.missingPath,
                                    candidatePath = match.path,
                                ),
                            )
                        },
                        variant = ButtonVariant.Ghost,
                    ) { Text("Apply") }
                }
            }
            // Low-confidence: candidates without an auto-match. Show a count + an inline expand
            // affordance to pick one. We don't auto-apply because filename collisions are common
            // in sample libraries (ten different "kick.wav"s).
            if (f.autoMatch == null && f.candidates.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                ) {
                    Text(
                        "${f.candidates.size} possible match${if (f.candidates.size == 1) "" else "es"}",
                        style = AppTheme.typography.caption,
                    )
                    Button(
                        onClick = { picking = !picking },
                        variant = ButtonVariant.Ghost,
                    ) { Text(if (picking) "Hide" else "Show") }
                }
                if (picking) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                        f.candidates.take(5).forEach { c ->
                            CandidatePickRow(f, c, holder)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        holder.dispatch(
                            NeedsAttentionStateHolder.Intent.DismissMissingSample(f.projectId, f.missingPath),
                        )
                    },
                    variant = ButtonVariant.Ghost,
                ) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun CandidatePickRow(
    finding: MissingSampleFinding,
    candidate: SampleCandidate,
    holder: NeedsAttentionStateHolder,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Text(
            "• ${candidate.path}",
            style = AppTheme.typography.caption,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                holder.dispatch(
                    NeedsAttentionStateHolder.Intent.ApplyMatch(
                        projectId = finding.projectId,
                        missingPath = finding.missingPath,
                        candidatePath = candidate.path,
                    ),
                )
            },
            variant = ButtonVariant.Ghost,
        ) { Text("Pick") }
    }
}
