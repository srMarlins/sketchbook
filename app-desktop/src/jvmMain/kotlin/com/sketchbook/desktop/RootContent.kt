package com.sketchbook.desktop

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.sketchbook.featuredetail.ProjectDetailScreen
import com.sketchbook.featuredetail.ProjectDetailStateHolder
import com.sketchbook.featurejournal.JournalScreen
import com.sketchbook.featurejournal.JournalStateHolder
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import java.io.File
import javax.swing.JFileChooser
import com.sketchbook.featureneedsattention.NeedsAttentionScreen
import com.sketchbook.featureneedsattention.NeedsAttentionStateHolder
import com.sketchbook.featureprojects.ProjectListScreen
import com.sketchbook.featureprojects.ProjectListStateHolder
import com.sketchbook.featureproposals.ProposalsScreen
import com.sketchbook.featureproposals.ProposalsStateHolder
import com.sketchbook.featuresettings.SettingsScreen
import com.sketchbook.featuresettings.SettingsStateHolder
import com.sketchbook.featuretimeline.TimelineScreen
import com.sketchbook.featuretimeline.TimelineStateHolder
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.repo.SyncQueueState
import com.sketchbook.desktop.repo.SwappableSyncQueue
import com.sketchbook.uishared.components.ActivityBar
import com.sketchbook.uishared.components.ActivityState
import com.sketchbook.uishared.components.InkLoading
import com.sketchbook.uishared.components.NotebookSidebar
import com.sketchbook.uishared.components.PaperPage
import com.sketchbook.uishared.components.SidebarItem
import kotlinx.coroutines.launch

/**
 * Root composable: notebook-style sidebar of top-level destinations + a `NavDisplay` body.
 *
 * Compose Navigation 3 owns the back stack. We pass it in from [Main] so the app menu can
 * reset/push too. Per-screen `StateHolder`s are remembered against the graph's app scope so
 * navigating away and back keeps `stateIn` caches warm.
 *
 * Sidebar siblings have no positional axis between them, so swapping top-level destinations
 * uses Material's "fade-through" — quick fade-out + paired fade-in with a tiny inward scale
 * on the entering screen. The previous slide-X transition implied a left/right relationship
 * between siblings that didn't exist. Hierarchical push/pop (Projects → Detail → Timeline)
 * keeps a slide accent; the back-stack's depth tells us which is happening.
 */
