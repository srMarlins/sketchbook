package com.sketchbook.desktop.bootstrap

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
import com.sketchbook.core.UserId
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.CloudMachineProfileStore
import com.sketchbook.repo.CloudTreeRegistry
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * BootstrapData replaces the deleted v=1→v=2 migrator. The tests pin its three load-bearing
 * invariants:
 *
 *  1. First launch (`registrySeeded = false`) seeds the registry with one entry per local
 *     project + the User Library tree, then flips the settings flag.
 *  2. Subsequent launches (`registrySeeded = true`) skip the registerAll round-trip and only
 *     refresh the local cache via fetch().
 *  3. Errors propagate as `Result.failure(...)` carrying the underlying [Throwable]; the
 *     settings flag stays unchanged so the next launch retries.
 */
class BootstrapDataTest {
    private val now = Instant.parse("2026-05-07T12:00:00Z")
    private val clock = FixedClock(now)
    private val hostId = HostId("studio-mac")

    @Test
    fun seedRegistryRegistersProjectsAndUserLibraryOnFirstLaunch() =
        runTest {
            val handle = CatalogDb.openInMemory()
            seedProject(handle.catalog, uuid = "p-1", name = "Sketch A")
            seedProject(handle.catalog, uuid = "p-2", name = "Sketch B")
            val cloud = FakeBootstrapCloud()
            val settings = FakeSettings()
            val data = newBootstrapData(handle.catalog, cloud, settings)

            val entries = data.seedRegistry()

            // Project rows + UL = 3 entries.
            val byKey = entries.associateBy { it.kind to it.scopeKey }
            assertTrue((TrackedTreeKind.Project to "p-1") in byKey, "missing p-1 entry")
            assertTrue((TrackedTreeKind.Project to "p-2") in byKey, "missing p-2 entry")
            assertTrue((TrackedTreeKind.UserLibrary to "default") in byKey, "missing UL entry")
            // UL tree-id is host-prefixed.
            assertEquals(
                TrackedTreeId("tt-ul-${hostId.value}"),
                byKey.getValue(TrackedTreeKind.UserLibrary to "default").treeId,
            )
            // Flag flipped → next launch is a no-op.
            assertTrue(settings.observe().first().registrySeeded)
        }

    @Test
    fun seedRegistryIsNoOpWhenAlreadySeeded() =
        runTest {
            val handle = CatalogDb.openInMemory()
            seedProject(handle.catalog, uuid = "p-1", name = "Sketch A")
            val cloud = FakeBootstrapCloud()
            val settings =
                FakeSettings(
                    initial =
                        Settings(
                            libraryRoots = emptyList(),
                            selfContainedProjects = emptySet(),
                            cacheSettings = BlobCacheSettings.Default,
                            registrySeeded = true,
                        ),
                )
            val data = newBootstrapData(handle.catalog, cloud, settings)

            // First call seeds nothing (no writes to the cloud); second call also no-op.
            data.seedRegistry()
            data.seedRegistry()

            // Cloud doc never created — fetch returns empty.
            assertEquals(0, cloud.writeCallsTo(REGISTRY_KEY))
            assertTrue(settings.observe().first().registrySeeded)
        }

    @Test
    fun seedRegistryFailureKeepsFlagUnsetSoNextLaunchRetries() =
        runTest {
            val handle = CatalogDb.openInMemory()
            seedProject(handle.catalog, uuid = "p-1", name = "Sketch A")
            val cloud = FakeBootstrapCloud(failOnRegistryWrite = true)
            val settings = FakeSettings()
            val data = newBootstrapData(handle.catalog, cloud, settings)

            // seedRegistry now throws on irrecoverable failure (per #128).
            var thrown: Throwable? = null
            try {
                data.seedRegistry()
            } catch (t: Throwable) {
                thrown = t
            }
            assertNotNull(thrown, "expected seedRegistry to throw when registry write fails")
            // Flag still false → subsequent launches will retry the seed.
            assertEquals(false, settings.observe().first().registrySeeded)
        }

