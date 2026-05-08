package com.sketchbook.repo

import com.sketchbook.catalog.CatalogDb
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
import com.sketchbook.core.CollabRole
import com.sketchbook.core.Collaborator
import com.sketchbook.core.Manifest
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.core.UserId
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TreeRegistryTest {
    private val now = Instant.parse("2026-05-07T12:00:00Z")
    private val clock = FixedClock(now)

    @Test
    fun fetchOnEmptyBucketReturnsEmptySnapshotWithNullGeneration() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeDocCloud()
            val registry = CloudTreeRegistry(cloud, handle.catalog, clock, UserId.DEFAULT)
            val snapshot = registry.fetch()
            assertEquals(emptyList(), snapshot.entries)
            assertNull(snapshot.generation)
        }

    @Test
    fun registerWritesEntryAndUpdatesCache() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeDocCloud()
            val registry = CloudTreeRegistry(cloud, handle.catalog, clock, UserId.DEFAULT)

            val entry =
                registry
                    .register(
                        kind = TrackedTreeKind.Project,
                        scopeKey = "project-uuid-1",
                        displayName = "lofi-sketch",
                        treeId = TrackedTreeId("tt-01HZ3W"),
                        createdByHost = "host-a",
                    ).getOrThrow()

            assertEquals(TrackedTreeId("tt-01HZ3W"), entry.treeId)
            // Cloud doc is now present.
            val read = cloud.readDoc(TreeRegistry.REGISTRY_KEY)!!
            assertTrue(read.bytes.decodeToString().contains("tt-01HZ3W"))
            // Local cache reflects the entry.
            val looked = registry.lookup(TrackedTreeKind.Project, "project-uuid-1")
            assertNotNull(looked)
            assertEquals(TrackedTreeId("tt-01HZ3W"), looked.treeId)
        }

    @Test
    fun registerIsIdempotentForSameScope() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeDocCloud()
            val registry = CloudTreeRegistry(cloud, handle.catalog, clock, UserId.DEFAULT)

            val first =
                registry
                    .register(
                        kind = TrackedTreeKind.Project,
                        scopeKey = "p1",
                        displayName = "first",
                        treeId = TrackedTreeId("tt-1"),
                        createdByHost = "host-a",
                    ).getOrThrow()
            val second =
                registry
                    .register(
                        kind = TrackedTreeKind.Project,
                        scopeKey = "p1",
                        displayName = "second-attempt",
                        treeId = TrackedTreeId("tt-2"),
                        createdByHost = "host-a",
                    ).getOrThrow()
            // Same entry returned; no second tree minted.
            assertEquals(first.treeId, second.treeId)
            assertEquals("first", second.displayName)
        }

    @Test
    fun registerRetriesOnCasConflict() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeDocCloud(injectConflictsOnFirstWrite = 2)
            val registry = CloudTreeRegistry(cloud, handle.catalog, clock, UserId.DEFAULT)
            val entry =
                registry
                    .register(
                        kind = TrackedTreeKind.UserLibrary,
                        scopeKey = "default",
                        displayName = "User Library",
                        treeId = TrackedTreeId("tt-ul"),
                        createdByHost = "host-a",
                    ).getOrThrow()
            assertEquals(TrackedTreeId("tt-ul"), entry.treeId)
        }

    @Test
    fun canReadOwnerOnlyByDefault() {
        val handle = CatalogDb.openInMemory()
        val cloud = FakeDocCloud()
        val registry = CloudTreeRegistry(cloud, handle.catalog, clock, UserId.DEFAULT)
        val entry =
            TreeRegistryEntry(
                treeId = TrackedTreeId("t"),
                kind = TrackedTreeKind.Project,
                scopeKey = "x",
                displayName = "x",
                ownerUserId = UserId("alice"),
                createdAt = now,
                createdByHost = "h",
            )
        assertTrue(registry.canRead(entry, UserId("alice")))
        assertEquals(false, registry.canRead(entry, UserId("bob")))
    }

    @Test
    fun canWriteRespectsCollaboratorRole() {
        val handle = CatalogDb.openInMemory()
        val cloud = FakeDocCloud()
        val registry = CloudTreeRegistry(cloud, handle.catalog, clock, UserId.DEFAULT)
        val entry =
            TreeRegistryEntry(
                treeId = TrackedTreeId("t"),
                kind = TrackedTreeKind.Project,
                scopeKey = "x",
                displayName = "x",
                ownerUserId = UserId("alice"),
                collaborators =
                    listOf(
                        Collaborator(UserId("bob"), CollabRole.Read),
                        Collaborator(UserId("carol"), CollabRole.Write),
                    ),
                createdAt = now,
                createdByHost = "h",
            )
        assertTrue(registry.canWrite(entry, UserId("alice")))
        assertEquals(false, registry.canWrite(entry, UserId("bob")))
        assertTrue(registry.canWrite(entry, UserId("carol")))
        assertTrue(registry.canRead(entry, UserId("bob")))
    }

    @Test
    fun lookupRoundTripsCreatedAtAndCreatedByHost() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeDocCloud()
            val registry = CloudTreeRegistry(cloud, handle.catalog, clock, UserId.DEFAULT)

            val createdByHost = "studio-mac"
            val written =
                registry
                    .register(
                        kind = TrackedTreeKind.Project,
                        scopeKey = "p1",
                        displayName = "lofi",
                        treeId = TrackedTreeId("tt-x"),
                        createdByHost = createdByHost,
                    ).getOrThrow()

            val looked = registry.lookup(TrackedTreeKind.Project, "p1")
            assertNotNull(looked)
            // Cache hit must produce the same identity fields a fresh fetch would.
            assertEquals(written.createdAt, looked.createdAt)
            assertEquals(written.createdByHost, looked.createdByHost)
            assertEquals(createdByHost, looked.createdByHost)
            assertEquals(now, looked.createdAt)
        }

    @Test
    fun refreshCacheDropsPhantomEntries() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val cloud = FakeDocCloud()
            val registry = CloudTreeRegistry(cloud, handle.catalog, clock, UserId.DEFAULT)

            // Register two entries; both land in the cache.
            registry
                .register(
                    kind = TrackedTreeKind.Project,
                    scopeKey = "p-stale",
                    displayName = "stale",
                    treeId = TrackedTreeId("tt-stale"),
                    createdByHost = "host-a",
                ).getOrThrow()
            registry
                .register(
                    kind = TrackedTreeKind.Project,
                    scopeKey = "p-keep",
                    displayName = "keep",
                    treeId = TrackedTreeId("tt-keep"),
                    createdByHost = "host-a",
                ).getOrThrow()

            // Simulate "tt-stale removed upstream": rewrite the cloud doc to only contain tt-keep.
            val keepOnlyDoc =
                """
                {
                  "v": 1,
                  "owner_user_id": "${UserId.DEFAULT.value}",
                  "trees": [
                    {
                      "tree_id": "tt-keep",
                      "kind": "project",
                      "scope_key": "p-keep",
                      "display_name": "keep",
                      "owner_user_id": "${UserId.DEFAULT.value}",
                      "collaborators": [],
                      "created_at": "$now",
                      "created_by_host": "host-a"
                    }
                  ]
                }
                """.trimIndent().toByteArray()
            cloud.overwriteDoc(TreeRegistry.REGISTRY_KEY, keepOnlyDoc)

            registry.fetch()

            // Phantom entry no longer resolves; the surviving one still does.
            assertNull(registry.lookup(TrackedTreeKind.Project, "p-stale"))
            assertNotNull(registry.lookup(TrackedTreeKind.Project, "p-keep"))
        }
}

