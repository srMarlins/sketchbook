package com.sketchbook.featureprojects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.sketchbook.core.ProjectId
import com.sketchbook.core.Stage
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.HighlightChip
import com.sketchbook.uishared.components.HighlightsStrip
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.ScanIndicator
import com.sketchbook.uishared.components.ShelfHeader
import com.sketchbook.uishared.components.SongStageChip
import com.sketchbook.uishared.components.SongStageTone
import com.sketchbook.uishared.components.SongStrip
import com.sketchbook.uishared.components.SongStripData
import com.sketchbook.uishared.components.SongSyncBadge
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.components.TextField
import com.sketchbook.uishared.theme.AppTheme

private const val SHELF_LIMIT = 8
private const val WIDE_THRESHOLD_DP = 920

/**
 * Project list screen: dashboard with side-by-side shelves on wide windows + an inline detail
 * panel that slides in from the right. Rows are grouped per project root via
 * [deriveProjectGroups] so a folder with `Track v1.als / v2.als / final.als` collapses to a
 * single shelf entry.
 *
 * **Bucketing.** Mirrors the web's classifier exactly (web/src/mocks/handlers.ts:160) — see
 * [bucketize]. We don't reinvent the rules here; the desktop dashboard must agree with the web
 * snapshot users were running before so re-installs feel continuous.
 *
 * **Sync plumbing.** [syncStateFor] is an optional lookup from the host (Desktop) into its
 * `SyncQueue`. When provided, each row gets its per-project pip; when null, the trailing slot
 * is empty. Stays optional so feature-projects doesn't take a hard dep on the queue interface
 * being wired through (test harnesses pass null).
 */
