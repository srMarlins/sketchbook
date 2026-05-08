package com.sketchbook.desktop.ui.setup

/**
 * Post-auth setup-flow ordering. Determines which screen (if any) appears before the user
 * lands on the main app:
 *
 * 1. **Sign in** (existing).
 * 2. **Mandatory migration** (commit 10) — only if legacy paths detected.
 * 3. **Plugin checklist** (commit 15) — only if any plugin row reports "needs install on
 *    this OS" after the registry pull + UL materialize complete.
 *
 * If every plugin needed by any project or template is already installed locally, the
 * screen is skipped — no point making the user dismiss an empty list. The Settings entry
 * (commit 15) opens [PluginChecklistScreen] standalone for follow-up.
 *
 * This file is the policy decider; the actual Compose nav graph wiring lives wherever the
 * main desktop nav is composed (a separate PR will install [SetupRoute.next] into that).
 */
object SetupNav {
    /**
     * Compute the next screen to show given the current state. Pure function — no
     * coroutines, no I/O. Callers gather the inputs (migration result, plugin manifest
     * union) and ask this function which route to navigate to.
     */
    fun next(state: SetupState): SetupRoute =
        when {
            !state.signedIn -> SetupRoute.SignIn
            !state.migrationComplete -> SetupRoute.Migration
            state.pendingPluginCount > 0 -> SetupRoute.PluginChecklist
            else -> SetupRoute.MainApp
        }

    /**
     * Filter the union of host plugin manifests down to the rows the current host *needs*
     * to install. This is the input the plugin checklist screen renders.
     *
     * Filtering rules:
     * - Drop rows installed on this host (the union OR-merged across hosts; we want the
     *   *local* installed flag specifically).
     * - Filter out formats that can't run on this OS (`vst3` works everywhere; `au` only
     *   on darwin; `vst` on Windows/Linux).
     */
    fun filterPending(
        union: List<com.sketchbook.repo.HostPluginEntry>,
        os: String,
    ): List<com.sketchbook.repo.HostPluginEntry> =
        union.filter { entry ->
            !entry.installed && formatRunsOn(entry.format, os)
        }

    /**
     * True iff a plugin in [format] runs on [os]. Public so the plugin checklist VM can OS-
     * filter the `alreadyInstalled` bucket as well as `pending` (a Mac-only AU listed
     * "installed" by the macOS host should not appear in the Windows host's view at all).
     */
    fun formatRunsOn(
        format: String,
        os: String,
    ): Boolean =
        when (format) {
            "vst3" -> true

            "au", "component" -> os == "darwin"

            "vst" -> os == "windows" || os == "linux"

            // not user-installable.
            "ableton", "unknown" -> false

            else -> false
        }
}

data class SetupState(
    val signedIn: Boolean,
    val migrationComplete: Boolean,
    val pendingPluginCount: Int,
)

sealed interface SetupRoute {
    data object SignIn : SetupRoute

    data object Migration : SetupRoute

    data object PluginChecklist : SetupRoute

    data object MainApp : SetupRoute
}