@Composable
fun RootContent(graph: DesktopAppGraph, backStack: NavBackStack<NavKey>) {
    val projectListHolder = remember {
        ProjectListStateHolder(graph.projectRepository, graph.appScope)
    }
    val projectDetailHolder = remember {
        ProjectDetailStateHolder(
            projects = graph.projectRepository,
            snapshots = graph.snapshotRepository,
            scope = graph.appScope,
            locks = graph.lockRepository,
        )
    }
    val timelineHolder = remember {
        TimelineStateHolder(graph.snapshotRepository, graph.appScope)
    }
    val proposalsHolder = remember {
        ProposalsStateHolder(
            repository = graph.proposalsRepository,
            scope = graph.appScope,
            executor = graph.proposalActionExecutor,
        )
    }
    val needsAttentionHolder = remember {
        NeedsAttentionStateHolder(graph.repairRepository, graph.appScope)
    }
    val settingsHolder = remember {
        SettingsStateHolder(graph.settingsRepository, graph.appScope)
    }
    val journalHolder = remember {
        JournalStateHolder(graph.journalRepository, graph.appScope)
    }
    val scanner = graph.scanner
    val sampleScanner = graph.sampleScanner
    // The catalog scanner is a cold Flow; we derive a single-state holder here so the UI can
    // bind to "current scan progress" without re-subscribing per recomposition. Scan kicks off
    // when a library root appears in settings; see the LaunchedEffect below.
    val scanProgressState = remember { kotlinx.coroutines.flow.MutableStateFlow<ScanUiState>(ScanUiState.Idle) }
    val scanProgress by scanProgressState.collectAsState()
    val syncQueue = graph.syncQueue
    val syncState by syncQueue.observe().collectAsState(initial = SyncQueueState())
    // Concrete handle for desktop-only helpers (`pushNowById`, `snapshotFor`). The public
    // `SyncQueue` interface stays narrow; the swappable façade exposes per-row wiring.
    val syncImpl = syncQueue as? SwappableSyncQueue
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Journal row taps navigate to project detail. Push onto the stack so the back gesture
    // returns to the journal list.
    LaunchedEffect(journalHolder) {
        journalHolder.effects.collect { effect ->
            when (effect) {
                is JournalStateHolder.Effect.NavigateToProject ->
                    backStack.add(Screen.ProjectDetail(effect.projectId))
            }
        }
    }
    // Project list now opens detail in a side-panel locally — no nav-stack push. The
    // Navigate effect is left intact in the state holder for future shared-detail callers
    // (deep-links, MCP tool invocations) but the desktop dashboard ignores it.
    LaunchedEffect(projectDetailHolder) {
        projectDetailHolder.effects.collect { effect ->
            when (effect) {
                is ProjectDetailStateHolder.Effect.LaunchLive -> Os.openInLive(effect.projectPath)
                ProjectDetailStateHolder.Effect.LockTaken,
                is ProjectDetailStateHolder.Effect.LockTakeFailed,
                -> Unit
            }
        }
    }
    // Kick a scan whenever a library root is added (or the app starts with roots already in
    // settings). Scan progress is exposed to the sidebar via [scanner.progress].
    LaunchedEffect(settingsHolder) {
        // Track which roots we've already scanned so settings re-emissions (cache toggle, etc.)
        // don't spam re-scans. The scanner is idempotent (INSERT OR REPLACE) but a re-scan over
        // 1900+ files is wasteful.
        val scannedProjects = mutableSetOf<String>()
        val scannedSamples = mutableSetOf<String>()
        settingsHolder.state.collect { settingsState ->
            // Project scan first (loud, time-bounded, drives the visible scan ribbon).
            for (root in settingsState.libraryRoots) {
                if (root is LibraryRoot.Projects && root.path !in scannedProjects) {
                    scannedProjects += root.path
                    val userSampleRoots = settingsState.libraryRoots
                        .filterIsInstance<LibraryRoot.UserSamples>()
                        .map { it.path }
                    graph.appScope.launch {
                        runScan(scanner, root.path, scanProgressState)
                        // After the project scan finishes, walk every UserSamples root into the
                        // `samples` corpus. This runs in the background — no progress UI for
                        // v1 because the walk is fast (no parsing) and surfacing it would just
                        // be noise after the louder project-scan ribbon.
                        for (sampleRoot in userSampleRoots) {
                            if (scannedSamples.add(sampleRoot)) {
                                runCatching { sampleScanner.scan(sampleRoot) }
                            }
                        }
                    }
                    break // one project root at a time at v1
                }
            }
            // If the user has no project roots configured but does have sample roots (or the
            // project scan already kicked off on a previous emission), still pick up newly-added
            // sample roots so the corpus stays fresh.
            for (root in settingsState.libraryRoots) {
                if (root is LibraryRoot.UserSamples && root.path !in scannedSamples) {
                    scannedSamples += root.path
                    graph.appScope.launch {
                        runCatching { sampleScanner.scan(root.path) }
                    }
                }
            }
        }
    }
    // (Sync-state seeding lives on the queue itself — see InMemorySyncQueue.start; the graph
    // hands it the project repository at construction. The UI just observes; it never feeds.)

    val current = backStack.lastOrNull() ?: Screen.Projects
    val proposalsState by proposalsHolder.state.collectAsState()
    val needsAttentionState by needsAttentionHolder.state.collectAsState()
    // Sidebar badges count *unresolved* items only — pending proposals + open findings — so the
    // user sees "stuff that wants me" rather than a total that never goes down. mac-import +
    // missing-sample collapse to a single integer because the screen merges them on arrival.
    val proposalCount = proposalsState.pending.size
    val attentionCount = needsAttentionState.macImports.size + needsAttentionState.missingSamplesTotal
    val items = sidebarItems(current, proposalCount, attentionCount)
    // Sidebar caption priority: scan first (most acute, time-bounded), then sync (slower
    // background activity), then offline marker, else nothing.
    val scanCaption = when (val p = scanProgress) {
        ScanUiState.Idle -> null
        is ScanUiState.Scanning -> "Scanning… ${p.done}/${p.total}"
        is ScanUiState.Done -> "Indexed ${p.indexed} projects" + if (p.failed > 0) " (${p.failed} failed)" else ""
        is ScanUiState.Failed -> "Scan failed: ${p.message}"
    }
    val syncCaption: String? = when {
        !syncState.online -> "Cloud: offline"
        syncState.uploading > 0 -> "Cloud: uploading ${syncState.uploading}"
        syncState.pending > 0 -> "Cloud: ${syncState.pending} pending"
        else -> null
    }
    val statusText = scanCaption ?: syncCaption
    // Drive the animated indicator on both Scanning *and* Done so the ribbon doesn't disappear
    // the instant the walk finishes — Done lingers ~3.5s before the scanner flips to Idle.
    val scanIndicatorLabel = when (val p = scanProgress) {
        is ScanUiState.Scanning -> "Parsing your library — ${p.done}/${p.total} projects"
        is ScanUiState.Done -> "Indexed ${p.indexed} projects" + if (p.failed > 0) " · ${p.failed} failed" else ""
        else -> null
    }
    // Show the inline ribbon during both Scanning and Done so the success state has time to
    // register; idle hides it. The persistent ActivityBar at the top of the pane covers the
    // "always-on" affordance.
    val scanIndicatorActive = scanProgress is ScanUiState.Scanning || scanProgress is ScanUiState.Done
    val activityState = when {
        // Scan wins precedence — it's the louder, time-bounded activity. Sync activity is shown
        // only when something is actively uploading; a non-empty pending queue with no upload in
        // flight reads as "stuck loading" — the sidebar caption already exposes the queue depth.
        scanProgress is ScanUiState.Scanning -> ActivityState.Scanning
        syncState.uploading > 0 -> ActivityState.Syncing
        else -> ActivityState.Idle
    }

    PaperPage {
        Row(modifier = Modifier.fillMaxSize()) {
            NotebookSidebar(
                title = "Sketchbook",
                items = items,
                onSelect = { item ->
                    val target = screenForId(item.id)
                    // Sidebar→Projects is the user's "go home" gesture. The list holder retains
                    // the last query across navigations, so without this reset the dashboard
                    // never reappears once a search has been typed — the screen keeps rendering
                    // SearchResults because state.query is still non-blank.
                    if (target == Screen.Projects) {
                        projectListHolder.dispatch(ProjectListStateHolder.Intent.Search(""))
                    }
                    backStack.clear()
                    backStack.add(target)
                },
                statusText = statusText,
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
                    ActivityBar(state = activityState, modifier = Modifier.fillMaxWidth())
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    transitionSpec = {
                        // Material fade-through + tiny scale-in. No positional slide because
                        // sidebar siblings aren't on a left/right axis.
                        val ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
                        val fadeOutSpec = tween<Float>(durationMillis = 90, easing = ease)
                        val fadeInSpec = tween<Float>(durationMillis = 220, delayMillis = 60, easing = ease)
                        val scaleSpec = tween<Float>(durationMillis = 220, delayMillis = 60, easing = ease)
                        (fadeIn(fadeInSpec) + scaleIn(scaleSpec, initialScale = 0.985f)) togetherWith
                            (fadeOut(fadeOutSpec) + scaleOut(scaleSpec, targetScale = 1.005f))
                    },
                    popTransitionSpec = {
                        val ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
                        val fadeOutSpec = tween<Float>(durationMillis = 90, easing = ease)
                        val fadeInSpec = tween<Float>(durationMillis = 220, delayMillis = 60, easing = ease)
                        val scaleSpec = tween<Float>(durationMillis = 220, delayMillis = 60, easing = ease)
                        (fadeIn(fadeInSpec) + scaleIn(scaleSpec, initialScale = 1.015f)) togetherWith
                            (fadeOut(fadeOutSpec) + scaleOut(scaleSpec, targetScale = 0.99f))
                    },
                    entryProvider = { key ->
                        NavEntry(key) { current ->
                            when (current) {
                                Screen.Projects -> ProjectListScreen(
                                    holder = projectListHolder,
                                    scanLabel = scanIndicatorLabel,
                                    scanActive = scanIndicatorActive,
                                    syncStateFor = syncImpl?.let { impl -> { id -> impl.snapshotFor(id) } },
                                    detailPanel = { id, dismiss ->
                                        LaunchedEffect(id) { projectDetailHolder.load(id) }
                                        DetailPanelContent(
                                            holder = projectDetailHolder,
                                            onDismiss = dismiss,
                                            syncStateFor = syncImpl?.let { impl -> { pid -> impl.snapshotFor(pid) } },
                                            onPushNow = syncImpl?.let { impl -> { pid ->
                                                coroutineScope.launch {
                                                    impl.pushNowById(pid)
                                                }
                                            } },
                                            conflictMessageFor = syncImpl?.let { impl -> { pid -> impl.conflictMessage(pid) } },
                                            onOpenTimeline = { pid ->
                                                val uuid = graph.syncStateStore.identityFor(pid)
                                                backStack.add(Screen.Timeline(uuid))
                                            },
                                        )
                                    },
                                )
                                is Screen.ProjectDetail -> {
                                    LaunchedEffect(current.id) { projectDetailHolder.load(current.id) }
                                    ProjectDetailScreen(projectDetailHolder)
                                }
                                is Screen.Timeline -> {
                                    LaunchedEffect(current.uuid) { timelineHolder.load(current.uuid) }
                                    TimelineScreen(timelineHolder)
                                }
                                Screen.Proposals -> ProposalsScreen(proposalsHolder)
                                Screen.NeedsAttention -> NeedsAttentionScreen(needsAttentionHolder)
                                Screen.Journal -> JournalScreen(journalHolder)
                                Screen.Settings -> SettingsScreen(
                                    holder = settingsHolder,
                                    syncState = syncState,
                                    onAddRootClicked = {
                                        Os.pickDirectory(title = "Add library root")?.let { path ->
                                            settingsHolder.dispatch(
                                                SettingsStateHolder.Intent.AddRoot(LibraryRoot.Projects(path)),
                                            )
                                        }
                                    },
                                    onUploadCredentialClicked = {
                                        Os.pickFile(title = "Service account JSON")?.let { path ->
                                            val json = runCatching { java.io.File(path).readText() }.getOrNull()
                                            settingsHolder.dispatch(
                                                SettingsStateHolder.Intent.SetCloudCredential(json),
                                            )
                                        }
                                    },
                                )
                                else -> Unit // unknown NavKey types are ignored
                            }
                        }
                    },
                )
                    }
                }
            }
        }
    }
}

