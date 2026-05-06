package com.sketchbook.featureneedsattention

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.GroupCard
import com.sketchbook.uishared.components.GroupRow
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.SubGroupHeader
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.components.VerbPill
import com.sketchbook.uishared.components.VerbTint
import com.sketchbook.uishared.theme.AppTheme

/**
 * Repair surface. Two top-level [GroupCard]s — Mac-imported and Missing samples — visually
 * matching the Proposals queue: rounded-corner card with a tintCream header band, bulk action in
 * the header, and flat hairline-divided rows nested as the body. Mac variations cluster (sorted
 * by stripped base name); missing samples sub-group by confidence (Auto / Multi / None) using
 * [SubGroupHeader] inside the same card.
 *
 * Performance: each card is one LazyColumn item, rows inside use [key] blocks so Compose can
 * skip unchanged rows when the pending set churns during a bulk repair. Per-row composables take
 * primitive/`@Immutable` args — no full finding-object dependency at the row argument level.
 *
 * `detailPane` is an optional slot the host wires (RootContent owns navigation).
 */
@Composable
fun NeedsAttentionScreen(
    vm: NeedsAttentionViewModel,
    modifier: Modifier = Modifier,
    detailPane: @Composable ((target: NeedsAttentionDetailTarget, dismiss: () -> Unit) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var openTarget by remember { mutableStateOf<NeedsAttentionDetailTarget?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val onRepair: (ProjectId) -> Unit = remember(vm) {
        { id -> vm.dispatch(NeedsAttentionViewModel.Intent.RepairMacPaths(id)) }
    }
    val onOpenMac: (MacImportFinding) -> Unit = remember(vm) {
        { f -> openTarget = NeedsAttentionDetailTarget.Mac(f) }
    }
    val onOpenMissing: (MissingSampleFinding) -> Unit = remember(vm) {
        { f -> openTarget = NeedsAttentionDetailTarget.Missing(f) }
    }
    val macIds by remember {
        derivedStateOf { state.macEntries.map { it.finding.projectId } }
    }
    val onBulkRepair: () -> Unit = remember(vm, macIds) {
        { vm.dispatch(NeedsAttentionViewModel.Intent.BulkRepairMacPaths(macIds)) }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(AppTheme.colors.surfacePage)
                .padding(PaddingValues(AppTheme.spacing.md)),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            PageHeader(title = "Needs attention")
            val hasMissing = state.missingByConfidence.autoMatch.isNotEmpty() ||
                state.missingByConfidence.multiCandidate.isNotEmpty() ||
                state.missingByConfidence.noCandidate.isNotEmpty()
            if (state.macEntries.isEmpty() && !hasMissing) {
                EmptyState(
                    title = if (state.loading) "Scanning…" else "All clear",
                    hint = "No Mac-imported projects or missing samples found.",
                )
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg)) {
                if (state.macEntries.isNotEmpty()) {
                    item(key = "mac-card") {
                        MacImportCard(
                            entries = state.macEntries,
                            pending = state.pendingMacRepairs,
                            expanded = expanded["mac"] ?: true,
                            onToggle = { expanded["mac"] = !(expanded["mac"] ?: true) },
                            onBulkRepair = onBulkRepair,
                            onRepair = onRepair,
                            onOpen = onOpenMac,
                        )
                    }
                }
                if (hasMissing) {
                    item(key = "missing-card") {
                        MissingSamplesCard(
                            buckets = state.missingByConfidence,
                            shown = state.missingSamples.size,
                            total = state.missingSamplesTotal,
                            truncated = state.missingSamplesTruncated,
                            pending = state.pendingMissingApplies,
                            expanded = expanded["missing"] ?: true,
                            onToggle = { expanded["missing"] = !(expanded["missing"] ?: true) },
                            onOpen = onOpenMissing,
                            onBulkApply = { findings ->
                                vm.dispatch(NeedsAttentionViewModel.Intent.BulkApplyAutoMatch(findings))
                            },
                            onBulkDismiss = { findings ->
                                vm.dispatch(NeedsAttentionViewModel.Intent.BulkDismiss(findings))
                            },
                        )
                    }
                }
            }
        }
        val target = openTarget
        if (target != null && detailPane != null) {
            detailPane(target) { openTarget = null }
        }
    }
}

