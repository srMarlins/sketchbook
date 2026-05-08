package com.sketchbook.desktop.bootstrap

import com.sketchbook.core.Os
import com.sketchbook.core.PluginFormat
import com.sketchbook.desktop.ui.setup.HostSliceContext
import com.sketchbook.desktop.ui.setup.OsProvider
import com.sketchbook.desktop.ui.setup.PluginChecklistViewModel
import com.sketchbook.repo.HostPluginEntry
import com.sketchbook.repo.HostPluginManifest
import com.sketchbook.repo.MachineEntry
import com.sketchbook.repo.MachineProfileStore
import com.sketchbook.repo.PluginPresenceProbe
import com.sketchbook.repo.UnionedPluginManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PluginChecklistViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyUnionYieldsEmptyState() =
        runTest(dispatcher) {
            val vm = newViewModel(union = UnionedPluginManifest(emptyList(), emptyList()), os = Os.Mac)
            assertEquals(0, vm.state.value.pending.size)
            assertEquals(0, vm.state.value.alreadyInstalled.size)
            // Initial-load flag clears once the first compose lands.
            assertEquals(false, vm.state.value.isInitialLoad)
        }

    @Test
    fun pendingFilteredByOsAtInit() =
        runTest(dispatcher) {
            val vm =
                newViewModel(
                    union =
                        UnionedPluginManifest(
                            perHost = emptyList(),
                            union =
                                listOf(
                                    HostPluginEntry("Diva", PluginFormat.Vst3, installed = false),
                                    HostPluginEntry("Pro-Q 3", PluginFormat.Au, installed = false),
                                    HostPluginEntry("Old Dog", PluginFormat.Vst2, installed = false),
                                ),
                        ),
                    os = Os.Windows,
                )

            // au excluded on windows; vst kept; vst3 kept.
            val names =
                vm.state.value.pending
                    .map { it.name }
                    .toSet()
            assertEquals(setOf("Diva", "Old Dog"), names)
        }

    @Test
    fun installedRowsLandInAlreadyInstalled() =
        runTest(dispatcher) {
            val vm =
                newViewModel(
                    union =
                        UnionedPluginManifest(
                            perHost = emptyList(),
                            union =
                                listOf(
                                    HostPluginEntry("Serum", PluginFormat.Vst3, installed = true),
                                    HostPluginEntry("Diva", PluginFormat.Vst3, installed = false),
                                ),
                        ),
                    os = Os.Mac,
                )

            assertEquals(
                listOf("Diva"),
                vm.state.value.pending
                    .map { it.name },
            )
            assertEquals(
                listOf("Serum"),
                vm.state.value.alreadyInstalled
                    .map { it.name },
            )
        }

    @Test
    fun alreadyInstalledIsOsFilteredSoMacOnlyAuDoesNotShowOnWindows() =
        runTest(dispatcher) {
            // A Mac host's slice reports a macOS-only AU as installed; the Windows host
            // should not list it under "already installed" — its format can't run on this OS.
            val vm =
                newViewModel(
                    union =
                        UnionedPluginManifest(
                            perHost = emptyList(),
                            union =
                                listOf(
                                    // Reports "installed somewhere" but format=au is Mac-only.
                                    HostPluginEntry("ChannelEQ", PluginFormat.Au, installed = true),
                                    HostPluginEntry("Serum", PluginFormat.Vst3, installed = true),
                                ),
                        ),
                    os = Os.Windows,
                )

            assertEquals(
                listOf("Serum"),
                vm.state.value.alreadyInstalled
                    .map { it.name },
                "Mac-only AU must not appear in the Windows already-installed bucket",
            )
        }

    @Test
    fun reprobeMovesNewlyInstalledToRecentlyInstalled() =
        runTest(dispatcher) {
            val store =
                MutableStubProfileStore(
                    union = UnionedPluginManifest(emptyList(), listOf(HostPluginEntry("Diva", PluginFormat.Vst3, false))),
                )
            val vm = newViewModel(store = store, probe = StubProbe(), os = Os.Mac)
            assertEquals(
                listOf("Diva"),
                vm.state.value.pending
                    .map { it.name },
            )

            store.union = UnionedPluginManifest(emptyList(), listOf(HostPluginEntry("Diva", PluginFormat.Vst3, true)))
            vm.reprobe()

            assertEquals(
                emptyList(),
                vm.state.value.pending
                    .map { it.name },
            )
            assertEquals(
                listOf("Diva"),
                vm.state.value.recentlyInstalled
                    .map { it.name },
            )
            assertTrue(!vm.state.value.isReprobing)
        }

    @Test
    fun reprobeReconcilesRecentlyInstalledAgainstLatestUnion() =
        runTest(dispatcher) {
            // First reprobe moves Diva into recentlyInstalled. Second reprobe sees Diva
            // *uninstalled* (user removed it mid-session); the bucket should drop it rather
            // than carry the stale "just installed" forever.
            val store =
                MutableStubProfileStore(
                    union = UnionedPluginManifest(emptyList(), listOf(HostPluginEntry("Diva", PluginFormat.Vst3, false))),
                )
            val vm = newViewModel(store = store, probe = StubProbe(), os = Os.Mac)

            // Round 1: install Diva.
            store.union = UnionedPluginManifest(emptyList(), listOf(HostPluginEntry("Diva", PluginFormat.Vst3, true)))
            vm.reprobe()
            assertEquals(
                listOf("Diva"),
                vm.state.value.recentlyInstalled
                    .map { it.name },
            )

            // Round 2: user uninstalled Diva again.
            store.union = UnionedPluginManifest(emptyList(), listOf(HostPluginEntry("Diva", PluginFormat.Vst3, false)))
            vm.reprobe()
            assertEquals(
                emptyList(),
                vm.state.value.recentlyInstalled
                    .map { it.name },
                "stale recentlyInstalled entry must be reconciled out when the user uninstalls",
            )
            // Diva is back to pending (not installed, vst3 runs on darwin).
            assertEquals(
                listOf("Diva"),
                vm.state.value.pending
                    .map { it.name },
            )
        }

    @Test
    fun composeUnionFailureSurfacesAsLoadFailed() =
        runTest(dispatcher) {
            val store = ThrowingProfileStore(IllegalStateException("network down"))
            val vm = newViewModel(store = store, probe = StubProbe(), os = Os.Mac)

            // refresh ran in init; failure path should clear isInitialLoad and stamp the
            // loadFailed message.
            assertEquals(false, vm.state.value.isInitialLoad)
            assertNotNull(vm.state.value.loadFailed)
            assertTrue(
                vm.state.value.loadFailed!!
                    .contains("network down"),
            )
        }

    @Test
    fun reprobeDispatchesProbeBeforeReadingUnion() =
        runTest(dispatcher) {
            val probe = CountingProbe()
            val store =
                MutableStubProfileStore(
                    union = UnionedPluginManifest(emptyList(), listOf(HostPluginEntry("Diva", PluginFormat.Vst3, false))),
                )
            val vm = newViewModel(store = store, probe = probe, os = Os.Mac)

            assertEquals(0, probe.callCount, "init should not run probe; only reprobe does")

            vm.reprobe()
            assertEquals(1, probe.callCount, "reprobe must call the presence probe")
            assertEquals(1, store.publishCount, "reprobe must re-publish the host slice")
        }

    @Test
    fun loadFailedClearsOnSuccessfulReprobe() =
        runTest(dispatcher) {
            val store =
                FlakyProfileStore(
                    initialFailure = IllegalStateException("boom"),
                    successUnion = UnionedPluginManifest(emptyList(), listOf(HostPluginEntry("Diva", PluginFormat.Vst3, false))),
                )
            val vm = newViewModel(store = store, probe = StubProbe(), os = Os.Mac)
            assertNotNull(vm.state.value.loadFailed)

            vm.reprobe()
            assertNull(vm.state.value.loadFailed)
            assertEquals(
                listOf("Diva"),
                vm.state.value.pending
                    .map { it.name },
            )
        }

    private fun newViewModel(
        store: MachineProfileStore,
        probe: PluginPresenceProbe = StubProbe(),
        os: Os,
    ): PluginChecklistViewModel =
        PluginChecklistViewModel(
            profileStore = store,
            presenceProbe = probe,
            hostInfo = HostSliceContext(hostId = "test-host", hostName = "Test Host"),
            osProvider = OsProvider { os },
        )

    private fun newViewModel(
        union: UnionedPluginManifest,
        os: Os,
    ): PluginChecklistViewModel = newViewModel(store = ImmutableStubProfileStore(union), probe = StubProbe(), os = os)
}

