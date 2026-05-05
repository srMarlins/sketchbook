package com.sketchbook.desktop

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * OS-level conveniences for the desktop shell. AWT `FileDialog` is used over `JFileChooser` so
 * the picker matches the host OS's native style on Mac and Windows.
 */
object Os {

    fun openInLive(projectPath: String): Boolean {
        val file = File(projectPath)
        if (!file.exists()) return false
        return runCatching { Desktop.getDesktop().open(file) }.isSuccess
    }

    fun pickDirectory(parent: Frame? = null, title: String = "Choose folder"): String? {
        val previous = System.getProperty("apple.awt.fileDialogForDirectories")
        return try {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = FileDialog(parent, title, FileDialog.LOAD)
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir == null || file == null) null else File(dir, file).absolutePath
        } finally {
            if (previous == null) System.clearProperty("apple.awt.fileDialogForDirectories")
            else System.setProperty("apple.awt.fileDialogForDirectories", previous)
        }
    }

    fun pickFile(parent: Frame? = null, title: String = "Choose file"): String? {
        val dialog = FileDialog(parent, title, FileDialog.LOAD)
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        return if (dir == null || file == null) null else File(dir, file).absolutePath
    }
}