@Composable
private fun MacImportCard(
    entries: List<NeedsAttentionViewModel.MacEntry>,
    pending: Set<ProjectId>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onBulkRepair: () -> Unit,
    onRepair: (ProjectId) -> Unit,
    onOpen: (MacImportFinding) -> Unit,
) {
    GroupCard(
        title = "Mac-imported",
        count = entries.size,
        expanded = expanded,
        onToggle = onToggle,
        actions = {
            Button(onClick = onBulkRepair, variant = ButtonVariant.Primary) { Text("Repair all") }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val lastIndex = entries.lastIndex
            entries.forEachIndexed { idx, entry ->
                val finding = entry.finding
                val pid = finding.projectId
                key(pid.value) {
                    MacImportRow(
                        name = finding.name,
                        macPathsCount = finding.macPathsCount,
                        projectInfoMissing = finding.projectInfoMissing,
                        isProjectBoundary = entry.isProjectBoundary,
                        isLast = idx == lastIndex,
                        isPending = pid in pending,
                        onOpen = { onOpen(finding) },
                        onRepair = { onRepair(pid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingSamplesCard(
    buckets: NeedsAttentionViewModel.MissingByConfidence,
    shown: Int,
    total: Int,
    truncated: Boolean,
    pending: Set<Pair<ProjectId, String>>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (MissingSampleFinding) -> Unit,
    onBulkApply: (List<MissingSampleFinding>) -> Unit,
    onBulkDismiss: (List<MissingSampleFinding>) -> Unit,
) {
    val title = if (truncated) "Missing samples · $shown of $total" else "Missing samples"
    val totalShown = buckets.autoMatch.size + buckets.multiCandidate.size + buckets.noCandidate.size
    val sections = remember(buckets) {
        listOfNotNull(
            buckets.autoMatch.takeIf { it.isNotEmpty() }
                ?.let { Section(MissingKind.Auto, "Auto-match", it) },
            buckets.multiCandidate.takeIf { it.isNotEmpty() }
                ?.let { Section(MissingKind.Multi, "Multiple candidates", it) },
            buckets.noCandidate.takeIf { it.isNotEmpty() }
                ?.let { Section(MissingKind.None, "No candidates", it) },
        )
    }

    GroupCard(
        title = title,
        count = totalShown,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val lastSectionIdx = sections.lastIndex
            sections.forEachIndexed { sIdx, section ->
                key(section.kind.name) {
                    val findings = remember(section.entries) { section.entries.map { it.finding } }
                    SubGroupHeader(
                        title = section.title,
                        count = section.entries.size,
                        actions = {
                            when (section.kind) {
                                MissingKind.Auto -> Button(
                                    onClick = { onBulkApply(findings) },
                                    variant = ButtonVariant.Primary,
                                ) { Text("Apply ${section.entries.size}") }
                                MissingKind.None -> Button(
                                    onClick = { onBulkDismiss(findings) },
                                    variant = ButtonVariant.Ghost,
                                ) { Text("Dismiss ${section.entries.size}") }
                                MissingKind.Multi -> Unit
                            }
                        },
                    )
                    val lastRowIdx = section.entries.lastIndex
                    section.entries.forEachIndexed { idx, entry ->
                        val f = entry.finding
                        val pendingKey = f.projectId to f.missingPath
                        // The "last row" hairline only suppresses on the last row of the *last*
                        // section so we don't draw a stray divider just before a SubGroupHeader.
                        val isLast = sIdx == lastSectionIdx && idx == lastRowIdx && !truncated
                        key(f.projectId.value, f.missingPath) {
                            MissingSampleRow(
                                projectName = f.projectName,
                                missingPath = f.missingPath,
                                kind = section.kind,
                                autoMatchParent = parentDirOf(f.autoMatch?.path),
                                candidatesCount = f.candidates.size,
                                isProjectBoundary = entry.isProjectBoundary,
                                isLast = isLast,
                                isPending = pendingKey in pending,
                                onOpen = { onOpen(f) },
                            )
                        }
                    }
                }
            }
            if (truncated) {
                ProvideContentColor(AppTheme.colors.inkMuted) {
                    Text(
                        "Showing $shown of $total — narrow your library or rescan to see the rest",
                        style = AppTheme.typography.caption,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
                    )
                }
            }
        }
    }
}

private enum class MissingKind { Auto, Multi, None }

private data class Section(
    val kind: MissingKind,
    val title: String,
    val entries: List<NeedsAttentionViewModel.MissingEntry>,
)

@Composable
private fun MacImportRow(
    name: String,
    macPathsCount: Int,
    projectInfoMissing: Boolean,
    isProjectBoundary: Boolean,
    isLast: Boolean,
    isPending: Boolean,
    onOpen: () -> Unit,
    onRepair: () -> Unit,
) {
    if (isProjectBoundary) Spacer(modifier = Modifier.height(AppTheme.spacing.sm))
    GroupRow(onClick = { if (!isPending) onOpen() }, last = isLast) {
        VerbPill("Repair", VerbTint.Repair)
        ProvideContentColor(if (isPending) AppTheme.colors.inkMuted else AppTheme.colors.inkPrimary) {
            Text(
                name,
                style = AppTheme.typography.bodyEmphasis,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (isPending) {
            Badge(color = AppTheme.colors.tintBlue) {
                ProvideContentColor(AppTheme.colors.inkPrimary) {
                    Text("repairing…", style = AppTheme.typography.caption, maxLines = 1)
                }
            }
        } else {
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(
                    macPathsLabel(macPathsCount, projectInfoMissing),
                    style = AppTheme.typography.caption,
                    maxLines = 1,
                )
            }
            IconAction(glyph = "↻", color = AppTheme.colors.accentAction, onClick = onRepair)
        }
        ProvideContentColor(AppTheme.colors.inkFaint) {
            Text("›", style = AppTheme.typography.body)
        }
    }
}

@Composable
private fun MissingSampleRow(
    projectName: String,
    missingPath: String,
    kind: MissingKind,
    autoMatchParent: String,
    candidatesCount: Int,
    isProjectBoundary: Boolean,
    isLast: Boolean,
    isPending: Boolean,
    onOpen: () -> Unit,
) {
    if (isProjectBoundary) Spacer(modifier = Modifier.height(AppTheme.spacing.sm))
    GroupRow(onClick = { if (!isPending) onOpen() }, last = isLast) {
        VerbPill("Sample", VerbTint.Repair)
        Badge(color = AppTheme.colors.tintCream) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(projectName, style = AppTheme.typography.caption, maxLines = 1)
            }
        }
        ProvideContentColor(if (isPending) AppTheme.colors.inkMuted else AppTheme.colors.inkPrimary) {
            Text(
                filenameOf(missingPath),
                style = AppTheme.typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (isPending) {
            Badge(color = AppTheme.colors.tintBlue) {
                ProvideContentColor(AppTheme.colors.inkPrimary) {
                    Text("applying…", style = AppTheme.typography.caption, maxLines = 1)
                }
            }
        } else when (kind) {
            MissingKind.Auto -> Badge(color = AppTheme.colors.tintSage) {
                ProvideContentColor(AppTheme.colors.inkPrimary) {
                    Text(
                        "→ ${autoMatchParent.ifEmpty { "match" }}/",
                        style = AppTheme.typography.caption,
                        maxLines = 1,
                    )
                }
            }
            MissingKind.Multi -> Badge(color = AppTheme.colors.tintBlue) {
                ProvideContentColor(AppTheme.colors.inkPrimary) {
                    Text("$candidatesCount matches", style = AppTheme.typography.caption)
                }
            }
            MissingKind.None -> Badge(color = AppTheme.colors.tintRose) {
                ProvideContentColor(AppTheme.colors.inkPrimary) {
                    Text("no match", style = AppTheme.typography.caption)
                }
            }
        }
        ProvideContentColor(AppTheme.colors.inkFaint) {
            Text("›", style = AppTheme.typography.body)
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

private fun macPathsLabel(count: Int, projectInfoMissing: Boolean): String {
    val plural = if (count == 1) "" else "s"
    val infoSuffix = if (projectInfoMissing) " · no info/" else ""
    return "$count mac path$plural$infoSuffix"
}

private fun filenameOf(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

private fun parentDirOf(path: String?): String {
    if (path == null) return ""
    val normalized = path.replace('\\', '/')
    val idx = normalized.lastIndexOf('/')
    if (idx <= 0) return ""
    val parent = normalized.substring(0, idx)
    val parts = parent.split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) parts.joinToString("/") else ".../${parts.takeLast(2).joinToString("/")}"
}