private fun sidebarItems(
    current: NavKey,
    proposalCount: Int,
    attentionCount: Int,
): List<SidebarItem> = listOf(
    SidebarItem(
        id = "projects",
        label = "Projects",
        active = current is Screen.Projects || current is Screen.ProjectDetail || current is Screen.Timeline,
    ),
    SidebarItem(
        id = "proposals",
        label = "Proposals",
        active = current == Screen.Proposals,
        badge = countBadge(proposalCount),
    ),
    SidebarItem(
        id = "needs-attention",
        label = "Needs attention",
        active = current == Screen.NeedsAttention,
        badge = countBadge(attentionCount),
    ),
    SidebarItem(
        id = "settings",
        label = "Settings",
        active = current == Screen.Settings,
    ),
    SidebarItem(
        id = "journal",
        label = "Journal",
        active = current == Screen.Journal,
    ),
)

// Cap at 99 so the binding doesn't get pushed off the row by a big "missing samples" count.
private fun countBadge(n: Int): String? = when {
    n <= 0 -> null
    n > 99 -> "99+"
    else -> n.toString()
}

private fun screenForId(id: String): Screen = when (id) {
    "projects" -> Screen.Projects
    "proposals" -> Screen.Proposals
    "needs-attention" -> Screen.NeedsAttention
    "settings" -> Screen.Settings
    "journal" -> Screen.Journal
    else -> Screen.Projects
}