private class StubProbe : PluginPresenceProbe {
    override suspend fun probe(): PluginPresenceProbe.ProbeResult = PluginPresenceProbe.ProbeResult.EMPTY
}

private class CountingProbe : PluginPresenceProbe {
    var callCount = 0
        private set

    override suspend fun probe(): PluginPresenceProbe.ProbeResult {
        callCount += 1
        return PluginPresenceProbe.ProbeResult.EMPTY
    }
}

private class ImmutableStubProfileStore(
    private val union: UnionedPluginManifest,
) : MachineProfileStore {
    override suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: Os,
    ): Result<HostPluginManifest> = Result.success(emptyManifest(hostId, hostName, os))

    override suspend fun composeUnion(): UnionedPluginManifest = union

    override suspend fun registerMachine(entry: MachineEntry): Result<Unit> = Result.success(Unit)

    override suspend fun listMachines(): List<MachineEntry> = emptyList()
}

private class MutableStubProfileStore(
    var union: UnionedPluginManifest,
) : MachineProfileStore {
    var publishCount = 0
        private set

    override suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: Os,
    ): Result<HostPluginManifest> {
        publishCount += 1
        return Result.success(emptyManifest(hostId, hostName, os))
    }

    override suspend fun composeUnion(): UnionedPluginManifest = union

    override suspend fun registerMachine(entry: MachineEntry): Result<Unit> = Result.success(Unit)

    override suspend fun listMachines(): List<MachineEntry> = emptyList()
}

private class ThrowingProfileStore(
    private val cause: Throwable,
) : MachineProfileStore {
    override suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: Os,
    ): Result<HostPluginManifest> = Result.failure(cause)

    override suspend fun composeUnion(): UnionedPluginManifest = throw cause

    override suspend fun registerMachine(entry: MachineEntry): Result<Unit> = Result.failure(cause)

    override suspend fun listMachines(): List<MachineEntry> = emptyList()
}

private class FlakyProfileStore(
    private val initialFailure: Throwable,
    private val successUnion: UnionedPluginManifest,
) : MachineProfileStore {
    private var firstCall = true

    override suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: Os,
    ): Result<HostPluginManifest> = Result.success(emptyManifest(hostId, hostName, os))

    override suspend fun composeUnion(): UnionedPluginManifest =
        if (firstCall) {
            firstCall = false
            throw initialFailure
        } else {
            successUnion
        }

    override suspend fun registerMachine(entry: MachineEntry): Result<Unit> = Result.success(Unit)

    override suspend fun listMachines(): List<MachineEntry> = emptyList()
}

private fun emptyManifest(
    hostId: String,
    hostName: String,
    os: Os,
): HostPluginManifest =
    HostPluginManifest(
        hostId = hostId,
        hostName = hostName,
        os = os,
        computedAt = Instant.fromEpochMilliseconds(0),
        plugins = emptyList(),
    )
