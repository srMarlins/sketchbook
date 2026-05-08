package com.sketchbook.repo

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.CloudDocRead
import com.sketchbook.cloud.CloudDocRef
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
import com.sketchbook.cloud.LeaseRefreshResult
import com.sketchbook.cloud.ManifestRef
import com.sketchbook.core.BlobHash
import com.sketchbook.core.CloudDocKey
import com.sketchbook.core.Manifest
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class MachineProfileStoreTest {
    private val now = Instant.parse("2026-05-07T12:00:00Z")
    private val clock = FixedClock2(now)

    @Test
    fun publishHostSliceWritesUnionOfBothTables() =
        runTest {
            val handle = CatalogDb.openInMemory()
            seedProjectPlugin(handle.catalog, name = "Serum", type = "vst3", installed = true)
            seedProjectPlugin(handle.catalog, name = "FabFilter Pro-Q 3", type = "au", installed = false)
            seedUserLibraryPlugin(handle.catalog, name = "Serum", type = "vst3", installed = false)
            seedUserLibraryPlugin(handle.catalog, name = "Diva", type = "vst3", installed = true)

            val cloud = FakeProfileCloud()
            val store = CloudMachineProfileStore(cloud, handle.catalog, clock)

            val slice = store.publishHostSlice("macstudio", "Mac Studio", os = "darwin").getOrThrow()

            // Three distinct (name, format): Serum, FabFilter, Diva. Serum's installed flag
            // should be true (project_plugins reported it installed; UL row didn't, but OR wins).
            val byName = slice.plugins.associateBy { it.name }
            assertEquals(setOf("Serum", "FabFilter Pro-Q 3", "Diva"), byName.keys)
            assertTrue(byName["Serum"]!!.installed)
            assertTrue(!byName["FabFilter Pro-Q 3"]!!.installed)
            assertTrue(byName["Diva"]!!.installed)

            // Wrote to the host-sliced cloud key.
            val read = cloud.readDoc(MachineProfileStore.pluginManifestKey("macstudio"))
            assertNotNull(read)
        }

    @Test
    fun composeUnionAcrossHostsOrsInstalledFlag() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeProfileCloud()
            val json =
                Json {
                    encodeDefaults = true
                    ignoreUnknownKeys = true
                    prettyPrint = false
                }

            // Pre-seed two host slices. Mac has Serum installed; Windows does not.
            cloud.writeDoc(
                MachineProfileStore.pluginManifestKey("mac"),
                expected = Generation.ZERO,
                bytes =
                    json
                        .encodeToString(
                            HostPluginManifest.serializer(),
                            HostPluginManifest(
                                hostId = "mac",
                                hostName = "Mac",
                                os = "darwin",
                                computedAt = now,
                                plugins = listOf(HostPluginEntry("Serum", "vst3", installed = true)),
                            ),
                        ).encodeToByteArray(),
            )
            cloud.writeDoc(
                MachineProfileStore.pluginManifestKey("win"),
                expected = Generation.ZERO,
                bytes =
                    json
                        .encodeToString(
                            HostPluginManifest.serializer(),
                            HostPluginManifest(
                                hostId = "win",
                                hostName = "Win",
                                os = "windows",
                                computedAt = now,
                                plugins =
                                    listOf(
                                        HostPluginEntry("Serum", "vst3", installed = false),
                                        HostPluginEntry("Diva", "vst3", installed = false),
                                    ),
                            ),
                        ).encodeToByteArray(),
            )

            val store = CloudMachineProfileStore(cloud, handle.catalog, clock)
            val unioned = store.composeUnion()

            assertEquals(2, unioned.perHost.size)
            val byName = unioned.union.associateBy { it.name }
            assertEquals(true, byName["Serum"]!!.installed)
            assertEquals(false, byName["Diva"]!!.installed)
        }

    @Test
    fun registerMachineRetriesOnCasConflict() =
        runTest {
            val handle = CatalogDb.openInMemory()
            // Inject one CAS conflict on machines.json so registerMachine has to retry.
            val cloud = FakeProfileCloud(machinesConflicts = 1)
            val store = CloudMachineProfileStore(cloud, handle.catalog, clock)

            val result =
                store.registerMachine(
                    MachineEntry(
                        hostId = "macstudio",
                        hostName = "Mac Studio",
                        os = "darwin",
                        lastSeenAt = now,
                        binaryVersion = "0.4.0",
                    ),
                )

            assertTrue(result.isSuccess, "expected success after retry, got: ${result.exceptionOrNull()}")
            val machines = store.listMachines()
            assertEquals(1, machines.size)
            assertEquals("macstudio", machines.single().hostId)
        }

    @Test
    fun registerMachineUpdatesExistingHostInPlace() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeProfileCloud()
            val store = CloudMachineProfileStore(cloud, handle.catalog, clock)

            store
                .registerMachine(
                    MachineEntry("macstudio", "Mac Studio", "darwin", now, "0.4.0"),
                ).getOrThrow()
            store
                .registerMachine(
                    MachineEntry("macstudio", "Mac Studio (renamed)", "darwin", now, "0.5.0"),
                ).getOrThrow()

            val machines = store.listMachines()
            assertEquals(1, machines.size)
            assertEquals("Mac Studio (renamed)", machines.single().hostName)
            assertEquals("0.5.0", machines.single().binaryVersion)
        }

    // ----- helpers --------------------------------------------------------------------------

    private fun seedProjectPlugin(
        catalog: Catalog,
        name: String,
        type: String,
        installed: Boolean,
    ) {
        catalog.catalogQueries.insertProject(
            path = "/lib/$name.als",
            name = name,
            parent_dir = "/lib",
            tempo = null,
            time_sig_num = null,
            time_sig_den = null,
            key = null,
            track_count = 0L,
            audio_tracks = 0L,
            midi_tracks = 0L,
            return_tracks = 0L,
            live_version = null,
            last_modified = 0.0,
            last_scanned = 0.0,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0L,
            effort_score = 0L,
            effort_breakdown = null,
            file_size_bytes = 0L,
        )
        val pid =
            catalog.catalogQueries
                .selectProjectIdByPath("/lib/$name.als")
                .executeAsOne()
        catalog.catalogQueries.insertProjectPlugin(
            project_id = pid,
            plugin_name = name,
            plugin_type = type,
            track_name = null,
        )
        // project_plugins defaults is_installed=1, so explicitly mark when the test wants
        // not-installed (the presence probe normally flips this).
        catalog.catalogQueries.markPluginsInstalledByNameAndType(
            is_installed = if (installed) 1L else 0L,
            plugin_name = name,
            plugin_type = type,
        )
    }

    private fun seedUserLibraryPlugin(
        catalog: Catalog,
        name: String,
        type: String,
        installed: Boolean,
    ) {
        // Ensure the parent tree_registry_cache row exists once per test — required by the
        // FK that 11.sqm added to user_library_plugins.tree_id. Idempotent: every UL row in
        // these tests uses the same tt-ul-test parent.
        if (!ulParentSeeded) {
            catalog.catalogQueries.upsertTreeRegistryEntry(
                tree_id = "tt-ul-test",
                tree_kind = "user_library",
                scope_key = "default",
                display_name = "User Library",
                owner_user_id = "DEFAULT",
                collaborators_json = "[]",
                created_at = 0L,
                created_by_host = "test-host",
                updated_at = 0L,
            )
            ulParentSeeded = true
        }
        catalog.catalogQueries.upsertUserLibraryPlugin(
            tree_id = "tt-ul-test",
            rel_path = "Templates/Live Set.als",
            plugin_name = name,
            plugin_type = type,
            is_installed = if (installed) 1L else 0L,
            last_seen_at = 0L,
        )
    }

    private var ulParentSeeded = false
}

