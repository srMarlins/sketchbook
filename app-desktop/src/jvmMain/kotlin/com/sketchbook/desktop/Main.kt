package com.sketchbook.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.sketchbook.uishared.theme.AppTheme

/**
 * Compose Desktop entry. Builds the composition root once, holds a single [RootStateHolder] for
 * navigation, and renders the main window. Menu items hand off to feature surfaces — Library
 * settings flips the stack to `Screen.Settings` rather than spawning a separate window.
 */
fun main() = application {
    val graph = remember { DesktopAppGraph() }
    val root = remember { RootStateHolder() }

    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Sketchbook",
    ) {
        MenuBar {
            Menu("File", mnemonic = 'F') {
                Item("Library settings", onClick = { root.reset(Screen.Settings) })
                Item("Needs attention", onClick = { root.reset(Screen.NeedsAttention) })
                Separator()
                Item("Quit", onClick = ::exitApplication)
            }
            Menu("Project", mnemonic = 'P') {
                Item("Projects", onClick = { root.reset(Screen.Projects) })
                Item("Proposals", onClick = { root.reset(Screen.Proposals) })
            }
        }
        AppTheme {
            RootContent(graph = graph, root = root)
        }
    }
}
