package com.sketchbook.desktop.bootstrap

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.repo.HostPluginManifest
import com.sketchbook.repo.MachineEntry
import com.sketchbook.repo.MachineProfileStore
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.TreeRegistry
import com.sketchbook.repo.TreeRegistrySnapshot
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Post-auth, post-migration startup tasks. Each is a single suspend function so the desktop
 * main loop can `coroutineScope { launch { ... } }` them concurrently where order doesn't
 * matter.
 *
 * Wiring in app-desktop's main entry:
 *
 * ```kotlin
 * // After successful auth + completed CloudMigrator:
 * val tasks = BootstrapData(graph.treeRegistry, graph.machineProfileStore, ...)
 * tasks.pullRegistry()
 * tasks.publishHostPluginManifest(hostId, hostName, os)
 * tasks.registerMachine(MachineEntry(...))
 * if (settings.userLibrarySyncEnabled) tasks.materializeUserLibrary(...)
 * ```
 *
 * Tests construct this with fakes and assert that each step calls the right repository
 * surface; the actual desktop main-loop integration lives next to the splash composable.
 */
class BootstrapData(
    private val registry: TreeRegistry,
    private val profile: MachineProfileStore,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * Pull `<tenant>/registry.json` and update the local cache. Returns the snapshot for
     * callers that need to walk the entries (e.g. spawning a per-project pull poller).
     */
    suspend fun pullRegistry(): TreeRegistrySnapshot = registry.fetch()

    /**
     * Publish this host's plugin slice. Idempotent; called both at startup and after
     * `JvmPluginPresenceProbe` re-runs so the cloud view stays in sync.
     */
    suspend fun publishHostPluginManifest(
        hostId: String,
        hostName: String,
        os: String,
    ): Result<HostPluginManifest> = profile.publishHostSlice(hostId, hostName, os)

    /** Register / refresh this host in `<tenant>/profile/machines.json`. */
    suspend fun registerMachine(
        hostId: String,
        hostName: String,
        os: String,
        binaryVersion: String,
    ): Result<Unit> =
        profile.registerMachine(
            MachineEntry(
                hostId = hostId,
                hostName = hostName,
                os = os,
                lastSeenAt = clock.now(),
                binaryVersion = binaryVersion,
            ),
        )

    /**
     * Whether User Library sync is enabled on this host. The actual materialize loop is
     * invoked by the background-pull wiring (a future PR) — this just exposes the flag.
     */
    suspend fun userLibrarySyncEnabled(): Boolean = settings.observe().first().userLibrarySyncEnabled
}

/**
 * Convenience builder so the desktop main loop doesn't need to know the constructor — it
 * just receives a [CloudBackend] handle (post-auth) and grabs everything else from the graph.
 */
fun bootstrapData(
    registry: TreeRegistry,
    profile: MachineProfileStore,
    settings: SettingsRepository,
    @Suppress("UNUSED_PARAMETER") cloud: CloudBackend,
): BootstrapData = BootstrapData(registry, profile, settings)