@Composable
fun ProjectListScreen(
    vm: ProjectListViewModel,
    modifier: Modifier = Modifier,
    scanLabel: String? = null,
    scanActive: Boolean = false,
    syncStateFor: ((ProjectId) -> ProjectSyncState)? = null,
    detailPanel: (@Composable (ProjectId, () -> Unit) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dispatch = vm::dispatch
    val listState = rememberLazyListState()

    // When search activates, scroll the page to the top so the search field — and the overlay
    // panel anchored at the top of the content area — are visible. Without this, typing while
    // scrolled deep into the dashboard would render the overlay above where the user is looking.
    LaunchedEffect(state.query.isNotBlank()) {
        if (state.query.isNotBlank()) listState.scrollToItem(0)
    }

    // Stable callbacks: hoisted so children passed `dispatch(...)` lambdas don't churn each
    // recomposition. With the LazyColumn below, only on-screen items recompose anyway, but
    // stable callbacks let `key`-bounded items skip even when `state` updates.
    val onOpenDetail: (ProjectId) -> Unit = remember(vm) { { id -> dispatch(ProjectListViewModel.Intent.OpenDetail(id)) } }
    val onZoomShelf: (ShelfId?) -> Unit = remember(vm) { { shelf -> dispatch(ProjectListViewModel.Intent.ZoomShelf(shelf)) } }
    val onShuffleGems: () -> Unit = remember(vm) { { dispatch(ProjectListViewModel.Intent.ShuffleGems) } }
    val onCloseDetail: () -> Unit = remember(vm) { { dispatch(ProjectListViewModel.Intent.CloseDetail) } }
    val onClearSearch: () -> Unit = remember(vm) { { dispatch(ProjectListViewModel.Intent.Search("")) } }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth.value >= WIDE_THRESHOLD_DP
        val viewportMaxHeight = maxHeight

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(PaddingValues(horizontal = AppTheme.spacing.xl, vertical = AppTheme.spacing.lg)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
        ) {
            // Header — single item; the inner Column holds the page title + search field +
            // scan ribbon and never changes its child count.
            item(key = "header") {
                Column(
                    modifier = Modifier.widthIn(max = 1240.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                ) {
                    PageHeader(
                        title = state.zoomShelf?.title() ?: "Projects",
                        subtitle = state.zoomShelf?.subtitle()
                            ?: "Your Ableton catalog — ${state.groups.size} project${if (state.groups.size == 1) "" else "s"}, "
                            + "${state.rows.size} `.als` file${if (state.rows.size == 1) "" else "s"}.",
                        actions = if (state.zoomShelf != null) {
                            { BackToOverview(onClick = { onZoomShelf(null) }) }
                        } else null,
                    )
                    TextField(
                        value = state.query,
                        onChange = { dispatch(ProjectListViewModel.Intent.Search(it)) },
                        placeholder = "Search projects, plugins, samples…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event -> handleSearchKey(event, state, dispatch) },
                        leading = { Text("⌕", style = AppTheme.typography.body) },
                        trailing = if (state.query.isNotBlank()) {
                            {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable(onClick = onClearSearch)
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                ) {
                                    Text("×", style = AppTheme.typography.body)
                                }
                            }
                        } else null,
                    )
                    FilterChipsRow(
                        tempoRange = state.tempoRange,
                        keyFilter = state.keyFilter,
                        distinctKeys = state.distinctKeys,
                        stageFilter = state.stageFilter,
                        onTempoChange = { range ->
                            dispatch(ProjectListViewModel.Intent.SetTempoRange(range))
                        },
                        onKeyChange = { key ->
                            dispatch(ProjectListViewModel.Intent.SetKeyFilter(key))
                        },
                        onStageChange = { stages ->
                            dispatch(ProjectListViewModel.Intent.SetStageFilter(stages))
                        },
                    )
                    ScanIndicator(
                        active = scanActive,
                        label = scanLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Body — branch by zoom mode. Zoomed = flat list of rows (the perf win); unzoomed
            // = dashboard with capped shelves rendered as a single item (each shelf's row count
            // is bounded by SHELF_LIMIT, so a non-lazy Column is fine inside).
            val zoom = state.zoomShelf
            if (zoom != null) {
                items(
                    items = zoom.bucket(state.buckets),
                    key = { group -> group.representative.id.value },
                ) { group ->
                    SongStripRow(
                        group = group,
                        sync = syncStateFor?.invoke(group.representative.id),
                        onOpen = onOpenDetail,
                        modifier = Modifier.widthIn(max = 1240.dp).fillMaxWidth(),
                    )
                }
            } else {
                item(key = "dashboard") {
                    HomeDashboard(
                        buckets = state.buckets,
                        gemsView = state.gemsView,
                        isWide = isWide,
                        onOpen = onOpenDetail,
                        onZoomShelf = onZoomShelf,
                        onShuffleGems = onShuffleGems,
                        syncStateFor = syncStateFor,
                    )
                }
            }
        }

        // Search overlay — scrim covers the whole content area; panel is anchored to the top
        // (under the search field, since search activation scrolls the LazyColumn back to top).
        if (state.query.isNotBlank()) {
            // Scrim: paper-fog over the base layer; click anywhere to clear the query.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(AppTheme.colors.surfaceSunken.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClearSearch,
                    ),
            )
            // Anchor the panel just under the search field. The header item's height is roughly
            // the page padding (lg) + page title + search field + scan ribbon + spacing —
            // approximated as 200dp so the panel reads as "below the field".
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 200.dp, start = AppTheme.spacing.xl, end = AppTheme.spacing.xl)
                    .widthIn(max = 1240.dp + AppTheme.spacing.xl.value.dp * 2)
                    .align(Alignment.TopCenter)
                    .heightIn(max = (viewportMaxHeight - 220.dp).coerceAtLeast(200.dp))
                    .clip(RoundedCornerShape(AppTheme.spacing.cornerCard))
                    .background(AppTheme.colors.surfaceCard)
                    .border(1.dp, AppTheme.colors.ruleLineStrong, RoundedCornerShape(AppTheme.spacing.cornerCard)),
            ) {
                SearchOverlayPanel(
                    groups = state.searchResults,
                    selectedIndex = state.searchSelectedIndex,
                    syncStateFor = syncStateFor,
                    onOpen = onOpenDetail,
                )
            }
        }

        DetailPanel(
            openId = state.openDetailId,
            onDismiss = onCloseDetail,
            content = detailPanel,
        )
    }
}

