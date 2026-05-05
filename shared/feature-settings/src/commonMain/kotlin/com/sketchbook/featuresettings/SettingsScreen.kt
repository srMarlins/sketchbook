package com.sketchbook.featuresettings

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
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import androidx.compose.foundation.lazy.items
import com.sketchbook.uishared.theme.AppTheme

/**
 * Settings screen. Wires intents to the desktop shell — `onAddRootClicked` opens a native file
 * picker (PR-18); the result is dispatched back as `Intent.AddRoot`.
 */
@Composable
fun SettingsScreen(
    holder: SettingsStateHolder,
    onAddRootClicked: () -> Unit,
    onUploadCredentialClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.surfacePage)
            .padding(PaddingValues(AppTheme.spacing.md)),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
    ) {
        Text("Settings", style = AppTheme.typography.title)

        Section("Library roots") {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                items(state.libraryRoots) { root ->
                    LibraryRootCard(root) {
                        holder.dispatch(SettingsStateHolder.Intent.RemoveRoot(root))
                    }
                }
            }
            Button(onClick = onAddRootClicked, variant = ButtonVariant.Secondary) {
                Text("Add root…")
            }
        }

        Section("Cloud sync") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                Badge(
                    color = if (state.cloudConfigured) AppTheme.colors.pinGreen else AppTheme.colors.accentSecondary,
                ) {
                    Text(
                        if (state.cloudConfigured) "configured" else "not configured",
                        style = AppTheme.typography.caption,
                    )
                }
                Button(onClick = onUploadCredentialClicked, variant = ButtonVariant.Secondary) {
                    Text(if (state.cloudConfigured) "Replace credential" else "Upload service-account JSON")
                }
                if (state.cloudConfigured) {
                    Button(
                        onClick = { holder.dispatch(SettingsStateHolder.Intent.SetCloudCredential(null)) },
                        variant = ButtonVariant.Ghost,
                    ) { Text("Clear") }
                }
            }
        }

        Section("Self-contained projects") {
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        Text(title, style = AppTheme.typography.bodyEmphasis)
        content()
    }
}

@Composable
private fun LibraryRootCard(root: LibraryRoot, onRemove: () -> Unit) {
    Surface(color = AppTheme.colors.surfacePanel) {
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