/**
 * Detail panel: header band + tab strip + scrollable body, mirroring `web/CorkboardPanel`.
 *
 * Tabs:
 *  - **Overview** — path/folder/modified/tempo/tracks/Live version/tags + primary actions.
 *  - **Versions** — unified timeline merging the project's *local variants* (sibling `.als`
 *    files in the project root) with *remote snapshots* (from `SnapshotRepository.history`).
 *    Each entry carries an origin badge (LOCAL / LOCAL ALT / REMOTE) so the user can see at
 *    a glance which copies live where.
 *  - **Tracks / Samples / Plugins** — placeholder until the `.als` parser is wired in.
 *  - **History** — the journal entries (move/rename/archive/setTags). Empty until actions wire
 *    through MCP.
 */
@Composable
private fun DetailPanelContent(
    holder: ProjectDetailStateHolder,
    onDismiss: () -> Unit,
    syncStateFor: ((com.sketchbook.core.ProjectId) -> ProjectSyncState)? = null,
    onPushNow: ((com.sketchbook.core.ProjectId) -> Unit)? = null,
    conflictMessageFor: ((com.sketchbook.core.ProjectId) -> String?)? = null,
    onOpenTimeline: ((com.sketchbook.core.ProjectId) -> Unit)? = null,
) {
    val state by holder.state.collectAsState()
    val theme = com.sketchbook.uishared.theme.AppTheme

    var tab by remember { mutableStateOf(DetailTab.Overview) }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxHeight().fillMaxWidth(),
    ) {
        // Header band.
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    val row = state.row
                    if (row != null) {
                        EditableTitle(
                            name = row.name,
                            onCommit = { holder.dispatch(com.sketchbook.featuredetail.ProjectDetailStateHolder.Intent.Rename(it)) },
                            theme = theme,
                        )
                    } else {
                        ProvideContentColor(theme.colors.inkPrimary) {
                            Text(
                                text = if (state.loading) "Loading" else "Project not found",
                                style = theme.typography.title,
                            )
                        }
                    }
                    if (state.loading) InkLoading()
                }
                state.row?.let { row ->
                    ProvideContentColor(theme.colors.inkMuted) {
                        Text(
                            text = com.sketchbook.featureprojects.projectRootDir(row.path.value),
                            style = theme.typography.mono.copy(
                                fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                        )
                    }
                    // Sync pill + Sync-now CTA. Pill always present (even if "—") so the
                    // detail panel has a consistent "where does this live in the cloud" slot.
                    val sync = syncStateFor?.invoke(row.id) ?: ProjectSyncState.Unknown
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        SyncPill(sync, theme)
                        if (onPushNow != null && sync != ProjectSyncState.Synced && sync != ProjectSyncState.Uploading) {
                            com.sketchbook.uishared.components.Button(
                                onClick = { onPushNow(row.id) },
                                variant = com.sketchbook.uishared.components.ButtonVariant.Ghost,
                            ) { Text("Sync now") }
                        }
                    }
                    // Inline conflict hint — small ghost text per memory
                    // feedback_layer_dont_redesign. Only shown when the queue tracks one.
                    val conflictMsg = conflictMessageFor?.invoke(row.id)
                    if (conflictMsg != null && sync == ProjectSyncState.Conflict) {
                        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                        ProvideContentColor(theme.colors.inkMuted) {
                            Text(conflictMsg, style = theme.typography.caption)
                        }
                    }
                }
            }
            state.row?.let { row ->
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    com.sketchbook.uishared.components.Button(
                        onClick = { holder.dispatch(com.sketchbook.featuredetail.ProjectDetailStateHolder.Intent.OpenInLive) },
                        variant = com.sketchbook.uishared.components.ButtonVariant.Primary,
                    ) { Text("Open in Live") }
                    com.sketchbook.uishared.components.Button(
                        onClick = { Os.openInLive(parentDirOf(row.path.value)) },
                        variant = com.sketchbook.uishared.components.ButtonVariant.Secondary,
                    ) { Text("Reveal folder") }
                    Button(
                        onClick = {
                            val current = parentDirOf(row.path.value)
                            openMoveDialog(current) { picked ->
                                holder.dispatch(ProjectDetailStateHolder.Intent.Move(picked))
                            }
                        },
                        variant = ButtonVariant.Secondary,
                    ) { Text("Move…") }
                    Button(
                        onClick = { holder.dispatch(ProjectDetailStateHolder.Intent.ToggleArchive) },
                        variant = ButtonVariant.Secondary,
                    ) { Text(if (row.archived) "Unarchive" else "Archive") }
                }
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                ProvideContentColor(theme.colors.inkMuted) {
                    Text(
                        "✕  CLOSE",
                        style = theme.typography.mono.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                        ),
                    )
                }
            }
        }
        // Tabs strip on a sunken band — like notebook page-tabs.
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.colors.surfaceSunken)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            for (t in DetailTab.entries) {
                DetailTabButton(t, t == tab, onClick = { tab = t }, theme = theme)
            }
        }

        // Body
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val row = state.row
            if (row == null) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxHeight().fillMaxWidth().padding(24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    ProvideContentColor(theme.colors.inkMuted) {
                        Text(
                            text = if (state.loading) "Loading project metadata…"
                                else "We couldn't find this project. It may have been removed since the last scan.",
                            style = theme.typography.body,
                        )
                    }
                }
            } else {
                val scroll = rememberScrollState()
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
                ) {
                    when (tab) {
                        DetailTab.Overview -> {
                            DetailMetaSection(
                                row = row,
                                theme = theme,
                                onTagsChange = { holder.dispatch(com.sketchbook.featuredetail.ProjectDetailStateHolder.Intent.SetTags(it)) },
                            )
                            DetailQuickVersions(row, state, theme)
                        }
                        DetailTab.Versions -> DetailVersionsTab(
                            row = row,
                            state = state,
                            theme = theme,
                            onOpenTimeline = onOpenTimeline?.let { open -> { open(row.id) } },
                        )
                        DetailTab.Tracks -> DetailTracksTab(row, theme)
                        DetailTab.Samples -> DetailSamplesTab(state.samples, theme)
                        DetailTab.Plugins -> DetailPluginsTab(state.plugins, theme)
                        DetailTab.History -> DetailHistoryTab(state, theme)
                    }
                }
            }
        }
    }
}

