package com.sketchbook.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation3.runtime.rememberNavBackStack
import com.sketchbook.uishared.theme.AppTheme

/**
 * Compose Desktop entry. Builds the composition root once, the Compose Navigation 3 back stack
 * once, and renders the main window. Menu items reset the stack to the chosen destination
 * rather than spawning new windows.
 */
fun main() = application {
    val graph = remember { DesktopAppGraph() }
    val backStack = rememberNavBackStack(NavSavedStateConfig, Screen.Projects)
    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))

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
                Item("Needs attention", onClick = {
                    backStack.clear(); backStack.add(Screen.NeedsAttention)
                })
                Separator()
                Item("Quit", onClick = ::exitApplication)
            }
            Menu("Project", mnemonic = 'P') {
                Item("Projects", onClick = {
                    backStack.clear(); backStack.add(Screen.Projects)
                })
                Item("Proposals", onClick = {
                    backStack.clear(); backStack.add(Screen.Proposals)
                })
            }
        }
        AppTheme {
            RootContent(graph = graph, backStack = backStack)
        }
    }
}
