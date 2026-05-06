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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.GroupRow
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Repair surface — Mac-imported and Missing-sample findings rendered as flat keyed items in a
 * single [LazyColumn] so 100+ rows scroll cheaply (lazy virtualization, not bulk-composition).
 *
 * Section visuals: each card has a header item (tintCream band with bulk action), per-row items
 * with `surfaceCard` background and hairline dividers, and a footer item that rounds the bottom
 * corner. Sub-section headers (Auto / Multi / None) live as items inside the missing-samples
 * card. Project-boundary spacers come straight off `entry.isProjectBoundary` — view-side has no
 * sort or grouping logic.
 *
 * Row composables take primitive/`@Immutable` args so Compose skips unchanged rows on
 * recomposition (the pending set churning during a bulk repair touches only the affected ids).
 */
@Composable
fun NeedsAttentionScreen(
    vm: NeedsAttentionViewModel,
    modifier: Modifier = Modifier,
    detailPane: @Composable ((target: NeedsAttentionDetailTarget, dismiss: () -> Unit) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var openTarget by remember { mutableStateOf<NeedsAttentionDetailTarget?>(null) }

    val onRepair: (com.sketchbook.core.ProjectId) -> Unit = remember(vm) {
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
    val onBulkRepair = remember(vm, macIds) {
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
            if (state.macEntries.isEmpty() && state.missingSamples.isEmpty()) {
                EmptyState(
                    title = if (state.loading) "Scanning…" else "All clear",
                    hint = "No Mac-imported projects or missing samples found.",
                )
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
                if (state.macEntries.isNotEmpty()) {
                    macSection(
                        entries = state.macEntries,
                        pending = state.pendingMacRepairs,
                        onBulkRepair = onBulkRepair,
                        onRepair = onRepair,
                        onOpen = onOpenMac,
                    )
                }
                if (state.missingByConfidence.autoMatch.isNotEmpty() ||
                    state.missingByConfidence.multiCandidate.isNotEmpty() ||
                    state.missingByConfidence.noCandidate.isNotEmpty()
                ) {
                    missingSection(
                        buckets = state.missingByConfidence,
                        truncatedShown = if (state.missingSamplesTruncated) {
                            state.missingSamples.size to state.missingSamplesTotal
                        } else null,
                        pending = state.pendingMissingApplies,
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
        val target = openTarget
        if (target != null && detailPane != null) {
            detailPane(target) { openTarget = null }
        }
    }
}

private fun LazyListScope.macSection(
    entries: List<NeedsAttentionViewModel.MacEntry>,
    pending: Set<com.sketchbook.core.ProjectId>,
    onBulkRepair: () -> Unit,
    onRepair: (com.sketchbook.core.ProjectId) -> Unit,
    onOpen: (MacImportFinding) -> Unit,
) {
    item(key = "mac-header") {
        SectionHeader(
            title = "Mac-imported",
            count = entries.size,
            shape = HEADER_SHAPE,
            actions = {
                Button(onClick = onBulkRepair, variant = ButtonVariant.Primary) {
                    Text("Repair all")
                }
            },
        )
    }
    val lastIndex = entries.lastIndex
    itemsIndexed(
        items = entries,
        key = { _, entry -> "mac-${entry.finding.projectId.value}" },
    ) { idx, entry ->
        val finding = entry.finding
        val pid = finding.projectId
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
    item(key = "mac-footer") { CardFooter() }
}

private fun LazyListScope.missingSection(
    buckets: NeedsAttentionViewModel.MissingByConfidence,
    truncatedShown: Pair<Int, Int>?,
    pending: Set<Pair<com.sketchbook.core.ProjectId, String>>,
    onOpen: (MissingSampleFinding) -> Unit,
    onBulkApply: (List<MissingSampleFinding>) -> Unit,
    onBulkDismiss: (List<MissingSampleFinding>) -> Unit,
) {
    val title = if (truncatedShown != null) {
        "Missing samples · ${truncatedShown.first} of ${truncatedShown.second}"
    } else {
        "Missing samples"
    }
    val totalShown = buckets.autoMatch.size + buckets.multiCandidate.size + buckets.noCandidate.size
    item(key = "missing-header") {
        SectionHeader(title = title, count = totalShown, shape = HEADER_SHAPE)
    }

    bucket(
        kind = MissingKind.Auto,
        title = "Auto-match",
        entries = buckets.autoMatch,
        pending = pending,
        onOpen = onOpen,
        bulkAction = { findings ->
            Button(
                onClick = { onBulkApply(findings) },
                variant = ButtonVariant.Primary,
            ) { Text("Apply ${findings.size}") }
        },
    )
    bucket(
        kind = MissingKind.Multi,
        title = "Multiple candidates",
        entries = buckets.multiCandidate,
        pending = pending,
        onOpen = onOpen,
        bulkAction = null,
    )
    bucket(
        kind = MissingKind.None,
        title = "No candidates",
        entries = buckets.noCandidate,
        pending = pending,
        onOpen = onOpen,
        bulkAction = { findings ->
            Button(
                onClick = { onBulkDismiss(findings) },
                variant = ButtonVariant.Ghost,
            ) { Text("Dismiss ${findings.size}") }
        },
    )

    if (truncatedShown != null) {
        item(key = "missing-truncation") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceCard)
                    .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
            ) {
                ProvideContentColor(AppTheme.colors.inkMuted) {
                    Text(
                        "Showing ${truncatedShown.first} of ${truncatedShown.second} — narrow your library or rescan to see the rest",
                        style = AppTheme.typography.caption,
                    )
                }
            }
        }
    }
    item(key = "missing-footer") { CardFooter() }
}

private fun LazyListScope.bucket(
    kind: MissingKind,
    title: String,
    entries: List<NeedsAttentionViewModel.MissingEntry>,
    pending: Set<Pair<com.sketchbook.core.ProjectId, String>>,
    onOpen: (MissingSampleFinding) -> Unit,
    bulkAction: (@Composable (List<MissingSampleFinding>) -> Unit)?,
) {
    if (entries.isEmpty()) return
    item(key = "bucket-${kind.name}") {
        val findings = entries.map { it.finding }
        SubSectionHeader(
            title = title,
            count = entries.size,
            actions = bulkAction?.let { { it(findings) } },
        )
    }
    val lastIndex = entries.lastIndex
    itemsIndexed(
        items = entries,
        key = { _, entry ->
            val f = entry.finding
            "miss-${kind.name}-${f.projectId.value}-${f.missingPath.hashCode()}"
        },
    ) { idx, entry ->
        val f = entry.finding
        val key = f.projectId to f.missingPath
        MissingSampleRow(
            projectName = f.projectName,
            missingPath = f.missingPath,
            kind = kind,
            autoMatchParent = parentDirOf(f.autoMatch?.path),
            candidatesCount = f.candidates.size,
            isProjectBoundary = entry.isProjectBoundary,
            isLast = idx == lastIndex,
            isPending = key in pending,
            onOpen = { onOpen(f) },
        )
    }
}

private enum class MissingKind { Auto, Multi, None }

private val HEADER_SHAPE = RoundedCornerShape(
    topStart = 8.dp,
    topEnd = 8.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)

private val FOOTER_SHAPE = RoundedCornerShape(
    topStart = 0.dp,
    topEnd = 0.dp,
    bottomStart = 8.dp,
    bottomEnd = 8.dp,
)

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    shape: RoundedCornerShape,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppTheme.colors.tintCream)
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.md),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .height(18.dp)
                .padding(horizontal = 1.dp)
                .background(AppTheme.colors.ruleMargin),
        )
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(title, style = AppTheme.typography.bodyEmphasis)
        }
        Badge(color = AppTheme.colors.surfaceCard) {
            ProvideContentColor(AppTheme.colors.inkSecondary) {
                Text(count.toString(), style = AppTheme.typography.caption)
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs, androidx.compose.ui.Alignment.End),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) { actions?.invoke() }
    }
}

@Composable
private fun SubSectionHeader(
    title: String,
    count: Int,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surfaceSunken)
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(title.uppercase(), style = AppTheme.typography.caption)
        }
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text("· $count", style = AppTheme.typography.caption)
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) { actions?.invoke() }
    }
}

@Composable
private fun CardFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FOOTER_SHAPE)
            .background(AppTheme.colors.surfaceCard)
            .height(AppTheme.spacing.sm),
    )
}

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surfaceCard)
            .padding(horizontal = AppTheme.spacing.md),
    ) {
        if (isProjectBoundary) Spacer(modifier = Modifier.height(AppTheme.spacing.sm))
        GroupRow(onClick = { if (!isPending) onOpen() }, last = isLast) {
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
                IconAction(
                    glyph = "↻",
                    color = AppTheme.colors.accentAction,
                    onClick = onRepair,
                )
            }
            ProvideContentColor(AppTheme.colors.inkFaint) {
                Text("›", style = AppTheme.typography.body)
            }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surfaceCard)
            .padding(horizontal = AppTheme.spacing.md),
    ) {
        if (isProjectBoundary) Spacer(modifier = Modifier.height(AppTheme.spacing.sm))
        GroupRow(onClick = { if (!isPending) onOpen() }, last = isLast) {
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

