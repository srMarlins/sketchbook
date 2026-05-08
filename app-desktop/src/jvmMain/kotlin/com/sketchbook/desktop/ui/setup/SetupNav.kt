package com.sketchbook.desktop.ui.setup

import com.sketchbook.core.Os
import com.sketchbook.core.PluginFormat
import com.sketchbook.repo.HostPluginEntry

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
     *   on Mac; `vst` on Windows/Linux).
     */
    fun filterPending(
        union: List<HostPluginEntry>,
        os: Os,
    ): List<HostPluginEntry> =
        union.filter { entry ->
            !entry.installed && formatRunsOn(entry.format, os)
        }

    /**
     * True iff a plugin in [format] runs on [os]. Public so the plugin checklist VM can OS-
     * filter the `alreadyInstalled` bucket as well as `pending` (a Mac-only AU listed
     * "installed" by the macOS host should not appear in the Windows host's view at all).
     *
     * Exhaustive over [PluginFormat] x [Os] — no `else` branch. Adding a new format or OS
     * is a compile-time decision.
     */
    fun formatRunsOn(
        format: PluginFormat,
        os: Os,
    ): Boolean =
        when (format) {
            PluginFormat.Vst3 -> true
            PluginFormat.Au ->
                when (os) {
                    Os.Mac -> true
                    Os.Windows, Os.Linux -> false
                }

            PluginFormat.Vst2 ->
                when (os) {
                    Os.Windows, Os.Linux -> true
                    Os.Mac -> false
                }

            // Not user-installable — Live ships it, "Unknown" is a parse fallback.
            PluginFormat.AbletonNative, PluginFormat.Unknown -> false
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
