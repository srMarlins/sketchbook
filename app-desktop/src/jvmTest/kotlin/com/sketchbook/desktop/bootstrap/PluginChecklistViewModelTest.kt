package com.sketchbook.desktop.bootstrap

import com.sketchbook.desktop.ui.setup.PluginChecklistViewModel
import com.sketchbook.repo.HostPluginEntry
import com.sketchbook.repo.HostPluginManifest
import com.sketchbook.repo.MachineEntry
import com.sketchbook.repo.MachineProfileStore
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
            val store = StubProfileStore(union = UnionedPluginManifest(emptyList(), emptyList()))
            val vm = PluginChecklistViewModel(store, osProvider = { "darwin" })
            assertEquals(0, vm.state.value.pending.size)
            assertEquals(0, vm.state.value.alreadyInstalled.size)
        }

    @Test
    fun pendingFilteredByOsAtInit() =
        runTest(dispatcher) {
            val store =
                StubProfileStore(
                    union =
                        UnionedPluginManifest(
                            perHost = emptyList(),
                            union =
                                listOf(
                                    HostPluginEntry("Diva", "vst3", installed = false),
                                    HostPluginEntry("Pro-Q 3", "au", installed = false),
                                    HostPluginEntry("Old Dog", "vst", installed = false),
                                ),
                        ),
                )
            val vm = PluginChecklistViewModel(store, osProvider = { "windows" })

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
            val store =
                StubProfileStore(
                    union =
                        UnionedPluginManifest(
                            perHost = emptyList(),
                            union =
                                listOf(
                                    HostPluginEntry("Serum", "vst3", installed = true),
                                    HostPluginEntry("Diva", "vst3", installed = false),
                                ),
                        ),
                )
            val vm = PluginChecklistViewModel(store, osProvider = { "darwin" })

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
    fun reprobeMovesNewlyInstalledToRecentlyInstalled() =
        runTest(dispatcher) {
            val store =
                StubProfileStore(
                    union =
                        UnionedPluginManifest(
                            perHost = emptyList(),
                            union = listOf(HostPluginEntry("Diva", "vst3", installed = false)),
                        ),
                )
            val vm = PluginChecklistViewModel(store, osProvider = { "darwin" })
            assertEquals(
                listOf("Diva"),
                vm.state.value.pending
                    .map { it.name },
            )

            vm.reprobe { listOf(HostPluginEntry("Diva", "vst3", installed = true)) }

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
}

private class StubProfileStore(
    private val union: UnionedPluginManifest,
) : MachineProfileStore {
    override suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: String,
    ): Result<HostPluginManifest> =
        Result.success(
            HostPluginManifest(
                hostId = hostId,
                hostName = hostName,
                os = os,
                computedAt = Instant.fromEpochMilliseconds(0),
                plugins = emptyList(),
            ),
        )

    override suspend fun composeUnion(): UnionedPluginManifest = union

    override suspend fun registerMachine(entry: MachineEntry): Result<Unit> = Result.success(Unit)

    override suspend fun listMachines(): List<MachineEntry> = emptyList()
}
