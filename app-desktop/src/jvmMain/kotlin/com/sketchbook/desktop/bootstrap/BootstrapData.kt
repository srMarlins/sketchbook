package com.sketchbook.desktop.bootstrap

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.AppScope
import com.sketchbook.core.Os
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.core.UserId
import com.sketchbook.repo.HostPluginManifest
import com.sketchbook.repo.MachineEntry
import com.sketchbook.repo.MachineProfileStore
import com.sketchbook.repo.RegisterSpec
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.TreeRegistry
import com.sketchbook.repo.TreeRegistryEntry
import com.sketchbook.repo.TreeRegistrySnapshot
import com.sketchbook.repo.getOrThrow
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Post-auth startup tasks. Each operation is a single `suspend fun` returning `Result<T>`
 * so the main loop can `coroutineScope { launch { ... } }` them concurrently and `awaitAll`
 * over a uniform shape. The first task — [seedRegistry] — is the one-shot replacement for
 * the deleted v=1 → v=2 migrator: it makes sure the cloud's `<tenant>/registry.json` doc
 * exists with an entry per local project plus the User Library tree, then flips
 * [com.sketchbook.repo.Settings.registrySeeded] so subsequent launches skip the work.
 *
 * Wiring in app-desktop's main entry:
 *
 * ```kotlin
 * // After successful auth, with a per-user cloud handle resolved:
 * val tasks: BootstrapData = graph.bootstrapData
 * tasks.seedRegistry()
 * tasks.pullRegistry()
 * tasks.publishHostPluginManifest(hostId, hostName, os)
 * tasks.registerMachine(hostId, hostName, os, binaryVersion)
 * if (tasks.userLibrarySyncEnabled()) materialize(...)
 * ```
 *
 * Tests construct this with fakes and assert that each step calls the right repository
 * surface; the desktop main-loop integration sits next to the splash composable.
 */
@SingleIn(AppScope::class)
@Inject
class BootstrapData(
    private val registry: TreeRegistry,
    private val profile: MachineProfileStore,
    private val settings: SettingsRepository,
    private val catalog: Catalog,
    private val ownerUserId: UserId,
    private val hostId: HostId,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    /**
     * Idempotent first-launch bootstrap: ensure the cloud's `<tenant>/registry.json` carries
     * an entry for every local project (keyed by its `project_identity.uuid`) plus the User
     * Library tree (`scope_key = "default"`). Returns the resulting registry entries — useful
     * for callers that want to spawn pull-pollers immediately.
     *
     * No-op when [com.sketchbook.repo.Settings.registrySeeded] is already true: returns the
     * locally-cached snapshot's entries via [TreeRegistry.fetch] without round-tripping the
     * registry write. The seed itself runs `TreeRegistry.registerAll` which is idempotent on
     * `(kind, scope_key)` — a stale/incomplete prior seed (e.g. crash mid-write) completes on
     * the next launch.
     *
     * Reads `selectAllProjectIdentitiesWithName()` inside `withContext(ioDispatcher)` so a
     * caller on the UI dispatcher doesn't block on JDBC.
     */
    suspend fun seedRegistry(): List<TreeRegistryEntry> {
        val current = settings.observe().first()
        if (current.registrySeeded) {
            // Already seeded on this machine. Pull the cloud doc to refresh the local cache
            // and hand callers the entries they would have gotten from a fresh seed.
            return registry.fetch().entries
        }
        val identities =
            withContext(ioDispatcher) {
                catalog.catalogQueries
                    .selectAllProjectIdentitiesWithName()
                    .executeAsList()
                    .map { ProjectIdentity(uuid = it.uuid, name = it.name) }
            }
        val specs =
            identities.map { identity ->
                RegisterSpec(
                    kind = TrackedTreeKind.Project,
                    scopeKey = identity.uuid,
                    displayName = identity.name,
                    treeId = TrackedTreeId(identity.uuid),
                    ownerUserId = ownerUserId,
                    createdByHost = hostId.value,
                )
            } +
                RegisterSpec(
                    kind = TrackedTreeKind.UserLibrary,
                    scopeKey = USER_LIBRARY_SCOPE_KEY,
                    displayName = USER_LIBRARY_DISPLAY_NAME,
                    // One UL tree per user, shared across hosts. registerAll's idempotency
                    // on `(kind, scope_key)` means the second host sees the first one's
                    // tree-id and reuses it; both hosts publish into the same tree and the
                    // Merge conflict mode resolves divergence.
                    treeId = TrackedTreeId(USER_LIBRARY_TREE_ID),
                    ownerUserId = ownerUserId,
                    createdByHost = hostId.value,
                )
        val entries = registry.registerAll(specs).getOrThrow()
        settings.markRegistrySeeded()
        return entries
    }

    /**
     * Pull `<tenant>/registry.json` and update the local cache. Returns the snapshot for
     * callers that need to walk the entries (e.g. spawning a per-project pull poller).
     * Throws on cloud / decode failure.
     */
    suspend fun pullRegistry(): TreeRegistrySnapshot = registry.fetch()

    /**
     * Publish this host's plugin slice. Idempotent; called both at startup and after
     * `JvmPluginPresenceProbe` re-runs so the cloud view stays in sync. Throws on cloud /
     * encode failure.
     */
    suspend fun publishHostPluginManifest(
        hostId: String,
        hostName: String,
        os: Os,
    ): HostPluginManifest = profile.publishHostSlice(hostId, hostName, os)

    /**
     * Register / refresh this host in `<tenant>/profile/machines.json`. Throws on
     * `SketchbookError.Conflict` (CAS exhaustion) or other cloud failures.
     */
    suspend fun registerMachine(
        hostId: String,
        hostName: String,
        os: Os,
        binaryVersion: String,
    ) {
        profile.registerMachine(
            MachineEntry(
                hostId = hostId,
                hostName = hostName,
                os = os,
                lastSeenAt = clock.now(),
                binaryVersion = binaryVersion,
            ),
        )
    }

    /** Whether User Library sync is enabled on this host. */
    suspend fun userLibrarySyncEnabled(): Boolean = settings.observe().first().userLibrarySyncEnabled

    private data class ProjectIdentity(
        val uuid: String,
        val name: String,
    )

    private companion object {
        const val USER_LIBRARY_SCOPE_KEY: String = "default"
        const val USER_LIBRARY_DISPLAY_NAME: String = "Ableton User Library"
        const val USER_LIBRARY_TREE_ID: String = "tt-ul-default"
    }
}

/**
 * Typed wrapper for the host identity injected at bootstrap. Distinct from `String` so the
 * graph can bind the value once via Metro's `@Provides` without colliding with other string
 * bindings.
 */
@JvmInline
value class HostId(
    val value: String,
)
