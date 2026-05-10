package com.sketchbook.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation3.runtime.rememberNavBackStack
import com.sketchbook.featureonboarding.OnboardingScreen
import com.sketchbook.featureonboarding.OnboardingViewModel
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.uishared.components.InkLoading
import com.sketchbook.uishared.components.PaperPage
import com.sketchbook.uishared.theme.AppTheme
import com.sketchbook.uishared.theme.AppTypography
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Compose Desktop entry. Builds the composition root once, the Compose Navigation 3 back stack
 * once, and renders the main window. Menu items reset the stack to the chosen destination
 * rather than spawning new windows.
 */
fun main(args: Array<String>) {
    // Diagnostics (rolling log + uncaught-exception → crash file) MUST be the first thing the
    // app does, before Compose initializes — otherwise a Compose-runtime exception during
    // startup leaves the user with no log to attach to a bug report.
    Diagnostics.init(
        dataDir = Diagnostics.resolveDataDir(),
        appVersion = System.getProperty("sketchbook.version") ?: "dev",
    )
    runApp(resetFirstRun = "--reset-first-run" in args)
}

private fun bootstrapGraph(resetFirstRun: Boolean): DesktopAppGraph {
    val graph = buildDesktopAppGraph()
    // Dev-only: clear the onboarding gate before anything else observes Settings, so
    // LaunchGate sees `firstRunCompletedAt = null` on its first emission and routes to the
    // Onboarding surface. Roots / plugin folders / cloud config survive.
    if (resetFirstRun) runBlocking { graph.settingsRepository.resetFirstRun() }
    // Phase 3: the previous per-project polling loop (`startBackgroundPull`) is gone.
    // SyncCoordinator (next commit) subscribes to `/users/{uid}/trees` and fires
    // `pollOnce` on each head_rev advance reported by the Firestore listener. Until that
    // wires in, the desktop runs without cross-machine pull — the local SnapshotPipeline
    // push path continues to work.
    startWatcher(graph)
    graph.libraryScanCoordinator.start()
    // Touch the holder so Metro instantiates the AppScope singleton at startup. The holder's
    // `init { scope.launch { ... } }` then begins observing auth + bucket and rebuilds the
    // per-user graph in the background.
    @Suppress("UnusedExpression")
    graph.userGraphHolder
    // Dev convenience: if SKETCHBOOK_DEFAULT_ROOT is set, seed it on first launch so the
    // iteration loop (build → run → screenshot) doesn't require clicking through the folder
    // picker every time. No-op for normal users (env unset).
    System.getenv("SKETCHBOOK_DEFAULT_ROOT")?.takeIf(String::isNotBlank)?.let { root ->
        graph.appScope.launch { graph.settingsRepository.upsertRoot(LibraryRoot.Projects(root)) }
    }
    return graph
}

private fun runApp(resetFirstRun: Boolean) =
    application {
        val graph = remember { bootstrapGraph(resetFirstRun) }
        val backStack = rememberNavBackStack(NavSavedStateConfig, Screen.Projects)
        val windowState = rememberWindowState(size = DpSize(1360.dp, 900.dp))
        val typography: AppTypography = remember { Fonts.load() }

        // Launch decision: route through LaunchGate so it remains the canonical source of truth.
        // When `OnboardingIntent.Finish` writes `firstRunCompletedAt`, the gate's flow re-emits
        // and this state advances from Onboarding → MainApp, which transparently swaps the surface
        // from OnboardingScreen → RootContent.
        var decision: LaunchDecision? by remember { mutableStateOf(null) }
        LaunchedEffect(graph) { graph.launchGate.observe().collect { decision = it } }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Sketchbook",
        ) {
            MenuBar {
                Menu("File", mnemonic = 'F') {
                    Item("Library settings", onClick = {
                        backStack.clear()
                        backStack.add(Screen.Settings)
                    })
                    Item("Inbox", onClick = {
                        backStack.clear()
                        backStack.add(Screen.Inbox(com.sketchbook.desktop.inbox.InboxTab.NeedsAttention))
                    })
                    Separator()
                    Item("Quit", onClick = ::exitApplication)
                }
                Menu("Project", mnemonic = 'P') {
                    Item("Projects", onClick = {
                        backStack.clear()
                        backStack.add(Screen.Projects)
                    })
                    Item("Proposals", onClick = {
                        backStack.clear()
                        backStack.add(Screen.Inbox(com.sketchbook.desktop.inbox.InboxTab.Proposals))
                    })
                }
            }
            AppTheme(typography = typography) {
                // Install the Metro VM factory once at the window root so every Composable below —
                // including the chrome and per-NavEntry VMs — can call `metroViewModel<X>()` without
                // seeing the graph.
                CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
                    when (decision) {
                        null -> {
                            // Boot splash while the SettingsRepository flow is resolving.
                            PaperPage {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    InkLoading()
                                }
                            }
                        }

                        LaunchDecision.Onboarding -> {
                            val onboardingVm: OnboardingViewModel = metroViewModel()
                            OnboardingScreen(
                                vm = onboardingVm,
                                onPickFolder = ::pickFolderJvm,
                                onPickFile = ::pickFileJvm,
                            )
                        }

                        LaunchDecision.MainApp -> {
                            RootContent(backStack = backStack)
                        }
                    }
                }
            }
        }
    }