    private fun newBootstrapData(
        catalog: Catalog,
        cloud: CloudBackend,
        settings: SettingsRepository,
    ): BootstrapData {
        val registry = CloudTreeRegistry(cloud, catalog, clock, UserId.DEFAULT)
        val profile = CloudMachineProfileStore(cloud, catalog, clock, kotlinx.coroutines.Dispatchers.Unconfined)
        return BootstrapData(
            registry = registry,
            profile = profile,
            settings = settings,
            catalog = catalog,
            ownerUserId = UserId.DEFAULT,
            hostId = hostId,
            ioDispatcher = Dispatchers.Unconfined,
            clock = clock,
        )
    }

    private fun seedProject(
        catalog: Catalog,
        uuid: String,
        name: String,
    ) {
        catalog.transaction {
            catalog.catalogQueries.insertProject(
                path = "/tmp/$name.als",
                name = name,
                parent_dir = "/tmp",
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
                    .selectProjectIdByPath(path = "/tmp/$name.als")
                    .executeAsOne()
            catalog.catalogQueries.upsertProjectIdentity(
                project_id = pid,
                uuid = uuid,
                created_at = "2026-01-01T00:00:00Z",
            )
        }
    }

    private companion object {
        val REGISTRY_KEY: CloudDocKey = CloudDocKey("registry.json")
    }
}

private class FixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

/**
 * In-memory [SettingsRepository] for bootstrap tests. Lets the test seed an initial Settings
 * (e.g. `registrySeeded = true` for the no-op case) and assert the
 * `markRegistrySeeded` side-effect.
 */
private class FakeSettings(
    initial: Settings =
        Settings(
            libraryRoots = emptyList(),
            selfContainedProjects = emptySet(),
            cacheSettings = BlobCacheSettings.Default,
        ),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<Settings> = state

    override suspend fun upsertRoot(root: LibraryRoot): Result<Unit> = Result.success(Unit)

    override suspend fun removeRoot(root: LibraryRoot): Result<Unit> = Result.success(Unit)

    override suspend fun setCloudBucket(bucket: String?): Result<Unit> = Result.success(Unit)

    override suspend fun setSelfContained(
        uuid: com.sketchbook.core.ProjectUuid,
        value: Boolean,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun setCacheSettings(settings: BlobCacheSettings): Result<Unit> = Result.success(Unit)

    override suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags): Result<Unit> = Result.success(Unit)

    override suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind): Result<Unit> = Result.success(Unit)

    override suspend fun setPluginFolders(folders: List<String>): Result<Unit> = Result.success(Unit)

    override suspend fun resetFirstRun(): Result<Unit> = Result.success(Unit)

    override suspend fun markRegistrySeeded(): Result<Unit> {
        state.value = state.value.copy(registrySeeded = true)
        return Result.success(Unit)
    }
}

/**
 * Minimal CloudBackend covering only the CloudDoc surface BootstrapData needs (registry +
 * machine roster + plugin manifests). Tracks per-key write counts so tests can assert the
 * "no-op when seeded" path performed zero writes to the registry doc.
 */
private class FakeBootstrapCloud(
    private val failOnRegistryWrite: Boolean = false,
) : CloudBackend {
    private val docs = mutableMapOf<CloudDocKey, Pair<ByteArray, Generation>>()
    private val writeCounts = mutableMapOf<CloudDocKey, Int>()
    private var nextGen = 1L

    fun writeCallsTo(key: CloudDocKey): Int = writeCounts[key] ?: 0

    override suspend fun headBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): Boolean = false

    override suspend fun putBlob(
        hash: BlobHash,
        source: RawSource,
        size: Long,
        scope: BlobScope,
    ) = Unit

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
    ) = Unit

    override suspend fun readDoc(key: CloudDocKey): CloudDocRead? {
        val d = docs[key] ?: return null
        return CloudDocRead(d.first, d.second)
    }

    override suspend fun writeDoc(
        key: CloudDocKey,
        expected: Generation?,
        bytes: ByteArray,
    ): Result<Generation> {
        writeCounts[key] = (writeCounts[key] ?: 0) + 1
        if (failOnRegistryWrite && key.path == "registry.json") {
            return Result.failure(
                SketchbookError.RemoteFailure(status = 500, body = null, message = "boom"),
            )
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
        docs
            .filter { it.key.path.startsWith(prefix.value) }
            .map { CloudDocRef(it.key, it.value.second) }
}
