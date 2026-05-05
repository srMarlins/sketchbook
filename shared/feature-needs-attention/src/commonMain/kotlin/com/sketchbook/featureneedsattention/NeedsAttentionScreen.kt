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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
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
                Button(
                    onClick = { holder.dispatch(NeedsAttentionStateHolder.Intent.AckMacImport(f.projectId)) },
                    variant = ButtonVariant.Ghost,
                ) { Text("Acknowledge") }
            }
        }
    }
}

@Composable
private fun MissingSampleCard(f: MissingSampleFinding, holder: NeedsAttentionStateHolder) {
    Surface(color = AppTheme.colors.surfacePanel) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            Text(f.projectName, style = AppTheme.typography.bodyEmphasis)
            Text(f.missingPath, style = AppTheme.typography.mono)
            f.autoMatch?.let { match ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                    Badge(color = AppTheme.colors.pinGreen) {
                        Text("auto-match", style = AppTheme.typography.caption)
                    }
                    Text(match.path, style = AppTheme.typography.caption)
                }
            }
            if (f.candidates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                    Text("${f.candidates.size} candidates", style = AppTheme.typography.caption)
                    f.candidates.take(3).forEach { c ->
                        Text("• ${c.path}", style = AppTheme.typography.caption)
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
