package com.sketchbook.desktop.bootstrap

import com.sketchbook.core.Os
import com.sketchbook.core.PluginFormat
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
                        HostPluginEntry("Serum", PluginFormat.Vst3, installed = true),
                        HostPluginEntry("Diva", PluginFormat.Vst3, installed = false),
                    ),
                os = Os.Mac,
            )
        assertEquals(listOf("Diva"), pending.map { it.name })
    }

    @Test
    fun filterPendingExcludesAuOnNonDarwin() {
        val pending =
            SetupNav.filterPending(
                union =
                    listOf(
                        HostPluginEntry("FabFilter Pro-Q 3", PluginFormat.Au, installed = false),
                        HostPluginEntry("Diva", PluginFormat.Vst3, installed = false),
                    ),
                os = Os.Windows,
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
                        HostPluginEntry("Pro-Q 3", PluginFormat.Au, installed = false),
                        HostPluginEntry("Old Plugin", PluginFormat.Vst2, installed = false),
                    ),
                os = Os.Mac,
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
                        HostPluginEntry("Wavetable", PluginFormat.AbletonNative, installed = false),
                        HostPluginEntry("Mystery", PluginFormat.Unknown, installed = false),
                        HostPluginEntry("Diva", PluginFormat.Vst3, installed = false),
                    ),
                os = Os.Mac,
            )
        // Native devices and unknowns aren't user-installable; only Diva stays.
        assertEquals(listOf("Diva"), pending.map { it.name })
    }
}
