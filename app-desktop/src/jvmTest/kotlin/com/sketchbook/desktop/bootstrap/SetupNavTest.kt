package com.sketchbook.desktop.bootstrap

import com.sketchbook.desktop.ui.setup.SetupNav
import com.sketchbook.desktop.ui.setup.SetupRoute
import com.sketchbook.desktop.ui.setup.SetupState
import com.sketchbook.repo.HostPluginEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupNavTest {
    @Test
    fun unsignedRoutesToSignIn() {
        assertEquals(
            SetupRoute.SignIn,
            SetupNav.next(SetupState(signedIn = false, migrationComplete = false, pendingPluginCount = 0)),
        )
    }

    @Test
    fun signedInButNotMigratedRoutesToMigration() {
        assertEquals(
            SetupRoute.Migration,
            SetupNav.next(SetupState(signedIn = true, migrationComplete = false, pendingPluginCount = 0)),
        )
    }

    @Test
    fun migratedWithPendingPluginsRoutesToChecklist() {
        assertEquals(
            SetupRoute.PluginChecklist,
            SetupNav.next(SetupState(signedIn = true, migrationComplete = true, pendingPluginCount = 7)),
        )
    }

    @Test
    fun migratedWithNoPendingPluginsRoutesToMainApp() {
        assertEquals(
            SetupRoute.MainApp,
            SetupNav.next(SetupState(signedIn = true, migrationComplete = true, pendingPluginCount = 0)),
        )
    }

    @Test
    fun filterPendingDropsInstalledRows() {
        val pending =
            SetupNav.filterPending(
                union =
                    listOf(
                        HostPluginEntry("Serum", "vst3", installed = true),
                        HostPluginEntry("Diva", "vst3", installed = false),
                    ),
                os = "darwin",
            )
        assertEquals(listOf("Diva"), pending.map { it.name })
    }

    @Test
    fun filterPendingExcludesAuOnNonDarwin() {
        val pending =
            SetupNav.filterPending(
                union =
                    listOf(
                        HostPluginEntry("FabFilter Pro-Q 3", "au", installed = false),
                        HostPluginEntry("Diva", "vst3", installed = false),
                    ),
                os = "windows",
            )
        // AU-only plugin filtered out on Windows; vst3 stays.
        assertEquals(listOf("Diva"), pending.map { it.name })
    }

    @Test
    fun filterPendingExcludesVstOnDarwin() {
        val pending =
            SetupNav.filterPending(
                union =
                    listOf(
                        HostPluginEntry("Pro-Q 3", "au", installed = false),
                        HostPluginEntry("Old Plugin", "vst", installed = false),
                    ),
                os = "darwin",
            )
        // vst (vst2) only runs on Windows / Linux; au stays on darwin.
        assertEquals(listOf("Pro-Q 3"), pending.map { it.name })
    }

    @Test
    fun filterPendingExcludesAbletonAndUnknown() {
        val pending =
            SetupNav.filterPending(
                union =
                    listOf(
                        HostPluginEntry("Wavetable", "ableton", installed = false),
                        HostPluginEntry("Mystery", "unknown", installed = false),
                        HostPluginEntry("Diva", "vst3", installed = false),
                    ),
                os = "darwin",
            )
        // Native devices and unknowns aren't user-installable; only Diva stays.
        assertEquals(listOf("Diva"), pending.map { it.name })
    }
}