/**
 * Pure key-event reducer for the search field. Returns `true` if the event was consumed.
 * Lives outside the composable so the keyboard contract is testable without Compose.
 */
private fun handleSearchKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: ProjectListViewModel.State,
    dispatch: (ProjectListViewModel.Intent) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val key = event.key
    return when {
        key == Key.Escape && state.query.isNotBlank() -> {
            dispatch(ProjectListViewModel.Intent.Search(""))
            true
        }
        state.query.isNotBlank() && state.searchResults.isNotEmpty() && key == Key.DirectionDown -> {
            dispatch(ProjectListViewModel.Intent.NavigateSearchNext)
            true
        }
        state.query.isNotBlank() && state.searchResults.isNotEmpty() && key == Key.DirectionUp -> {
            dispatch(ProjectListViewModel.Intent.NavigateSearchPrev)
            true
        }
        state.query.isNotBlank() && state.searchResults.isNotEmpty() && (key == Key.Enter || key == Key.NumPadEnter) -> {
            dispatch(ProjectListViewModel.Intent.OpenSelectedSearch)
            true
        }
        else -> false
    }
}

@Composable
private fun BackToOverview(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProvideContentColor(colors.inkMuted) {
            Text(
                "← OVERVIEW",
                style = AppTheme.typography.mono.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
    }
}

/**
 * Single shelf row item. Pulled out so it's the same composable in zoomed-list `items()` and
 * search-overlay `items()` blocks — equal `key` slot identity across modes lets the slot table
 * reuse layout when the same project appears in both.
 */
@Composable
private fun SongStripRow(
    group: ProjectGroup,
    sync: ProjectSyncState?,
    onOpen: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    SongStrip(
        data = group.toSongStripData(sync),
        onOpen = { onOpen(group.representative.id) },
        modifier = modifier,
    )
}

@Composable
private fun HomeDashboard(
    buckets: Buckets,
    gemsView: List<ProjectGroup>,
    isWide: Boolean,
    onOpen: (ProjectId) -> Unit,
    onZoomShelf: (ShelfId?) -> Unit,
    onShuffleGems: () -> Unit,
    syncStateFor: ((ProjectId) -> ProjectSyncState)?,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.widthIn(max = 1240.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
    ) {
        // Six chips in fixed display order, mirroring web/src/components/data/HighlightsStrip.tsx
        // CATEGORIES. Empty categories stay in the row (dim) so layout doesn't shift between scans.
        HighlightsStrip(
            chips = listOf(
                HighlightChip(ShelfId.CurrentlyWorking.id, "Currently working on", buckets.currentlyWorking.size, colors.pinBlue, colors.tintBlue),
                HighlightChip(ShelfId.ForgottenGems.id, "Forgotten gems", buckets.forgottenGems.size, colors.accentWarning, colors.tintCream),
                HighlightChip(ShelfId.AlmostDone.id, "Almost done", buckets.almostDone.size, colors.pinOrange, colors.tintSage),
                HighlightChip(ShelfId.HasPotential.id, "Has potential", buckets.hasPotential.size, colors.pinPurple, colors.tintRose),
                HighlightChip(ShelfId.Untriaged.id, "Untriaged", buckets.untriaged.size, colors.inkMuted, colors.surfaceCard),
                HighlightChip(ShelfId.Broken.id, "Broken", buckets.broken.size, colors.accentDanger, colors.tintRose),
                HighlightChip(ShelfId.Archived.id, "Archived", buckets.archived.size, colors.inkMuted, colors.surfaceCard),
            ),
            onSelect = { id -> ShelfId.fromId(id)?.let(onZoomShelf) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (buckets.all.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "No projects yet",
                    hint = "Open Settings → Library roots and add the folder where you keep your Ableton projects. Sketchbook scans on launch and updates the catalog as files change.",
                )
            }
        } else if (isWide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Shelf(
                        title = "Currently working on",
                        subtitle = "Active sketches — blue color tag or touched in the last 6 months. ${buckets.currentlyWorking.size} total.",
                        groups = buckets.currentlyWorking.take(SHELF_LIMIT),
                        onOpen = onOpen,
                        onSeeAll = { onZoomShelf(ShelfId.CurrentlyWorking) },
                        syncStateFor = syncStateFor,
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    Shelf(
                        title = "Forgotten gems",
                        subtitle = "High-effort sketches buried in the catalog. ${buckets.forgottenGems.size} total.",
                        groups = gemsView.take(SHELF_LIMIT),
                        onOpen = onOpen,
                        onSeeAll = { onZoomShelf(ShelfId.ForgottenGems) },
                        onShuffle = if (buckets.forgottenGems.size > SHELF_LIMIT) onShuffleGems else null,
                        syncStateFor = syncStateFor,
                    )
                }
            }
        } else {
            Shelf(
                title = "Currently working on",
                subtitle = "Active sketches — blue color tag or touched recently.",
                groups = buckets.currentlyWorking.take(SHELF_LIMIT),
                onOpen = onOpen,
                onSeeAll = { onZoomShelf(ShelfId.CurrentlyWorking) },
                syncStateFor = syncStateFor,
            )
            Shelf(
                title = "Forgotten gems",
                subtitle = "High-effort sketches buried in the catalog.",
                groups = gemsView.take(SHELF_LIMIT),
                onOpen = onOpen,
                onSeeAll = { onZoomShelf(ShelfId.ForgottenGems) },
                onShuffle = if (buckets.forgottenGems.size > SHELF_LIMIT) onShuffleGems else null,
                syncStateFor = syncStateFor,
            )
        }
    }
}

