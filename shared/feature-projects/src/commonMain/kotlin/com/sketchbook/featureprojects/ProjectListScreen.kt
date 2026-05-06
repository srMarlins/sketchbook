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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.HighlightChip
import com.sketchbook.uishared.components.HighlightsStrip
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.ScanIndicator
import com.sketchbook.uishared.components.ShelfHeader
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
    holder: ProjectListStateHolder,
    modifier: Modifier = Modifier,
    scanLabel: String? = null,
    scanActive: Boolean = false,
    syncStateFor: ((ProjectId) -> ProjectSyncState)? = null,
    detailPanel: (@Composable (ProjectId, () -> Unit) -> Unit)? = null,
) {
    val state by holder.state.collectAsState()
    val dispatch = holder::dispatch
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
    val onOpenDetail: (ProjectId) -> Unit = remember(holder) { { id -> dispatch(ProjectListStateHolder.Intent.OpenDetail(id)) } }
    val onZoomShelf: (ShelfId?) -> Unit = remember(holder) { { shelf -> dispatch(ProjectListStateHolder.Intent.ZoomShelf(shelf)) } }
    val onShuffleGems: () -> Unit = remember(holder) { { dispatch(ProjectListStateHolder.Intent.ShuffleGems) } }
    val onCloseDetail: () -> Unit = remember(holder) { { dispatch(ProjectListStateHolder.Intent.CloseDetail) } }
    val onClearSearch: () -> Unit = remember(holder) { { dispatch(ProjectListStateHolder.Intent.Search("")) } }

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
                        onChange = { dispatch(ProjectListStateHolder.Intent.Search(it)) },
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
    state: ProjectListStateHolder.State,
    dispatch: (ProjectListStateHolder.Intent) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val key = event.key
    return when {
        key == Key.Escape && state.query.isNotBlank() -> {
            dispatch(ProjectListStateHolder.Intent.Search(""))
            true
        }
        state.query.isNotBlank() && state.searchResults.isNotEmpty() && key == Key.DirectionDown -> {
            dispatch(ProjectListStateHolder.Intent.NavigateSearchNext)
            true
        }
        state.query.isNotBlank() && state.searchResults.isNotEmpty() && key == Key.DirectionUp -> {
            dispatch(ProjectListStateHolder.Intent.NavigateSearchPrev)
            true
        }
        state.query.isNotBlank() && state.searchResults.isNotEmpty() && (key == Key.Enter || key == Key.NumPadEnter) -> {
            dispatch(ProjectListStateHolder.Intent.OpenSelectedSearch)
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
    )
}

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