private enum class DetailTab(val label: String) {
    Overview("Overview"),
    Versions("Versions"),
    Tracks("Tracks"),
    Samples("Samples"),
    Plugins("Plugins"),
    History("History"),
}

@Composable
private fun DetailTabButton(
    tab: DetailTab,
    active: Boolean,
    onClick: () -> Unit,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    val bg = if (active) theme.colors.surfaceCard else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (active) theme.colors.inkPrimary else theme.colors.inkSecondary
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        ProvideContentColor(fg) {
            Text(
                tab.label,
                style = if (active) theme.typography.bodyEmphasis else theme.typography.body,
            )
        }
    }
}


@Composable
private fun DetailMetaSection(
    row: com.sketchbook.core.ProjectRow,
    theme: com.sketchbook.uishared.theme.AppTheme,
    onTagsChange: (List<String>) -> Unit,
) {
    Section("Overview", theme) {
        DetailRow("Project root", com.sketchbook.featureprojects.projectRootDir(row.path.value), theme, mono = true)
        DetailRow("Active variant", java.io.File(row.path.value).name, theme, mono = true)
        DetailRow("Last modified", relativeFromInstant(row.updatedAt), theme)
        row.tempo?.let { DetailRow("Tempo", "${it.toInt()} bpm", theme) }
        if (row.trackCount > 0) DetailRow("Tracks", row.trackCount.toString(), theme)
        row.lastSavedLiveVersion?.let { DetailRow("Last saved with", it, theme) }
        TagsEditorRow(
            tags = row.tags,
            theme = theme,
            onChange = onTagsChange,
        )
    }
}

@Composable
private fun DetailQuickVersions(
    row: com.sketchbook.core.ProjectRow,
    state: ProjectDetailStateHolder.State,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    val unified = remember(row.path.value, state.history) { unifyVersions(row, state.history) }
    val sample = unified.take(4)
    val total = unified.size
    if (total == 0) return
    Section("Versions ($total)", theme) {
        for (entry in sample) {
            VersionRow(entry, isCurrent = entry.absPath == row.path.value, theme = theme)
        }
        if (total > sample.size) {
            ProvideContentColor(theme.colors.inkMuted) {
                Text(
                    "+ ${total - sample.size} more — see Versions tab",
                    style = theme.typography.caption,
                )
            }
        }
    }
}

@Composable
private fun DetailVersionsTab(
    row: com.sketchbook.core.ProjectRow,
    state: ProjectDetailStateHolder.State,
    theme: com.sketchbook.uishared.theme.AppTheme,
    onOpenTimeline: (() -> Unit)? = null,
) {
    val unified = remember(row.path.value, state.history) { unifyVersions(row, state.history) }
    Section("Local + remote", theme) {
        ProvideContentColor(theme.colors.inkMuted) {
            Text(
                "Files in this project's folder, merged with snapshots Sketchbook has uploaded to the cloud. The currently-open variant is highlighted.",
                style = theme.typography.body,
            )
        }
    }
    Section("Versions (${unified.size})", theme) {
        if (unified.isEmpty()) {
            ProvideContentColor(theme.colors.inkMuted) {
                Text("Nothing yet — once you save in Live a few times, Sketchbook starts tracking variants here.", style = theme.typography.body)
            }
        } else {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
            ) {
                for (entry in unified) {
                    VersionRow(entry, isCurrent = entry.absPath == row.path.value, theme = theme)
                }
            }
        }
    }
    if (onOpenTimeline != null) {
        com.sketchbook.uishared.components.Button(
            onClick = onOpenTimeline,
            variant = com.sketchbook.uishared.components.ButtonVariant.Ghost,
        ) {
            Text("View full timeline →", style = theme.typography.bodyEmphasis)
        }
    }
}

