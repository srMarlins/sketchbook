package com.sketchbook.migration

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.CloudDocRef
import com.sketchbook.cloud.Generation
import com.sketchbook.core.CloudDocKey
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.core.UserId
import com.sketchbook.repo.RegisterSpec
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.TreeRegistry
import com.sketchbook.repo.TreeRegistryEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM-backed [CloudMigrator]. Operates only via [CloudBackend]'s `CloudDoc` and `*Manifest`
 * surfaces — no direct GCS calls — so the same code path runs in tests against
 * `FakeCloudBackend` and in production against `DirectGcsBackend`.
 *
 * Source path layout (v=1, what we read): `<tenant>/manifests/<project_uuid>/<rev>-...json`.
 * Destination layout (v=2, what we write): `<tenant>/trees/project/<tree_id>/manifests/<rev>-...json`.
 * The tree id is the project UUID itself — we deliberately don't mint new ULIDs for
 * existing projects; doing so would orphan every blob already keyed by the legacy id and
 * introduce a second source of identity. New trees registered after this point (UserLibrary,
 * future kinds) get fresh ULIDs.
 *
 * Reads at the destination path use `expected = Generation.ZERO` (must-not-exist) so a
 * partially-migrated bucket completes the rest on re-run rather than overwriting work the
 * other host has already done.
 */
/**
 * Constructed at the call site (the desktop migration coordinator) once a
 * [CloudBackend] is available — there's no app-scoped CloudBackend binding because the
 * cloud handle is per-user / per-cred-rotation via `SwappableSyncQueue`.
 */
