package com.sketchbook.desktop

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * OS-level conveniences for the desktop shell.
 *
 * Folder pickers are tricky cross-platform. AWT's `FileDialog` only supports directory selection
 * on macOS (via `apple.awt.fileDialogForDirectories=true`); on Windows/Linux it silently degrades
 * to a file picker — which is what we hit before this rewrite. Strategy: AWT for macOS (it gives
 * the real Finder folder picker), `JFileChooser` for everywhere else. JFileChooser isn't
 * native-styled but it actually works; we apply the system L&F so it looks reasonable.
 */
object Os {
    private val isMac: Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")

    init {
        // Apply the system look-and-feel once so JFileChooser pickers match the host OS chrome.
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    fun openInLive(projectPath: String): Boolean {
        val file = File(projectPath)
        if (!file.exists()) return false
        return runCatching { Desktop.getDesktop().open(file) }.isSuccess
    }

    fun pickDirectory(
        parent: Frame? = null,
        title: String = "Choose folder",
    ): String? = if (isMac) pickDirectoryMac(parent, title) else pickDirectorySwing(title)

    fun pickFile(
        parent: Frame? = null,
        title: String = "Choose file",
    ): String? {
        val dialog = FileDialog(parent, title, FileDialog.LOAD)
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        return if (dir == null || file == null) null else File(dir, file).absolutePath
    }

    private fun pickDirectoryMac(
        parent: Frame?,
        title: String,
    ): String? {
        val previous = System.getProperty("apple.awt.fileDialogForDirectories")
        return try {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = FileDialog(parent, title, FileDialog.LOAD)
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir == null || file == null) null else File(dir, file).absolutePath
        } finally {
            if (previous == null) {
                System.clearProperty("apple.awt.fileDialogForDirectories")
            } else {
                System.setProperty("apple.awt.fileDialogForDirectories", previous)
            }
        }
    }

    private fun pickDirectorySwing(title: String): String? {
        val chooser =
            JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
            }
        val result = chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }
}
