package com.sketchbook.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.featuredetail.ProjectDetailScreen
import com.sketchbook.featuredetail.ProjectDetailStateHolder
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
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Root composable: a sidebar of top-level destinations + a body that renders the screen at the
 * top of the [RootStateHolder] stack. Feature `StateHolder`s are remembered against the graph's
 * application scope so navigating away and back keeps their `stateIn` caches warm.
 *
 * `ProjectDetail` and `Timeline` need a routed argument (`ProjectId` / `ProjectUuid`); we pass
 * those to `holder.load(...)` whenever the stack-top screen changes.
 */
@Composable
fun RootContent(graph: DesktopAppGraph, root: RootStateHolder) {
    val stack by root.stack.collectAsState()
    val current = stack.last()

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
        ProposalsStateHolder(graph.proposalsRepository, graph.appScope)
    }
    val needsAttentionHolder = remember {
        NeedsAttentionStateHolder(graph.repairRepository, graph.appScope)
    }
    val settingsHolder = remember {
        SettingsStateHolder(graph.settingsRepository, graph.appScope)
    }

    LaunchedEffect(projectListHolder) {
        projectListHolder.effects.collect { effect ->
            when (effect) {
                is ProjectListStateHolder.Effect.Navigate -> root.push(Screen.ProjectDetail(effect.id))
            }
        }
    }
    LaunchedEffect(projectDetailHolder) {
        projectDetailHolder.effects.collect { effect ->
            when (effect) {
                is ProjectDetailStateHolder.Effect.LaunchLive -> {
                    Os.openInLive(effect.projectPath)
                }
                ProjectDetailStateHolder.Effect.LockTaken,
                is ProjectDetailStateHolder.Effect.LockTakeFailed,
                -> Unit
            }
        }
    }

    LaunchedEffect(current) {
        when (current) {
            is Screen.ProjectDetail -> projectDetailHolder.load(current.id)
            is Screen.Timeline -> timelineHolder.load(current.uuid)
            else -> Unit
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(AppTheme.colors.surfacePage)) {
        Sidebar(current = current, onSelect = { root.reset(it) })
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (current) {
                Screen.Projects -> ProjectListScreen(projectListHolder)
                is Screen.ProjectDetail -> ProjectDetailScreen(projectDetailHolder)
                is Screen.Timeline -> TimelineScreen(timelineHolder)
                Screen.Proposals -> ProposalsScreen(proposalsHolder)
                Screen.NeedsAttention -> NeedsAttentionScreen(needsAttentionHolder)
                Screen.Settings -> SettingsScreen(
                    holder = settingsHolder,
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
                            settingsHolder.dispatch(SettingsStateHolder.Intent.SetCloudCredential(json))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Sidebar(current: Screen, onSelect: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(AppTheme.colors.surfacePanel)
            .padding(PaddingValues(AppTheme.spacing.md)),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Text("Sketchbook", style = AppTheme.typography.title)
        SidebarItem("Projects", isActive(current, Screen.Projects)) { onSelect(Screen.Projects) }
        SidebarItem("Proposals", isActive(current, Screen.Proposals)) { onSelect(Screen.Proposals) }
        SidebarItem("Needs attention", isActive(current, Screen.NeedsAttention)) { onSelect(Screen.NeedsAttention) }
        SidebarItem("Settings", isActive(current, Screen.Settings)) { onSelect(Screen.Settings) }
    }
}

private fun isActive(current: Screen, target: Screen): Boolean = when (target) {
    Screen.Projects -> current is Screen.Projects || current is Screen.ProjectDetail || current is Screen.Timeline
    else -> current == target
}

@Composable
private fun SidebarItem(label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) AppTheme.colors.accentAction else AppTheme.colors.surfacePanel
    Surface(
        color = color,
        padding = PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = if (active) AppTheme.typography.bodyEmphasis else AppTheme.typography.body,
        )
    }
}