private class FixedClock(
    private val instant: Instant,
) : kotlin.time.Clock {
    override fun now(): Instant = instant
}

/** Minimal CloudBackend that only implements the CloudDoc API. Enough for TreeRegistry. */
private class FakeDocCloud(
    private var injectConflictsOnFirstWrite: Int = 0,
) : CloudBackend {
    private val docs = mutableMapOf<CloudDocKey, Pair<ByteArray, Generation>>()
    private var nextGen = 1L

    override suspend fun headBlob(
        hash: BlobHash,
        scope: BlobScope,
    ) = false

    override suspend fun putBlob(
        hash: BlobHash,
        source: RawSource,
        size: Long,
        scope: BlobScope,
    ) = error("n/a")

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
    ) = emptyList<ManifestRef>()

    override suspend fun appendManifestHead(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expectedHead: Generation?,
        manifest: Manifest,
    ) = Result.failure<Generation>(SketchbookError.Conflict("n/a"))

    override suspend fun acquireLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
    ) = LeaseAcquireResult.Acquired(Generation("1"))

    override suspend fun refreshLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
        expected: Generation,
    ) = LeaseRefreshResult.Refreshed(Generation("1"))

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
        if (injectConflictsOnFirstWrite > 0) {
            injectConflictsOnFirstWrite -= 1
            // Inject a stale read for the next attempt: bump the underlying doc out from under us.
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
        docs
            .filter { it.key.path.startsWith(prefix.value) }
            .map { CloudDocRef(it.key, it.value.second) }

    /**
     * Test helper: replace the doc at [key] with [bytes] and bump the generation. Used to
     * simulate an upstream rewrite (collab revoked, tree deleted) so the next fetch returns
     * a smaller entry set than the cache currently mirrors.
     */
    fun overwriteDoc(
        key: CloudDocKey,
        bytes: ByteArray,
    ) {
        docs[key] = bytes to Generation((nextGen++).toString())
    }
}