private class FixedClock2(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

private class FakeProfileCloud(
    private var machinesConflicts: Int = 0,
) : CloudBackend {
    private val docs = mutableMapOf<CloudDocKey, Pair<ByteArray, Generation>>()
    private var nextGen = 1L

    override suspend fun headBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): Boolean = false

    override suspend fun putBlob(
        hash: BlobHash,
        source: RawSource,
        size: Long,
        scope: BlobScope,
    ) {}

    override suspend fun getBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): RawSource = error("n/a")

    override suspend fun readManifest(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        rev: SnapshotRev,
    ): Manifest = error("n/a")

    override suspend fun listManifests(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        sinceRev: SnapshotRev?,
    ): List<ManifestRef> = emptyList()

    override suspend fun appendManifestHead(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expectedHead: Generation?,
        manifest: Manifest,
    ): Result<Generation> = Result.failure(SketchbookError.Conflict("n/a"))

    override suspend fun acquireLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
    ): LeaseAcquireResult = LeaseAcquireResult.Acquired(Generation("1"))

    override suspend fun refreshLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
        expected: Generation,
    ): LeaseRefreshResult = LeaseRefreshResult.Refreshed(Generation("1"))

    override suspend fun releaseLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expected: Generation,
    ) {}

    override suspend fun readDoc(key: CloudDocKey): CloudDocRead? {
        val d = docs[key] ?: return null
        return CloudDocRead(d.first, d.second)
    }

    override suspend fun writeDoc(
        key: CloudDocKey,
        expected: Generation?,
        bytes: ByteArray,
    ): Result<Generation> {
        // Conflict-injection target: the machines roster doc. Useful for testing CAS-retry
        // behavior without a contentious second writer.
        if (key == MachineProfileStore.MACHINES_KEY && machinesConflicts > 0) {
            machinesConflicts -= 1
            // Bump the underlying generation to simulate someone else's write landing first.
            val gen = Generation((nextGen++).toString())
            docs[key] = bytes to gen
            return Result.failure(SketchbookError.Conflict("test-injected"))
        }
        val current = docs[key]
        if (expected != null) {
            if (expected == Generation.ZERO) {
                if (current != null) return Result.failure(SketchbookError.Conflict("exists"))
            } else if (current == null || current.second != expected) {
                return Result.failure(SketchbookError.Conflict("gen mismatch"))
            }
        }
        val gen = Generation((nextGen++).toString())
        docs[key] = bytes to gen
        return Result.success(gen)
    }

    override suspend fun listDocs(prefix: CloudDocKey.Prefix): List<CloudDocRef> =
        docs.keys
            .filter { it.path.startsWith(prefix.value) }
            .map { CloudDocRef(it, docs[it]!!.second) }
}
