package com.sketchbook.migration

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
import com.sketchbook.repo.CloudTreeRegistry
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.TreeRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class CloudMigratorTest {
    private val now = Instant.parse("2026-05-07T12:00:00Z")
    private val clock = FixedClock(now)
    private val hostId = "macstudio"

    @Test
    fun statusReturnsUpToDateWhenSettingsAlreadyComplete() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeMigratorCloud()
            val settings =
                FakeSettings(
                    initial = Settings(libraryRoots = emptyList(), selfContainedProjects = emptySet(), cloudMigrationComplete = true),
                )
            val migrator = newMigrator(cloud, handle.catalog, settings)
            assertEquals(MigrationStatus.UpToDate, migrator.status())
        }

    @Test
    fun statusReportsLegacyManifestsAndProjects() =
        runTest {
            val handle = CatalogDb.openInMemory()
            seedProject(handle.catalog, uuid = "p-1", name = "Sketch A")
            seedProject(handle.catalog, uuid = "p-2", name = "Sketch B")
            val cloud = FakeMigratorCloud()
            cloud.seedLegacyManifest("p-1", "00000001-host-ts.json")
            cloud.seedLegacyManifest("p-1", "00000002-host-ts.json")
            cloud.seedLegacyManifest("p-2", "00000001-host-ts.json")
            val settings = FakeSettings()
            val migrator = newMigrator(cloud, handle.catalog, settings)

            val status = migrator.status()
            assertTrue(status is MigrationStatus.Pending)
            val report = (status as MigrationStatus.Pending).report
            assertEquals(3, report.manifestsPending)
            assertEquals(2, report.projectTreesPending)
            assertTrue(report.userLibraryPending)
        }

    @Test
    fun migrateRelocatesManifestsAndRegistersUserLibrary() =
        runTest {
            val handle = CatalogDb.openInMemory()
            seedProject(handle.catalog, uuid = "p-1", name = "Sketch A")
            val cloud = FakeMigratorCloud()
            cloud.seedLegacyManifest("p-1", "00000001-host-ts.json")
            cloud.seedLegacyManifest("p-1", "00000002-host-ts.json")
            val settings = FakeSettings()
            val migrator = newMigrator(cloud, handle.catalog, settings)

            val events = migrator.migrate().toList()

            // Terminal event is Done.
            val done = events.last()
            assertTrue(done is MigrationProgress.Done)
            done as MigrationProgress.Done

            // Both manifests landed at the v=2 destination.
            val v2P1 = MigrationLayout.v2ManifestPrefix(TrackedTreeId("p-1"), TrackedTreeKind.Project)
            assertNotNull(cloud.readDoc(CloudDocKey(v2P1 + "00000001-host-ts.json")))
            assertNotNull(cloud.readDoc(CloudDocKey(v2P1 + "00000002-host-ts.json")))

            // Registry now contains the project + UserLibrary.
            val kinds = done.registry.map { it.kind }
            assertTrue(TrackedTreeKind.Project in kinds)
            assertTrue(TrackedTreeKind.UserLibrary in kinds)

            // Settings flag flipped.
            assertTrue(settings.observe().first().cloudMigrationComplete)
        }

    @Test
    fun migrateIsIdempotentOnPartiallyMigratedBucket() =
        runTest {
            val handle = CatalogDb.openInMemory()
            seedProject(handle.catalog, uuid = "p-1", name = "Sketch A")
            val cloud = FakeMigratorCloud()
            // Pre-populate the destination as if a prior run already moved it.
            val v2P1 = MigrationLayout.v2ManifestPrefix(TrackedTreeId("p-1"), TrackedTreeKind.Project)
            cloud.writeDoc(CloudDocKey(v2P1 + "00000001-host-ts.json"), expected = Generation.ZERO, bytes = "v2-bytes".encodeToByteArray())
            // Plus a stale legacy copy still present (idempotency: skip on conflict, don't fail).
            cloud.seedLegacyManifest("p-1", "00000001-host-ts.json")

            val settings = FakeSettings()
            val migrator = newMigrator(cloud, handle.catalog, settings)
            val events = migrator.migrate().toList()

            assertTrue(events.last() is MigrationProgress.Done)
            // Destination keeps the original v=2 bytes (write-with-must-not-exist failed → skipped).
            val read = cloud.readDoc(CloudDocKey(v2P1 + "00000001-host-ts.json"))!!
            assertEquals("v2-bytes", read.bytes.decodeToString())
        }

    @Test
    fun migrateMarksCompleteEvenWithNoLegacyManifests() =
        runTest {
            // Fresh user, empty bucket: migrator still seeds the registry + UL entry and
            // marks the settings flag so subsequent launches don't re-prompt.
            val handle = CatalogDb.openInMemory()
            val cloud = FakeMigratorCloud()
            val settings = FakeSettings()
            val migrator = newMigrator(cloud, handle.catalog, settings)

            val events = migrator.migrate().toList()

            assertTrue(events.last() is MigrationProgress.Done)
            assertTrue(settings.observe().first().cloudMigrationComplete)
        }

    @Test
    fun migrateRegistersUserLibraryOnlyOnceAcrossReruns() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeMigratorCloud()
            val settings = FakeSettings()
            val migrator = newMigrator(cloud, handle.catalog, settings)

            migrator.migrate().toList()

            // Reset migration_complete flag (simulates the migrator running again — e.g. dev reset).
            settings.update { it.copy(cloudMigrationComplete = false) }
            val events = migrator.migrate().toList()

            val done = events.last() as MigrationProgress.Done
            val ulEntries = done.registry.filter { it.kind == TrackedTreeKind.UserLibrary }
            assertEquals(1, ulEntries.size)
        }

    // ---- helpers --------------------------------------------------------------------------------

    private fun newMigrator(
        cloud: CloudBackend,
        catalog: Catalog,
        settings: SettingsRepository,
    ): CloudMigrator {
        val registry: TreeRegistry = CloudTreeRegistry(cloud, catalog, clock, UserId.DEFAULT)
        return JvmCloudMigrator(
            cloud = cloud,
            catalog = catalog,
            registry = registry,
            settings = settings,
            ownerUserId = UserId.DEFAULT,
            hostId = hostId,
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
}

private class FixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

/**
 * Minimal in-memory [SettingsRepository] for the migrator tests. Lets the test seed an
 * initial Settings and assert the migrator's `markCloudMigrationComplete` side-effect.
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

    fun update(transform: (Settings) -> Settings) {
        state.value = transform(state.value)
    }

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

    override suspend fun markCloudMigrationComplete(): Result<Unit> {
        state.value = state.value.copy(cloudMigrationComplete = true)
        return Result.success(Unit)
    }
}

/**
 * Minimal CloudBackend that only stores documents — manifests are written + read via the
 * doc API, which is exactly the surface the migrator uses.
 */
private class FakeMigratorCloud : CloudBackend {
    private val docs = mutableMapOf<CloudDocKey, Pair<ByteArray, Generation>>()
    private var nextGen = 1L

    fun seedLegacyManifest(
        uuid: String,
        fileName: String,
    ) {
        val key = CloudDocKey("manifests/$uuid/$fileName")
        docs[key] = "legacy-bytes".encodeToByteArray() to Generation((nextGen++).toString())
    }

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
        val current = docs[key]
        if (expected != null) {
            if (expected == Generation.ZERO) {
                if (current != null) return Result.failure(SketchbookError.Conflict("exists at ${key.path}"))
            } else if (current == null || current.second != expected) {
                return Result.failure(SketchbookError.Conflict("gen mismatch at ${key.path}"))
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
