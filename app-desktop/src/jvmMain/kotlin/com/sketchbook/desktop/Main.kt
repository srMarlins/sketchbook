package com.sketchbook.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation3.runtime.rememberNavBackStack
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.uishared.theme.AppTheme
import com.sketchbook.uishared.theme.AppTypography
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.launch

/**
 * Compose Desktop entry. Builds the composition root once, the Compose Navigation 3 back stack
 * once, and renders the main window. Menu items reset the stack to the chosen destination
 * rather than spawning new windows.
 */
fun main() {
    // Diagnostics (rolling log + uncaught-exception → crash file) MUST be the first thing the
    // app does, before Compose initializes — otherwise a Compose-runtime exception during
    // startup leaves the user with no log to attach to a bug report.
    Diagnostics.init(
        dataDir = Diagnostics.resolveDataDir(),
        appVersion = System.getProperty("sketchbook.version") ?: "dev",
    )
    runApp()
}

private fun runApp() = application {
    val graph = remember {
        buildDesktopAppGraph().also {
            startBackgroundPull(it)
            startWatcher(it)
            it.libraryScanCoordinator.start()
        }
    }
    // Dev convenience: if SKETCHBOOK_DEFAULT_ROOT is set and Settings has no roots, seed it
    // once at launch so the iteration loop (build → run → screenshot) doesn't require clicking
    // through the folder picker every time. No-op for normal users (env unset).
    remember(graph) {
        val envRoot = System.getenv("SKETCHBOOK_DEFAULT_ROOT")
        if (!envRoot.isNullOrBlank()) {
            graph.appScope.launch {
                graph.settingsRepository.upsertRoot(LibraryRoot.Projects(envRoot))
            }
        }
    }
    val backStack = rememberNavBackStack(NavSavedStateConfig, Screen.Projects)
    val windowState = rememberWindowState(size = DpSize(1360.dp, 900.dp))
    val typography: AppTypography = remember { Fonts.load() }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Sketchbook",
    ) {
        MenuBar {
            Menu("File", mnemonic = 'F') {
                Item("Library settings", onClick = {
                    backStack.clear(); backStack.add(Screen.Settings)
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
                    backStack.clear(); backStack.add(Screen.Projects)
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
                RootContent(backStack = backStack)
            }
        }
    }
}
