package com.sketchbook.desktop

import javax.swing.JFileChooser

/**
 * Native folder/file pickers used by the onboarding flow (and any other surface that wants to
 * reuse the same Swing-backed pattern without re-deriving the JFileChooser boilerplate).
 *
 * Existing call sites (e.g. `RootContent.openMoveDialog`) keep their own inline `JFileChooser`
 * usage — these helpers are additive and exist so [com.sketchbook.featureonboarding.OnboardingScreen]
 * can be handed `() -> String?` lambdas without leaking Swing into the shared module.
 */
fun pickFolderJvm(): String? {
    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    val result = chooser.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}

fun pickFileJvm(): String? {
    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.FILES_ONLY }
    val result = chooser.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}
