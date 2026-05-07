package com.sketchbook.featuresettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.auth.AuthState
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
import kotlinx.coroutines.delay

/**
 * Settings screen. The page is centered in a max-width column so it doesn't sprawl on a wide
 * window. Cloud sign-in + bucket configuration land in Phase 5 as a dedicated "Cloud" section;
 * the legacy service-account JSON disclosure has been removed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onAddRootClicked: () -> Unit,
    modifier: Modifier = Modifier,
    syncState: SyncQueueState? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        dispatch = vm::dispatch,
        onAddRootClicked = onAddRootClicked,
        modifier = modifier,
        syncState = syncState,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsContent(
    state: SettingsViewModel.State,
    dispatch: (SettingsViewModel.Intent) -> Unit,
    onAddRootClicked: () -> Unit,
    modifier: Modifier = Modifier,
    syncState: SyncQueueState? = null,
) {
    val scroll = rememberScrollState()
    Column(
        modifier =
            modifier
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
                                dispatch(SettingsViewModel.Intent.RemoveRoot(root))
                            }
                        }
                    }
                }
                Button(onClick = onAddRootClicked, variant = ButtonVariant.Primary) {
                    Text("Add folder…")
                }
            }

            Section(
                title = "Cloud",
                hint = "Sign in with Google so Sketchbook can sync your projects.",
            ) {
                Surface(
                    color = AppTheme.colors.tintBlue,
                    elevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                        when (val auth = state.auth) {
                            is AuthState.SignedIn -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                                ) {
                                    Badge(color = AppTheme.colors.accentPositive) {
                                        Text("signed in", style = AppTheme.typography.caption)
                                    }
                                    Text(auth.email, style = AppTheme.typography.body)
                                }
                                Button(
                                    onClick = { dispatch(SettingsViewModel.Intent.SignOut) },
                                    variant = ButtonVariant.Ghost,
                                ) { Text("Sign out") }
                            }

                            AuthState.SignedOut -> {
                                Text(
                                    "Not signed in. Cloud sync is disabled until you sign in.",
                                    style = AppTheme.typography.body,
                                )
                                Button(
                                    onClick = { dispatch(SettingsViewModel.Intent.SignIn) },
                                    variant = ButtonVariant.Primary,
                                ) { Text("Sign in with Google") }
                            }
                        }
                        var bucketDraft by remember(state.cloudBucket) {
                            mutableStateOf(state.cloudBucket.orEmpty())
                        }
                        // Debounce dispatch so we don't trigger a SyncQueue + UserGraph rebuild
                        // on every keystroke. 500 ms after the user stops typing the new bucket
                        // is committed to settings; the LaunchedEffect's coroutine is cancelled
                        // and re-launched on each subsequent keystroke.
                        LaunchedEffect(bucketDraft) {
                            if (bucketDraft != state.cloudBucket.orEmpty()) {
                                delay(BUCKET_DEBOUNCE_MS)
                                dispatch(
                                    SettingsViewModel.Intent.SetCloudBucket(
                                        bucketDraft.takeIf { it.isNotBlank() },
                                    ),
                                )
                            }
                        }
                        com.sketchbook.uishared.components.TextField(
                            value = bucketDraft,
                            onChange = { bucketDraft = it },
                            placeholder = "Bucket name (e.g. sketchbook-prod)",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                                onClick = { dispatch(SettingsViewModel.Intent.SetCacheSize(scaleSize(cache.maxSizeBytes, +1))) },
                                variant = ButtonVariant.Secondary,
                            ) { Text("Increase") }
                            Button(
                                onClick = { dispatch(SettingsViewModel.Intent.SetCacheSize(scaleSize(cache.maxSizeBytes, -1))) },
                                variant = ButtonVariant.Secondary,
                            ) { Text("Decrease") }
                            Button(
                                onClick = { dispatch(SettingsViewModel.Intent.SetCacheLru(!cache.lruEnabled)) },
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
                            if (state.auth is AuthState.SignedOut) {
                                Text(
                                    "Sign in to enable sync.",
                                    style = AppTheme.typography.caption,
                                )
                            }
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
        }
    }
}

private fun humanRelative(ms: Long): String {
    val deltaMs =
        kotlin.time.Clock.System
            .now()
            .toEpochMilliseconds() - ms
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

private fun scaleSize(
    current: Long,
    direction: Int,
): BlobCacheSettings {
    val gib = (current / (1024L * 1024 * 1024)).coerceAtLeast(1)
    val newGib = (gib + direction * 5L).coerceIn(1L, 500L)
    return BlobCacheSettings(maxSizeBytes = newGib * 1024L * 1024 * 1024, lruEnabled = true)
}

@Composable
private fun Section(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        Text(title, style = AppTheme.typography.bodyEmphasis)
        if (hint != null) {
            Text(hint, style = AppTheme.typography.caption)
        }
        content()
    }
}

@Composable
private fun LibraryRootCard(
    root: LibraryRoot,
    onRemove: () -> Unit,
) {
    Surface(color = AppTheme.colors.surfaceCard, elevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            val (kind, color) =
                when (root) {
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

private const val BUCKET_DEBOUNCE_MS: Long = 500