/**
 * Single shelf — header + up to [SHELF_LIMIT] rows. Row count is bounded so the non-lazy
 * `Column` is fine; converting to `LazyColumn` here would conflict with the parent
 * `LazyColumn`'s scroll measurement. Rows are keyed via the surrounding `key()` block to keep
 * slot identity stable when the bucket changes.
 */
@Composable
private fun Shelf(
    title: String,
    subtitle: String,
    groups: List<ProjectGroup>,
    onOpen: (ProjectId) -> Unit,
    onSeeAll: () -> Unit,
    syncStateFor: ((ProjectId) -> ProjectSyncState)?,
    onShuffle: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShelfHeader(title = title, subtitle = subtitle, onSeeAll = onSeeAll, onShuffle = onShuffle)
        if (groups.isEmpty()) {
            Box(modifier = Modifier.padding(start = 4.dp)) {
                ProvideContentColor(AppTheme.colors.inkMuted) {
                    Text("Nothing here yet.", style = AppTheme.typography.body)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (group in groups) {
                    androidx.compose.runtime.key(group.representative.id.value) {
                        SongStripRow(
                            group = group,
                            sync = syncStateFor?.invoke(group.representative.id),
                            onOpen = onOpen,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Search results inside the overlay panel. Uses a [LazyColumn] so a 100-result query doesn't
 * compose 100 rows when only a handful are visible. Selection is rendered as an outer accent
 * border on the highlighted row.
 */
@Composable
private fun SearchOverlayPanel(
    groups: List<ProjectGroup>,
    selectedIndex: Int,
    syncStateFor: ((ProjectId) -> ProjectSyncState)?,
    onOpen: (ProjectId) -> Unit,
) {
    if (groups.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(title = "No matches", hint = "Try a different query.")
        }
        return
    }
    val accent = AppTheme.colors.accentAction
    val cardShape = RoundedCornerShape(AppTheme.spacing.cornerCard)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            items = groups,
            key = { _, group -> group.representative.id.value },
        ) { index, group ->
            val rowMod = if (index == selectedIndex) Modifier.border(2.dp, accent, cardShape) else Modifier
            SongStripRow(
                group = group,
                sync = syncStateFor?.invoke(group.representative.id),
                onOpen = onOpen,
                modifier = rowMod,
            )
        }
    }
}

@Composable
private fun BoxScope.DetailPanel(
    openId: ProjectId?,
    onDismiss: () -> Unit,
    content: (@Composable (ProjectId, () -> Unit) -> Unit)?,
) {
    val ease = remember { CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f) }
    AnimatedVisibility(
        visible = openId != null,
        enter = fadeIn(tween(180, easing = ease)),
        exit = fadeOut(tween(180, easing = ease)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.surfaceOverlay)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
    AnimatedVisibility(
        visible = openId != null,
        enter = slideInHorizontally(tween(280, easing = ease)) { it },
        exit = slideOutHorizontally(tween(220, easing = ease)) { it },
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 460.dp, max = 820.dp)
                .fillMaxHeight()
                .background(AppTheme.colors.surfaceCard),
        ) {
            val id = openId
            if (id != null && content != null) {
                content(id, onDismiss)
            }
        }
    }
}

/**
 * Toolbar filter chips: Tempo range + Key. Two pill-shaped chips matching the home
 * `HighlightsStrip` styling (same shape, border, padding, mono caption type) per
 * `feedback_color_restraint` — no new colors per state. Each opens a small Popup with the
 * actual editor; clicking the chip toggles the popup, and clicking outside dismisses it.
 */
@Composable
private fun FilterChipsRow(
    tempoRange: ClosedFloatingPointRange<Double>?,
    keyFilter: String?,
    distinctKeys: List<String>,
    stageFilter: Set<Stage>,
    onTempoChange: (ClosedFloatingPointRange<Double>?) -> Unit,
    onKeyChange: (String?) -> Unit,
    onStageChange: (Set<Stage>) -> Unit,
) {
    var tempoOpen by remember { mutableStateOf(false) }
    var keyOpen by remember { mutableStateOf(false) }
    var stageOpen by remember { mutableStateOf(false) }

    val tempoLabel = tempoRange?.let { range ->
        "Tempo: ${formatBpm(range.start)}–${formatBpm(range.endInclusive)}"
    } ?: "Tempo: any"
    val keyLabel = "Key: ${keyFilter ?: "any"}"
    val stageLabel = if (stageFilter.isEmpty()) "Stage: any"
        else "Stage: " + stageFilter.sortedBy { it.ordinal }.joinToString(", ") { it.name.lowercase() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                label = tempoLabel,
                active = tempoRange != null,
                onClick = { tempoOpen = !tempoOpen },
            )
            if (tempoOpen) {
                Popup(
                    onDismissRequest = { tempoOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    TempoFilterPopup(
                        current = tempoRange,
                        onApply = { range ->
                            onTempoChange(range)
                            tempoOpen = false
                        },
                        onClear = {
                            onTempoChange(null)
                            tempoOpen = false
                        },
                    )
                }
            }
        }
        Box {
            FilterChip(
                label = keyLabel,
                active = keyFilter != null,
                onClick = { keyOpen = !keyOpen },
            )
            if (keyOpen) {
                Popup(
                    onDismissRequest = { keyOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    KeyFilterPopup(
                        keys = distinctKeys,
                        current = keyFilter,
                        onPick = { picked ->
                            onKeyChange(picked)
                            keyOpen = false
                        },
                    )
                }
            }
        }
        Box {
            FilterChip(
                label = stageLabel,
                active = stageFilter.isNotEmpty(),
                onClick = { stageOpen = !stageOpen },
            )
            if (stageOpen) {
                Popup(
                    onDismissRequest = { stageOpen = false },
                    // Multi-select stays open across toggles; only "Any" / outside-click close it.
                    properties = PopupProperties(focusable = true),
                ) {
                    StageFilterPopup(
                        current = stageFilter,
                        onToggle = { stage ->
                            val next = if (stage in stageFilter) stageFilter - stage else stageFilter + stage
                            onStageChange(next)
                        },
                        onAny = {
                            onStageChange(emptySet())
                            stageOpen = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Stage popup: an "Any" reset row + one toggle row per [Stage]. Multi-select — clicking a
 * stage toggles it in the active set without closing the popup, matching how producers expect
 * a checkbox-style filter to feel ("show me Mixing AND Done").
 */
@Composable
private fun StageFilterPopup(
    current: Set<Stage>,
    onToggle: (Stage) -> Unit,
    onAny: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .clip(RoundedCornerShape(AppTheme.spacing.cornerCard))
            .background(colors.surfaceCard)
            .border(1.dp, colors.ruleLineStrong, RoundedCornerShape(AppTheme.spacing.cornerCard)),
    ) {
        StageFilterRow(label = "Any", selected = current.isEmpty(), onClick = onAny)
        for (stage in Stage.values()) {
            StageFilterRow(
                label = stage.displayName,
                selected = stage in current,
                onClick = { onToggle(stage) },
            )
        }
    }
}

@Composable
private fun StageFilterRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.tintCream else colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.spacing.md, vertical = 8.dp),
    ) {
        ProvideContentColor(if (selected) colors.inkPrimary else colors.inkSecondary) {
            Text(label, style = AppTheme.typography.body)
        }
    }
}

/** Single pill chip — same geometry/typography as HighlightsStrip chips for visual cohesion. */
@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val borderColor = if (active) colors.accentSecondary else colors.ruleLineStrong
    val tint = if (active) colors.tintCream else colors.surfaceCard
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideContentColor(if (active) colors.inkPrimary else colors.inkSecondary) {
            Text(
                label.uppercase(),
                style = AppTheme.typography.mono.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
    }
}

/**
 * Tempo popup: two BPM input fields (min, max). Empty field = open-ended on that side.
 * "Apply" parses + clamps to [40, 240] and emits the range; "Clear" emits null.
 */
@Composable
private fun TempoFilterPopup(
    current: ClosedFloatingPointRange<Double>?,
    onApply: (ClosedFloatingPointRange<Double>?) -> Unit,
    onClear: () -> Unit,
) {
    val colors = AppTheme.colors
    var minText by remember { mutableStateOf(current?.start?.let { formatBpm(it) } ?: "") }
    var maxText by remember { mutableStateOf(current?.endInclusive?.let { formatBpm(it) } ?: "") }

    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 280.dp)
            .clip(RoundedCornerShape(AppTheme.spacing.cornerCard))
            .background(colors.surfaceCard)
            .border(1.dp, colors.ruleLineStrong, RoundedCornerShape(AppTheme.spacing.cornerCard))
            .padding(AppTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(colors.inkSecondary) {
            Text("Tempo (BPM)", style = AppTheme.typography.body)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TextField(
                    value = minText,
                    onChange = { minText = it },
                    placeholder = "min",
                )
            }
            ProvideContentColor(colors.inkMuted) { Text("–", style = AppTheme.typography.body) }
            Box(modifier = Modifier.weight(1f)) {
                TextField(
                    value = maxText,
                    onChange = { maxText = it },
                    placeholder = "max",
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, colors.ruleLineStrong, RoundedCornerShape(50))
                    .clickable(onClick = onClear)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                ProvideContentColor(colors.inkSecondary) {
                    Text("Clear", style = AppTheme.typography.body)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(colors.tintCream)
                    .border(1.dp, colors.accentSecondary, RoundedCornerShape(50))
                    .clickable {
                        val range = parseTempoRange(minText, maxText)
                        onApply(range)
                    }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                ProvideContentColor(colors.inkPrimary) {
                    Text("Apply", style = AppTheme.typography.body)
                }
            }
        }
    }
}

/**
 * Parse min/max strings into a clamped range. Empty fields become open-ended (40 / 240).
 * Both empty → null (no filter). Invalid input falls through to null on its side.
 */
internal fun parseTempoRange(minText: String, maxText: String): ClosedFloatingPointRange<Double>? {
    val rawMin = minText.trim().toDoubleOrNull()
    val rawMax = maxText.trim().toDoubleOrNull()
    if (rawMin == null && rawMax == null) return null
    val lo = (rawMin ?: 40.0).coerceIn(40.0, 240.0)
    val hi = (rawMax ?: 240.0).coerceIn(40.0, 240.0)
    return if (lo <= hi) lo..hi else hi..lo
}

private fun formatBpm(value: Double): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

/**
 * Key popup: scrolling list of distinct keys + an "Any" entry that clears the filter.
 * Keys come from the catalog's `selectDistinctKeys` query so the producer only sees keys their
 * library actually contains.
 */
@Composable
private fun KeyFilterPopup(
    keys: List<String>,
    current: String?,
    onPick: (String?) -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(AppTheme.spacing.cornerCard))
            .background(colors.surfaceCard)
            .border(1.dp, colors.ruleLineStrong, RoundedCornerShape(AppTheme.spacing.cornerCard)),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            KeyRow(label = "Any", selected = current == null) { onPick(null) }
            for (k in keys) {
                KeyRow(label = k, selected = k == current) { onPick(k) }
            }
            if (keys.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(AppTheme.spacing.md),
                ) {
                    ProvideContentColor(colors.inkMuted) {
                        Text("No keys parsed yet.", style = AppTheme.typography.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.tintCream else colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.spacing.md, vertical = 8.dp),
    ) {
        ProvideContentColor(if (selected) colors.inkPrimary else colors.inkSecondary) {
            Text(label, style = AppTheme.typography.body)
        }
    }
}

private fun ProjectGroup.toSongStripData(sync: ProjectSyncState?): SongStripData {
    val r = representative
    return SongStripData(
        id = r.id.value,
        name = r.name,
        parentDir = id,
        tempo = r.tempo,
        timeSigNum = null,
        timeSigDen = null,
        trackCount = r.trackCount.takeIf { it > 0 },
        lengthSeconds = null,
        effortScore = effortScore,
        lastModifiedRelative = relativeFromMs(updatedAtMs),
        colorTag = r.colorTag,
        tags = r.tags,
        warning = if (missingSampleCount > 0) "$missingSampleCount missing sample${if (missingSampleCount == 1) "" else "s"}" else null,
        sync = sync?.toBadge(),
        variantCount = variantCount,
        // Effective stage: override wins over the inferred classification — same rule the
        // toolbar filter uses, so chip rendering and filter membership always agree.
        stage = r.effectiveStage?.toChip(),
    )
}

private fun Stage.toChip(): SongStageChip = SongStageChip(
    label = label,
    tone = when (this) {
        Stage.Sketch -> SongStageTone.Sketch
        Stage.InProgress -> SongStageTone.InProgress
        Stage.Mixing -> SongStageTone.Mixing
        Stage.Done -> SongStageTone.Done
        Stage.Stuck -> SongStageTone.Stuck
    },
)

internal fun ProjectGroup.toSongStripDataForTest(sync: ProjectSyncState?): SongStripData =
    toSongStripData(sync)

private fun ProjectSyncState.toBadge(): SongSyncBadge = when (this) {
    ProjectSyncState.Synced -> SongSyncBadge.Synced
    ProjectSyncState.Pending -> SongSyncBadge.Pending
    ProjectSyncState.Uploading -> SongSyncBadge.Uploading
    ProjectSyncState.Conflict -> SongSyncBadge.Conflict
    // Remote ahead is "we owe a pull" — surface it as Pending in the song-strip badge so the
    // user reads "this row needs sync attention" without introducing a new badge variant.
    ProjectSyncState.RemoteAhead -> SongSyncBadge.Pending
    ProjectSyncState.LocalOnly -> SongSyncBadge.LocalOnly
    ProjectSyncState.Unknown -> SongSyncBadge.Unknown
}

private fun relativeFromMs(ms: Long): String {
    val deltaMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - ms
    val days = deltaMs / (24L * 60 * 60 * 1000)
    return when {
        days < 1 -> "today"
        days < 2 -> "yesterday"
        days < 30 -> "${days}d ago"
        days < 365 -> "${days / 30}mo ago"
        else -> "${days / 365}y ago"
    }
}
