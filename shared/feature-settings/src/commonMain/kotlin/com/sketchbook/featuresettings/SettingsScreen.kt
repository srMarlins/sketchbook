package com.sketchbook.featuresettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.SyncQueueState
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.PageHeader
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Settings screen. The page is centered in a max-width column so it doesn't sprawl on a wide
 * window. Cloud sync is parked behind an "Advanced" disclosure — at v1 the cloud isn't actually
 * wired through, and the bare "Upload service-account JSON" button confused real users in
 * testing (there's no context for what it is). The disclosure both explains it and lets us
 * keep the wiring for power users who already have a GCS account ready.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onAddRootClicked: () -> Unit,
    onUploadCredentialClicked: () -> Unit,
    modifier: Modifier = Modifier,
    syncState: SyncQueueState? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showCloud by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(PaddingValues(horizontal = AppTheme.spacing.xl, vertical = AppTheme.spacing.lg)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xl),
        ) {
            PageHeader(
                title = "Settings",
                subtitle = "Library roots, cache, and the things you'll set once and forget about.",
            )

            Section(
                title = "Library roots",
                hint = "Folders Sketchbook scans for Ableton projects and samples.",
            ) {
                if (state.libraryRoots.isEmpty()) {
                    Surface(
                        color = AppTheme.colors.tintCream,
                        padding = PaddingValues(AppTheme.spacing.md),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                            Text("No roots configured yet.", style = AppTheme.typography.bodyEmphasis)
                            Text(
                                "Add the folder where you keep your Ableton projects (often " +
                                    "“My Documents/Live Projects” on Windows or “~/Music/Ableton/User Library” " +
                                    "on Mac). You can add more later.",
                                style = AppTheme.typography.body,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                    ) {
                        for (root in state.libraryRoots) {
                            LibraryRootCard(root) {
                                vm.dispatch(SettingsViewModel.Intent.RemoveRoot(root))
                            }
                        }
                    }
                }
                Button(onClick = onAddRootClicked, variant = ButtonVariant.Primary) {
                    Text("Add folder…")
                }
            }

            Section(
                title = "Local blob cache",
                hint = "How much disk to spend on cached blobs that back rewinds and snapshots.",
            ) {
                val cache = state.cacheSettings
                Surface(
                    color = AppTheme.colors.surfaceCard,
                    elevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                        Text(
                            "Cache size: ${humanGiB(cache.maxSizeBytes)} · LRU ${if (cache.lruEnabled) "on" else "off"}",
                            style = AppTheme.typography.body,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                        ) {
                            Button(
                                onClick = { vm.dispatch(SettingsViewModel.Intent.SetCacheSize(scaleSize(cache.maxSizeBytes, +1))) },
                                variant = ButtonVariant.Secondary,
                            ) { Text("Increase") }
                            Button(
                                onClick = { vm.dispatch(SettingsViewModel.Intent.SetCacheSize(scaleSize(cache.maxSizeBytes, -1))) },
                                variant = ButtonVariant.Secondary,
                            ) { Text("Decrease") }
                            Button(
                                onClick = { vm.dispatch(SettingsViewModel.Intent.SetCacheLru(!cache.lruEnabled)) },
                                variant = ButtonVariant.Ghost,
                            ) { Text(if (cache.lruEnabled) "Disable LRU" else "Enable LRU") }
                        }
                    }
                }
            }

            if (syncState != null) {
                Section(
                    title = "Cloud sync",
                    hint = "Live status of the upload/download queue. The button kicks the next pending push.",
                ) {
                    Surface(
                        color = AppTheme.colors.tintBlue,
                        elevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                            ) {
                                Badge(
                                    color = if (syncState.online) AppTheme.colors.accentPositive else AppTheme.colors.accentDanger,
                                ) {
                                    Text(
                                        if (syncState.online) "online" else "offline",
                                        style = AppTheme.typography.caption,
                                    )
                                }
                                Text(
                                    "Pending ${syncState.pending} · Uploading ${syncState.uploading} · Downloading ${syncState.downloading}",
                                    style = AppTheme.typography.body,
                                )
                            }
                            val lastSuccess = syncState.lastSuccessAtMs
                            if (lastSuccess != null) {
                                Text(
                                    "Last successful sync: ${humanRelative(lastSuccess)}",
                                    style = AppTheme.typography.caption,
                                )
                            } else {
                                Text(
                                    "No successful sync yet this session.",
                                    style = AppTheme.typography.caption,
                                )
                            }
                            val err = syncState.lastErrorMessage
                            if (err != null) {
                                Text(
                                    "Last error: $err",
                                    style = AppTheme.typography.caption,
                                )
                            }
                        }
                    }
                }
            }

            Section(
                title = "Self-contained projects",
                hint = "Projects flagged self-contained skip cloud dedup so collaborators don't see your stems.",
            ) {
                if (state.selfContainedProjects.isEmpty()) {
                    Text("No projects flagged self-contained.", style = AppTheme.typography.body)
                } else {
                    Text(
                        "${state.selfContainedProjects.size} project(s) skip cloud dedup. Toggle from each project's detail pane.",
                        style = AppTheme.typography.caption,
                    )
                }
            }

            // Cloud sync is parked: at v1 it isn't actually wired through, and the bare
            // "service-account JSON" button confuses anyone who isn't already running their
            // own GCS bucket. Keep it tucked away under an Advanced disclosure.
            Section(
                title = "Advanced",
                hint = "Power-user toggles. Most people can ignore this section.",
            ) {
                Button(
                    onClick = { showCloud = !showCloud },
                    variant = ButtonVariant.Ghost,
                ) {
                    Text(if (showCloud) "Hide cloud sync" else "Show cloud sync")
                }
                if (showCloud) {
                    Surface(
                        color = AppTheme.colors.tintBlue,
                        padding = PaddingValues(AppTheme.spacing.md),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                            Text("Cloud sync (preview)", style = AppTheme.typography.bodyEmphasis)
                            Text(
                                "Sketchbook backs snapshots up to a Google Cloud Storage bucket so projects round-trip across machines. Drop in a service-account JSON with read/write on the bucket, then enter the bucket name below.",
                                style = AppTheme.typography.body,
                            )
                            FlowRow(
                                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                            ) {
                                Badge(
                                    color = if (state.cloudReady) AppTheme.colors.accentPositive
                                        else if (state.cloudConfigured) AppTheme.colors.accentWarning
                                        else AppTheme.colors.accentSecondary,
                                ) {
                                    Text(
                                        when {
                                            state.cloudReady -> "ready"
                                            state.cloudConfigured -> "needs bucket"
                                            else -> "not configured"
                                        },
                                        style = AppTheme.typography.caption,
                                    )
                                }
                                Button(onClick = onUploadCredentialClicked, variant = ButtonVariant.Secondary) {
                                    Text(if (state.cloudConfigured) "Replace credential…" else "Choose JSON…")
                                }
                                if (state.cloudConfigured) {
                                    Button(
                                        onClick = { vm.dispatch(SettingsViewModel.Intent.SetCloudCredential(null)) },
                                        variant = ButtonVariant.Ghost,
                                    ) { Text("Clear") }
                                }
                            }
                            // Bucket name field. Saved on every keystroke (in-memory store; the
                            // flow rebuilds the SyncQueue on transition to ready/unready, not
                            // on every typed char — distinctUntilChanged on cloudReady).
                            var bucketDraft by remember(state.cloudBucket) {
                                mutableStateOf(state.cloudBucket.orEmpty())
                            }
                            com.sketchbook.uishared.components.TextField(
                                value = bucketDraft,
                                onChange = {
                                    bucketDraft = it
                                    vm.dispatch(SettingsViewModel.Intent.SetCloudBucket(it.takeIf { v -> v.isNotBlank() }))
                                },
                                placeholder = "Bucket name (e.g. sketchbook-srmarlins)",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun humanRelative(ms: Long): String {
    val deltaMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - ms
    val sec = deltaMs / 1000
    return when {
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86_400 -> "${sec / 3600}h ago"
        else -> "${sec / 86_400}d ago"
    }
}

private fun humanGiB(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024 * 1024)
    return if (gb >= 1.0) "${"%.1f".format(gb)} GiB" else "${bytes / (1024 * 1024)} MiB"
}

private fun scaleSize(current: Long, direction: Int): BlobCacheSettings {
    val gib = (current / (1024L * 1024 * 1024)).coerceAtLeast(1)
    val newGib = (gib + direction * 5L).coerceIn(1L, 500L)
    return BlobCacheSettings(maxSizeBytes = newGib * 1024L * 1024 * 1024, lruEnabled = true)
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        Text(title, style = AppTheme.typography.bodyEmphasis)
        if (hint != null) {
            Text(hint, style = AppTheme.typography.caption)
        }
        content()
    }
}

@Composable
private fun LibraryRootCard(root: LibraryRoot, onRemove: () -> Unit) {
    Surface(color = AppTheme.colors.surfaceCard, elevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            val (kind, color) = when (root) {
                is LibraryRoot.Projects -> "projects" to AppTheme.colors.pinBlue
                is LibraryRoot.UserSamples -> "samples" to AppTheme.colors.pinPurple
                is LibraryRoot.External -> "external (${root.kind.name.lowercase()})" to AppTheme.colors.pinOrange
            }
            Badge(color = color) { Text(kind, style = AppTheme.typography.caption) }
            Column(modifier = Modifier.weight(1f)) {
                Text(root.path, style = AppTheme.typography.body)
                if (root is LibraryRoot.External) {
                    Text("alias: ${root.alias}", style = AppTheme.typography.caption)
                }
            }
            Button(onClick = onRemove, variant = ButtonVariant.Ghost) { Text("Remove") }
        }
    }
}