@Composable
private fun DetailHistoryTab(
    state: ProjectDetailStateHolder.State,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    Section("Journal", theme) {
        ProvideContentColor(theme.colors.inkMuted) {
            Text(
                "Move/rename/archive/tag actions from Sketchbook itself land here. The .als file's own modification timestamp lives in Versions.",
                style = theme.typography.body,
            )
        }
    }
    if (state.history.isEmpty()) {
        ProvideContentColor(theme.colors.inkMuted) {
            Text("No journal entries yet.", style = theme.typography.body)
        }
    } else {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
            for (snap in state.history) {
                ProvideContentColor(theme.colors.inkSecondary) {
                    Text(
                        text = (snap.label ?: "rev ${snap.rev.value}")
                            + " · ${snap.kind.name.lowercase()}"
                            + " · ${snap.fileCount} files",
                        style = theme.typography.body,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderTab(
    title: String,
    body: String,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    Section(title, theme) {
        ProvideContentColor(theme.colors.inkMuted) {
            Text(body, style = theme.typography.body)
        }
    }
}

@Composable
private fun VersionRow(
    entry: VersionEntry,
    isCurrent: Boolean,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        // Origin badge
        val (badgeBg, badgeFg, badgeLabel) = when (entry.origin) {
            VersionOrigin.LocalActive -> Triple(theme.colors.accentSoft, theme.colors.inkPrimary, "ACTIVE")
            VersionOrigin.LocalAlternate -> Triple(theme.colors.tintBlue, theme.colors.inkPrimary, "LOCAL")
            VersionOrigin.Remote -> Triple(theme.colors.tintSage, theme.colors.inkPrimary, "REMOTE")
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .background(badgeBg, androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            ProvideContentColor(badgeFg) {
                Text(
                    badgeLabel,
                    style = theme.typography.mono.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(9.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                        letterSpacing = androidx.compose.ui.unit.TextUnit(0.6f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                )
            }
        }
        ProvideContentColor(if (isCurrent) theme.colors.accentAction else theme.colors.inkPrimary) {
            Text(
                text = entry.label,
                style = theme.typography.body,
                modifier = Modifier.weight(1f),
            )
        }
        ProvideContentColor(theme.colors.inkMuted) {
            Text(
                text = entry.subtitle,
                style = theme.typography.mono.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
    }
}

private data class VersionEntry(
    val origin: VersionOrigin,
    val label: String,
    val subtitle: String,
    val absPath: String?,
    val timestampMs: Long,
)

private enum class VersionOrigin { LocalActive, LocalAlternate, Remote }

private fun unifyVersions(
    row: com.sketchbook.core.ProjectRow,
    history: List<com.sketchbook.core.Snapshot>,
): List<VersionEntry> {
    val out = mutableListOf<VersionEntry>()
    // Local variants — siblings under the project root.
    val parent = java.io.File(row.path.value).parentFile
    if (parent != null && parent.isDirectory) {
        val siblings = parent.listFiles { f ->
            f.isFile &&
                f.name.endsWith(".als", ignoreCase = true) &&
                !f.name.endsWith(".als.bak", ignoreCase = true) &&
                !f.name.startsWith(".")
        }?.toList() ?: emptyList()
        for (sib in siblings) {
            val abs = sib.absolutePath.replace('\\', '/')
            val isActive = abs == row.path.value
            out += VersionEntry(
                origin = if (isActive) VersionOrigin.LocalActive else VersionOrigin.LocalAlternate,
                label = sib.nameWithoutExtension,
                subtitle = relativeFromMs(sib.lastModified()) + " · " + formatBytes(sib.length()),
                absPath = abs,
                timestampMs = sib.lastModified(),
            )
        }
    }
    // Remote snapshots from the SnapshotRepository.
    for (snap in history) {
        out += VersionEntry(
            origin = VersionOrigin.Remote,
            label = snap.label ?: "rev ${snap.rev.value}",
            subtitle = "${snap.kind.name.lowercase()} · ${snap.fileCount} files · ${snap.hostName}",
            absPath = null,
            timestampMs = runCatching { snap.timestamp.toEpochMilliseconds() }.getOrDefault(0L),
        )
    }
    return out.sortedByDescending { it.timestampMs }
}

private fun relativeFromInstant(instant: kotlin.time.Instant): String =
    relativeFromMs(instant.toEpochMilliseconds())

private fun relativeFromMs(ms: Long): String {
    val deltaMs = System.currentTimeMillis() - ms
    val days = deltaMs / (24L * 60 * 60 * 1000)
    return when {
        days < 1 -> "today"
        days < 2 -> "yesterday"
        days < 30 -> "${days}d ago"
        days < 365 -> "${days / 30}mo ago"
        else -> "${days / 365}y ago"
    }
}

// Project-root detection lives in :shared:feature-projects so the list and the detail panel
// agree. Imported as `com.sketchbook.featureprojects.projectRootDir`.

@Composable
private fun SyncPill(state: ProjectSyncState, theme: com.sketchbook.uishared.theme.AppTheme) {
    val (bg, fg, label) = when (state) {
        ProjectSyncState.Synced -> Triple(theme.colors.tintSage, theme.colors.inkPrimary, "SYNCED")
        ProjectSyncState.Pending -> Triple(theme.colors.tintBlue, theme.colors.inkPrimary, "PENDING")
        ProjectSyncState.Uploading -> Triple(theme.colors.tintBlue, theme.colors.inkPrimary, "UPLOADING…")
        ProjectSyncState.Conflict -> Triple(theme.colors.tintRose, theme.colors.inkPrimary, "CONFLICT")
        ProjectSyncState.RemoteAhead -> Triple(theme.colors.tintBlue, theme.colors.inkPrimary, "REMOTE AHEAD")
        ProjectSyncState.LocalOnly -> Triple(theme.colors.surfaceSunken, theme.colors.inkSecondary, "LOCAL ONLY")
        ProjectSyncState.Unknown -> Triple(theme.colors.surfaceSunken, theme.colors.inkMuted, "—")
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ProvideContentColor(fg) {
            Text(
                label,
                style = theme.typography.mono.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.6f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
    }
}

@Composable
private fun DetailHistorySection(
    state: com.sketchbook.featuredetail.ProjectDetailStateHolder.State,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    if (state.history.isEmpty()) return
    Section("History (${state.history.size})", theme) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
            for (snap in state.history) {
                ProvideContentColor(theme.colors.inkSecondary) {
                    Text(
                        text = (snap.label ?: "rev ${snap.rev.value}") + " · ${snap.kind.name.lowercase()} · ${snap.fileCount} files",
                        style = theme.typography.body,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    theme: com.sketchbook.uishared.theme.AppTheme,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        ProvideContentColor(theme.colors.inkSecondary) {
            Text(
                text = title.uppercase(),
                style = theme.typography.mono.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
        content()
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    theme: com.sketchbook.uishared.theme.AppTheme,
    mono: Boolean = false,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        ProvideContentColor(theme.colors.inkMuted) {
            Text(
                label,
                style = theme.typography.caption,
                modifier = Modifier.width(120.dp),
            )
        }
        ProvideContentColor(theme.colors.inkPrimary) {
            Text(
                value,
                style = if (mono) theme.typography.mono.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                ) else theme.typography.body,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EditableTitle(
    name: String,
    onCommit: (String) -> Unit,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    var editing by remember(name) { mutableStateOf(false) }
    var draft by remember(name) { mutableStateOf(name) }
    val focusRequester = remember { FocusRequester() }

    if (editing) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = theme.typography.title.copy(color = theme.colors.inkPrimary),
            cursorBrush = SolidColor(theme.colors.inkPrimary),
            modifier = Modifier
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    if (!focus.isFocused && editing) {
                        if (draft.trim().isNotEmpty() && draft != name) onCommit(draft)
                        editing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            if (draft.trim().isNotEmpty() && draft != name) onCommit(draft)
                            editing = false
                            true
                        }
                        Key.Escape -> {
                            draft = name
                            editing = false
                            true
                        }
                        else -> false
                    }
                },
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    } else {
        ProvideContentColor(theme.colors.inkPrimary) {
            Text(
                text = name,
                style = theme.typography.title,
                modifier = Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { draft = name; editing = true },
                ),
            )
        }
    }
}

@Composable
private fun TagsEditorRow(
    tags: List<String>,
    theme: com.sketchbook.uishared.theme.AppTheme,
    onChange: (List<String>) -> Unit,
) {
    var addingTag by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        ProvideContentColor(theme.colors.inkMuted) {
            Text(
                "Tags",
                style = theme.typography.caption,
                modifier = Modifier.width(120.dp),
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            for (tag in tags) {
                EditableTagChip(
                    label = tag,
                    onRemove = { onChange(tags.filterNot { it == tag }) },
                    theme = theme,
                )
            }
            if (addingTag) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(theme.colors.tintCream)
                        .border(1.dp, theme.colors.ruleLineStrong, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .widthIn(min = 60.dp),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = theme.typography.mono.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                            color = theme.colors.inkSecondary,
                        ),
                        cursorBrush = SolidColor(theme.colors.inkPrimary),
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && addingTag) {
                                    val t = draft.trim()
                                    if (t.isNotEmpty() && t !in tags) onChange(tags + t)
                                    draft = ""
                                    addingTag = false
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        val t = draft.trim()
                                        if (t.isNotEmpty() && t !in tags) onChange(tags + t)
                                        draft = ""
                                        addingTag = false
                                        true
                                    }
                                    Key.Escape -> {
                                        draft = ""
                                        addingTag = false
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, theme.colors.ruleLine, RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { addingTag = true },
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    ProvideContentColor(theme.colors.inkMuted) {
                        Text(
                            "+ add",
                            style = theme.typography.mono.copy(
                                fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableTagChip(
    label: String,
    onRemove: () -> Unit,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(theme.colors.tintBlue)
            .border(1.dp, theme.colors.ruleLine, RoundedCornerShape(50))
            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        ProvideContentColor(theme.colors.inkSecondary) {
            Text(
                label,
                style = theme.typography.mono.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove,
                )
                .padding(horizontal = 4.dp),
        ) {
            ProvideContentColor(theme.colors.inkMuted) {
                Text(
                    "×",
                    style = theme.typography.mono.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                )
            }
        }
    }
}

private fun parentDirOf(absPath: String): String {
    val idx = absPath.lastIndexOf('/')
    return if (idx <= 0) absPath else absPath.substring(0, idx)
}

private fun openMoveDialog(currentParentDir: String, onPick: (String) -> Unit) {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Move project to…"
        val start = File(currentParentDir)
        if (start.isDirectory) currentDirectory = start
    }
    if (chooser.showDialog(null, "Move here") == JFileChooser.APPROVE_OPTION) {
        val picked = chooser.selectedFile?.absolutePath ?: return
        if (picked.replace('\\', '/') != currentParentDir.replace('\\', '/')) onPick(picked)
    }
}

private fun siblingAlsFiles(row: com.sketchbook.core.ProjectRow): List<java.io.File> {
    val parent = java.io.File(row.path.value).parentFile ?: return emptyList()
    return parent.listFiles { f -> f.isFile && f.name.endsWith(".als", ignoreCase = true) && !f.name.startsWith(".") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "${b}B"
    b < 1024 * 1024 -> "${b / 1024}K"
    b < 1024 * 1024 * 1024 -> "${b / (1024 * 1024)}M"
    else -> "${b / (1024L * 1024 * 1024)}G"
}

@Composable
private fun DetailTracksTab(
    row: com.sketchbook.core.ProjectRow,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    Section("Tracks", theme) {
        if (row.trackCount <= 0) {
            ProvideContentColor(theme.colors.inkMuted) {
                Text(
                    "Track counts populate after the parser runs over this `.als`. The home dashboard's scan ribbon shows progress.",
                    style = theme.typography.body,
                )
            }
        } else {
            DetailRow("Total tracks", row.trackCount.toString(), theme)
            row.tempo?.let { DetailRow("Tempo", "${it.toInt()} bpm", theme) }
            row.lastSavedLiveVersion?.let { DetailRow("Last saved with", it, theme) }
            ProvideContentColor(theme.colors.inkMuted) {
                Text(
                    "Per-track names + audio/MIDI/return/group breakdown will surface once the parser exposes them through the repository (planned).",
                    style = theme.typography.caption,
                )
            }
        }
    }
}

@Composable
private fun DetailSamplesTab(
    samples: List<com.sketchbook.repo.SampleEntry>,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    val missing = samples.count { it.isMissing }
    Section("Samples (${samples.size})", theme) {
        if (samples.isEmpty()) {
            ProvideContentColor(theme.colors.inkMuted) {
                Text(
                    "No sample references on this project — either it's a synth-only sketch or the parser hasn't run yet.",
                    style = theme.typography.body,
                )
            }
            return@Section
        }
        if (missing > 0) {
            ProvideContentColor(theme.colors.accentDanger) {
                Text(
                    "$missing sample${if (missing == 1) "" else "s"} missing from disk. Visit Needs Attention to repair.",
                    style = theme.typography.bodyEmphasis,
                )
            }
        }
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            for (s in samples) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    val (badgeBg, badgeFg, label) = if (s.isMissing) {
                        Triple(theme.colors.tintRose, theme.colors.inkPrimary, "MISSING")
                    } else {
                        Triple(theme.colors.tintSage, theme.colors.inkPrimary, "FOUND")
                    }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .background(badgeBg, androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        ProvideContentColor(badgeFg) {
                            Text(
                                label,
                                style = theme.typography.mono.copy(
                                    fontSize = androidx.compose.ui.unit.TextUnit(9.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.6f, androidx.compose.ui.unit.TextUnitType.Sp),
                                ),
                            )
                        }
                    }
                    ProvideContentColor(theme.colors.inkPrimary) {
                        Text(
                            s.displayName,
                            style = theme.typography.body,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ProvideContentColor(theme.colors.inkMuted) {
                        Text(
                            s.rawPath,
                            style = theme.typography.mono.copy(
                                fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.width(320.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailPluginsTab(
    plugins: List<com.sketchbook.core.PluginRef>,
    theme: com.sketchbook.uishared.theme.AppTheme,
) {
    Section("Plugins (${plugins.size})", theme) {
        if (plugins.isEmpty()) {
            ProvideContentColor(theme.colors.inkMuted) {
                Text(
                    "No plugins detected — either the project is plugin-free or the parser hasn't run yet.",
                    style = theme.typography.body,
                )
            }
            return@Section
        }
        // Group by track so the user reads "kick → drum bus → master" at a glance.
        val byTrack = plugins.groupBy { it.trackName ?: "(master)" }
        for ((track, list) in byTrack) {
            ProvideContentColor(theme.colors.inkSecondary) {
                Text(
                    track,
                    style = theme.typography.mono.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                        letterSpacing = androidx.compose.ui.unit.TextUnit(0.6f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                )
            }
            for (p in list) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
                ) {
                    val (badgeBg, badgeFg, label) = when (p.format) {
                        com.sketchbook.core.PluginFormat.Vst3 -> Triple(theme.colors.tintBlue, theme.colors.inkPrimary, "VST3")
                        com.sketchbook.core.PluginFormat.Vst2 -> Triple(theme.colors.tintBlue, theme.colors.inkPrimary, "VST")
                        com.sketchbook.core.PluginFormat.Au -> Triple(theme.colors.tintRose, theme.colors.inkPrimary, "AU")
                        com.sketchbook.core.PluginFormat.AbletonNative -> Triple(theme.colors.tintCream, theme.colors.inkPrimary, "LIVE")
                        com.sketchbook.core.PluginFormat.Unknown -> Triple(theme.colors.surfaceSunken, theme.colors.inkMuted, "?")
                    }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .background(badgeBg, androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        ProvideContentColor(badgeFg) {
                            Text(
                                label,
                                style = theme.typography.mono.copy(
                                    fontSize = androidx.compose.ui.unit.TextUnit(9.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.6f, androidx.compose.ui.unit.TextUnitType.Sp),
                                ),
                            )
                        }
                    }
                    ProvideContentColor(theme.colors.inkPrimary) {
                        Text(p.name, style = theme.typography.body, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