class JvmCloudMigrator(
    private val cloud: CloudBackend,
    private val catalog: Catalog,
    private val registry: TreeRegistry,
    private val settings: SettingsRepository,
    private val ownerUserId: UserId = UserId.DEFAULT,
    private val hostId: String = "unknown-host",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudMigrator {
    override suspend fun status(): MigrationStatus {
        val firstSettings: Settings = settings.observe().first()
        if (firstSettings.cloudMigrationComplete) return MigrationStatus.UpToDate

        val legacyManifests = listLegacyManifests()
        val identities = readIdentities()
        val userLibraryAlready =
            registry
                .lookup(TrackedTreeKind.UserLibrary, USER_LIBRARY_SCOPE_KEY) != null

        if (legacyManifests.isEmpty() && identities.isEmpty() && userLibraryAlready) {
            return MigrationStatus.UpToDate
        }
        return MigrationStatus.Pending(
            MigrationReport(
                projectTreesPending = identities.size,
                manifestsPending = legacyManifests.size,
                userLibraryPending = !userLibraryAlready,
            ),
        )
    }

    override fun migrate(): Flow<MigrationProgress> =
        channelFlow {
            try {
                send(MigrationProgress.Probing)
                val legacy = listLegacyManifests()
                val identities = readIdentities()

                send(MigrationProgress.Relocating(done = 0, total = legacy.size))
                val relocateError = relocateInParallel(legacy, this)
                if (relocateError != null) {
                    send(MigrationProgress.Failed(relocateError))
                    return@channelFlow
                }

                send(MigrationProgress.BuildingRegistry)
                // Single round-trip to seed the registry with every project + the UL entry.
                // Previously this was N writes (one per project) which dominated migration
                // time on accounts with hundreds of projects.
                val specs =
                    identities.map { identity ->
                        RegisterSpec(
                            kind = TrackedTreeKind.Project,
                            scopeKey = identity.uuid,
                            displayName = identity.name,
                            treeId = TrackedTreeId(identity.uuid),
                            ownerUserId = ownerUserId,
                            createdByHost = hostId,
                        )
                    } +
                        RegisterSpec(
                            kind = TrackedTreeKind.UserLibrary,
                            scopeKey = USER_LIBRARY_SCOPE_KEY,
                            displayName = "Ableton User Library",
                            treeId = TrackedTreeId(USER_LIBRARY_TREE_ID_PREFIX + hostId),
                            ownerUserId = ownerUserId,
                            createdByHost = hostId,
                        )
                val registered =
                    registry.registerAll(specs).getOrElse {
                        send(MigrationProgress.Failed("registry register failed: ${it.message}"))
                        return@channelFlow
                    }
                for (entry in registered) send(MigrationProgress.RegisteredTree(entry))

                settings.markCloudMigrationComplete()
                send(
                    MigrationProgress.Done(
                        report =
                            MigrationReport(
                                projectTreesPending = identities.size,
                                manifestsPending = legacy.size,
                                userLibraryPending = false,
                            ),
                        registry = registered,
                    ),
                )
            } catch (t: Throwable) {
                send(MigrationProgress.Failed(t.message ?: "migration failed"))
            }
        }.flowOn(ioDispatcher)

    /**
     * Copy each legacy manifest to its v=2 destination concurrently, [RELOCATE_CONCURRENCY]
     * at a time. Returns null on success, or a human-readable failure reason otherwise. The
     * outer flow surfaces it as [MigrationProgress.Failed]. Progress events are pushed onto
     * [progress] which the caller's [channelFlow] forwards to collectors —  `channelFlow`'s
     * `SendChannel` is thread-safe, unlike the bare `flow` builder's `FlowCollector`.
     */
    private suspend fun relocateInParallel(
        legacy: List<CloudDocRef>,
        progress: SendChannel<MigrationProgress>,
    ): String? {
        if (legacy.isEmpty()) return null
        val done = AtomicInteger(0)
        var failure: String? = null
        coroutineScope {
            legacy
                .chunked((legacy.size + RELOCATE_CONCURRENCY - 1) / RELOCATE_CONCURRENCY)
                .map { chunk ->
                    async {
                        for (item in chunk) {
                            if (failure != null) return@async
                            val parsed = parseLegacyKey(item.key.path) ?: continue
                            val destinationKey =
                                CloudDocKey(
                                    MigrationLayout.v2ManifestPrefix(
                                        treeId = TrackedTreeId(parsed.projectUuid),
                                        kind = TrackedTreeKind.Project,
                                    ) + parsed.fileName,
                                )
                            val read = cloud.readDoc(item.key) ?: continue
                            val write = cloud.writeDoc(destinationKey, expected = Generation.ZERO, bytes = read.bytes)
                            if (write.isFailure && write.exceptionOrNull() !is SketchbookError.Conflict) {
                                failure = "failed to copy ${item.key.path}: ${write.exceptionOrNull()?.message}"
                                return@async
                            }
                            val current = done.incrementAndGet()
                            progress.send(MigrationProgress.Relocating(done = current, total = legacy.size))
                        }
                    }
                }.awaitAll()
        }
        return failure
    }

    private suspend fun listLegacyManifests(): List<CloudDocRef> =
        runCatching { cloud.listDocs(CloudDocKey.Prefix(MigrationLayout.LEGACY_MANIFESTS_PREFIX)) }
            .getOrElse { emptyList() }
            .filter { parseLegacyKey(it.key.path) != null }

    private fun readIdentities(): List<ProjectIdentityRow> =
        catalog.catalogQueries
            .selectAllProjectIdentitiesWithName()
            .executeAsList()
            .map { ProjectIdentityRow(uuid = it.uuid, name = it.name) }

    private data class ProjectIdentityRow(val uuid: String, val name: String)

    private companion object {
        const val USER_LIBRARY_SCOPE_KEY: String = "default"

        /**
         * How many manifest copies run concurrently. Tuned conservatively — GCS can absorb
         * far more, but bumping this trades head-room for marginal throughput on the typical
         * "hundreds of manifests" account size.
         */
        const val RELOCATE_CONCURRENCY: Int = 8

        // For now the tree id is host-prefixed so each host's first migration produces a
        // distinct UL tree-id. Once shared User Library sync lands (post-v1.2) the migrator
        // will need to converge these — out of scope here.
        const val USER_LIBRARY_TREE_ID_PREFIX: String = "tt-ul-"

        /**
         * Parse `manifests/<project_uuid>/<rev-host-ts>.json`. Returns null when the path
         * doesn't match the legacy layout (e.g. someone uploaded a stray file).
         */
        fun parseLegacyKey(path: String): ParsedLegacy? {
            val parts = path.removePrefix(MigrationLayout.LEGACY_MANIFESTS_PREFIX).split('/')
            if (parts.size != 2) return null
            val (uuid, file) = parts
            if (uuid.isBlank() || file.isBlank()) return null
            return ParsedLegacy(projectUuid = uuid, fileName = file)
        }
    }

    private data class ParsedLegacy(val projectUuid: String, val fileName: String)
}
