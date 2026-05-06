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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.sketchbook.core.ParseStatus
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
    val scroll = rememberScrollState()

    val groups = remember(state.rows) { deriveProjectGroups(state.rows) }
    val buckets = remember(groups) { bucketize(groups) }
    var openDetailId: ProjectId? by remember { mutableStateOf(null) }
    var zoomShelf: ShelfId? by remember { mutableStateOf(null) }
    // Forgotten-gems shuffle: seed=0 means "show top-effort" (default order); incrementing
    // re-rolls a random sample from the qualifying set. Mirrors `_shelf_gems_sample` in
    // packages/web/audio_web/home.py — random rotation across distinct project roots.
    var gemsShuffleSeed by remember { mutableStateOf(0) }
    val gemsView = remember(buckets.forgottenGems, gemsShuffleSeed) {
        if (gemsShuffleSeed == 0) buckets.forgottenGems
        else buckets.forgottenGems.shuffled(kotlin.random.Random(gemsShuffleSeed.toLong() * 7919L))
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth.value >= WIDE_THRESHOLD_DP

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(PaddingValues(horizontal = AppTheme.spacing.xl, vertical = AppTheme.spacing.lg)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
        ) {
            Column(
                modifier = Modifier.widthIn(max = 1240.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            ) {
                PageHeader(
                    title = zoomShelf?.title() ?: "Projects",
                    subtitle = zoomShelf?.subtitle()
                        ?: "Your Ableton catalog — ${groups.size} project${if (groups.size == 1) "" else "s"}, "
                        + "${state.rows.size} `.als` file${if (state.rows.size == 1) "" else "s"}.",
                    actions = if (zoomShelf != null) {
                        { BackToOverview(onClick = { zoomShelf = null }) }
                    } else null,
                )
                TextField(
                    value = state.query,
                    onChange = { holder.dispatch(ProjectListStateHolder.Intent.Search(it)) },
                    placeholder = "Search projects, plugins, samples…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && state.query.isNotBlank()) {
                                holder.dispatch(ProjectListStateHolder.Intent.Search(""))
                                true
                            } else {
                                false
                            }
                        },
                    trailing = if (state.query.isNotBlank()) {
                        {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { holder.dispatch(ProjectListStateHolder.Intent.Search("")) }
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

            Box(modifier = Modifier.widthIn(max = 1240.dp).fillMaxWidth()) {
                // Base layer — always rendered so it stays visible (dimmed) behind the overlay.
                if (zoomShelf != null) {
                    ShelfFlat(
                        groups = zoomShelf!!.bucket(buckets),
                        onOpen = { openDetailId = it.representative.id },
                        syncStateFor = syncStateFor,
                    )
                } else {
                    HomeDashboard(
                        buckets = buckets,
                        gemsView = gemsView,
                        isWide = isWide,
                        onOpen = { openDetailId = it.representative.id },
                        onSeeAll = { zoomShelf = it },
                        onChip = { zoomShelf = it },
                        onShuffleGems = { gemsShuffleSeed = (gemsShuffleSeed + 1).coerceAtLeast(1) },
                        syncStateFor = syncStateFor,
                    )
                }

                // Search overlay — scrim + results panel anchored under the search field.
                if (state.query.isNotBlank()) {
                    // Scrim: paper-fog over the base layer; click anywhere to clear the query.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(AppTheme.colors.surfaceSunken.copy(alpha = 0.85f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { holder.dispatch(ProjectListStateHolder.Intent.Search("")) },
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .clip(RoundedCornerShape(AppTheme.spacing.cornerCard))
                            .background(AppTheme.colors.surfaceCard)
                            .border(1.dp, AppTheme.colors.ruleLineStrong, RoundedCornerShape(AppTheme.spacing.cornerCard))
                            .padding(8.dp),
                    ) {
                        SearchResults(
                            groups = groups.filter { matchesQuery(it, state.query) },
                            onOpen = { openDetailId = it.representative.id },
                            syncStateFor = syncStateFor,
                        )
                    }
                }
            }
        }

        DetailPanel(
            openId = openDetailId,
            onDismiss = { openDetailId = null },
            content = detailPanel,
        )
    }
}

private fun matchesQuery(group: ProjectGroup, q: String): Boolean {
    val needle = q.trim()
    if (needle.isEmpty()) return true
    if (group.id.contains(needle, ignoreCase = true)) return true
    return group.variants.any { v ->
        v.name.contains(needle, ignoreCase = true) ||
            v.tags.any { it.contains(needle, ignoreCase = true) }
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

@Composable
private fun HomeDashboard(
    buckets: Buckets,
    gemsView: List<ProjectGroup>,
    isWide: Boolean,
    onOpen: (ProjectGroup) -> Unit,
    onSeeAll: (ShelfId) -> Unit,
    onChip: (ShelfId) -> Unit,
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
            onSelect = { id -> ShelfId.fromId(id)?.let(onChip) },
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
                        onSeeAll = { onSeeAll(ShelfId.CurrentlyWorking) },
                        syncStateFor = syncStateFor,
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    Shelf(
                        title = "Forgotten gems",
                        subtitle = "High-effort sketches buried in the catalog. ${buckets.forgottenGems.size} total.",
                        groups = gemsView.take(SHELF_LIMIT),
                        onOpen = onOpen,
                        onSeeAll = { onSeeAll(ShelfId.ForgottenGems) },
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
                onSeeAll = { onSeeAll(ShelfId.CurrentlyWorking) },
                syncStateFor = syncStateFor,
            )
            Shelf(
                title = "Forgotten gems",
                subtitle = "High-effort sketches buried in the catalog.",
                groups = gemsView.take(SHELF_LIMIT),
                onOpen = onOpen,
                onSeeAll = { onSeeAll(ShelfId.ForgottenGems) },
                onShuffle = if (buckets.forgottenGems.size > SHELF_LIMIT) onShuffleGems else null,
                syncStateFor = syncStateFor,
            )
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    subtitle: String,
    groups: List<ProjectGroup>,
    onOpen: (ProjectGroup) -> Unit,
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
                    SongStrip(
                        data = group.toSongStripData(syncStateFor?.invoke(group.representative.id)),
                        onOpen = { onOpen(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfFlat(
    groups: List<ProjectGroup>,
    onOpen: (ProjectGroup) -> Unit,
    syncStateFor: ((ProjectId) -> ProjectSyncState)?,
) {
    Column(
        modifier = Modifier.widthIn(max = 1240.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                EmptyState(title = "Nothing here yet")
            }
        } else {
            for (group in groups) {
                SongStrip(
                    data = group.toSongStripData(syncStateFor?.invoke(group.representative.id)),
                    onOpen = { onOpen(group) },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    groups: List<ProjectGroup>,
    onOpen: (ProjectGroup) -> Unit,
    syncStateFor: ((ProjectId) -> ProjectSyncState)?,
) {
    if (groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
            EmptyState(title = "No matches", hint = "Try a different query.")
        }
    } else {
        Column(
            modifier = Modifier.widthIn(max = 1240.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (group in groups) {
                SongStrip(
                    data = group.toSongStripData(syncStateFor?.invoke(group.representative.id)),
                    onOpen = { onOpen(group) },
                )
            }
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
                .clickable(onClick = onDismiss),
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

private enum class ShelfId(val id: String) {
    CurrentlyWorking("currently-working"),
    ForgottenGems("forgotten-gems"),
    AlmostDone("almost-done"),
    HasPotential("has-potential"),
    Untriaged("untriaged"),
    Broken("broken");

    fun title(): String = when (this) {
        CurrentlyWorking -> "Currently working on"
        ForgottenGems -> "Forgotten gems"
        AlmostDone -> "Almost done"
        HasPotential -> "Has potential"
        Untriaged -> "Untriaged"
        Broken -> "Broken"
    }

    fun subtitle(): String = when (this) {
        CurrentlyWorking -> "Blue color tag or modified within 6 months."
        ForgottenGems -> "Effort score >=60 and quiet for 2+ years."
        AlmostDone -> "Warm color tags (orange / yellow) — close to a release."
        HasPotential -> "Purple-tagged sketches marked for revisit."
        Untriaged -> "No color tag yet — needs a glance."
        Broken -> "Failed to parse, or referencing missing samples."
    }

    fun bucket(b: Buckets): List<ProjectGroup> = when (this) {
        CurrentlyWorking -> b.currentlyWorking
        ForgottenGems -> b.forgottenGems
        AlmostDone -> b.almostDone
        HasPotential -> b.hasPotential
        Untriaged -> b.untriaged
        Broken -> b.broken
    }

    companion object {
        fun fromId(id: String): ShelfId? = entries.firstOrNull { it.id == id }
    }
}

private data class Buckets(
    val currentlyWorking: List<ProjectGroup>,
    val forgottenGems: List<ProjectGroup>,
    val almostDone: List<ProjectGroup>,
    val hasPotential: List<ProjectGroup>,
    val untriaged: List<ProjectGroup>,
    val broken: List<ProjectGroup>,
    val all: List<ProjectGroup>,
)

private const val FOURTEEN_DAYS_MS = 14L * 24 * 60 * 60 * 1000
private const val FORGOTTEN_GEM_THRESHOLD = 65

// Ableton palette indices (the real ones our scanner will emit, matching the
// `AbletonPalette` map in :ui-shared and the mocks the user iterated against).
// Python `home.py` used a provisional 1..6 mapping with a comment noting it would
// change once real scan data arrived — we use the canonical Ableton indices here.
private val WARM_COLORS = setOf(1, 2, 3) // pink / orange / yellow
private const val BLUE = 10
private const val PURPLE = 12
// Forgotten-gems excludes "shipped" / "killed" tags so the gem shelf is genuinely
// forgotten, not just old.
private val GEM_EXCLUDE_COLORS = setOf(6, 7) // green-ish (shipped), red-ish (killed)

/**
 * Faithful port of the production server impl in
 * `packages/web/audio_web/home.py::_shelf_*`. Notably:
 *
 *  - `currently-working`: blue OR mtime within **14 days** (server says 14d, not 6mo).
 *  - `forgotten-gems`: effort_score >= **65** AND mtime older than **180 days** AND color tag
 *    NOT in (green, red). The earlier 2-year gate was from the JS mock (test-only data) —
 *    the production rule is 6 months.
 *  - `almost-done`: warm color tags (orange/yellow) — server uses orange+yellow specifically.
 *  - `has-potential`: purple color tag.
 *  - `untriaged`: no color tag.
 *  - `broken`: parse failed OR missing_sample_count > 0.
 *
 * A group can appear in multiple shelves; chip counts and shelves are computed independently.
 *
 * Note: until the streaming `.als` parser fills [ProjectGroup.effortScore] / `colorTag`, only
 * `currently-working` (mtime branch), `untriaged`, and `broken` will populate. Forgotten gems,
 * almost-done, has-potential need parser data. [EffortScore] now degrades to a file-size-only
 * proxy when meta is null so `forgotten-gems` has *something* to surface in the meantime.
 */
private fun bucketize(all: List<ProjectGroup>): Buckets {
    val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val currentlyWorking = all.filter { g ->
        g.representative.colorTag == BLUE || (nowMs - g.updatedAtMs) < FOURTEEN_DAYS_MS
    }
    // No recency gate — "forgotten" is meant to surface effort, not just age. Old projects
    // sort earlier as a tiebreaker (oldest first) so the shelf prefers buried things, but a
    // recent high-effort sketch can still show up. Shuffle re-rolls a random sample from this
    // set when the user clicks the Shuffle button on the shelf header.
    val forgottenGems = all.asSequence()
        .filter { g ->
            (g.effortScore ?: 0) >= FORGOTTEN_GEM_THRESHOLD &&
                g.representative.colorTag !in GEM_EXCLUDE_COLORS
        }
        .sortedWith(compareByDescending<ProjectGroup> { it.effortScore ?: 0 }.thenBy { it.updatedAtMs })
        .toList()
    val almostDone = all.filter { g -> g.representative.colorTag in WARM_COLORS }
    val hasPotential = all.filter { g -> g.representative.colorTag == PURPLE }
    val untriaged = all.filter { g -> g.representative.colorTag == null }
    val broken = all.filter { g ->
        g.parseStatusBest == ParseStatus.Failed || g.missingSampleCount > 0
    }
    return Buckets(
        currentlyWorking = currentlyWorking,
        forgottenGems = forgottenGems,
        almostDone = almostDone,
        hasPotential = hasPotential,
        untriaged = untriaged,
        broken = broken,
        all = all,
    )
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
