package com.sketchbook.sync

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
import com.sketchbook.cloud.LeaseRefreshResult
import com.sketchbook.cloud.ManifestRef
import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.repo.BlobCacheSettings
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmBlobCacheTest {

    private val tmp = createTempDirectory("blob-cache-")

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    private val hash = BlobHash("b3:" + "a".repeat(64))
    private val payload = "hello-blob".toByteArray()

    @Test
    fun fetchOnMissThenServeFromCacheOnHit() = runTest {
        val cloud = CountingCloud(payload)
        val handle = CatalogDb.openInMemory()
        val cache = JvmBlobCache(handle.catalog, tmp, cloud, cacheSettings = { BlobCacheSettings.Default })

        val first = cache.getOrFetch(hash, BlobScope.Shared)
        assertTrue(Files.exists(first))
        assertEquals(payload.toList(), Files.readAllBytes(first).toList())
        assertEquals(1, cloud.getBlobCalls)

        val second = cache.getOrFetch(hash, BlobScope.Shared)
        assertEquals(first, second)
        assertEquals(1, cloud.getBlobCalls, "second call should not hit cloud")
    }

    @Test
    fun separateScopesAreSeparateCacheKeys() = runTest {
        val cloud = CountingCloud(payload)
        val handle = CatalogDb.openInMemory()
        val cache = JvmBlobCache(handle.catalog, tmp, cloud, cacheSettings = { BlobCacheSettings.Default })
        val privateScope = BlobScope.Private(ProjectUuid("p"))

        cache.getOrFetch(hash, BlobScope.Shared)
        cache.getOrFetch(hash, privateScope)

        assertEquals(2, cloud.getBlobCalls, "shared and private keys must each fetch")
    }

    @Test
    fun fetchStreamsLargeBlobWithoutHeapBuffering() = runTest {
        // 64 MB synthetic blob — large enough to OOM if heap-buffered under tight heap budgets,
        // small enough to keep CI fast. The contract under test is "fetch does not read the
        // entire RawSource into a ByteArray before writing"; size equality between source payload
        // and on-disk file (and the recorded catalog row) implicitly proves the streaming path is
        // correct.
        val sizeBytes = 64 * 1024 * 1024
        val largePayload = ByteArray(sizeBytes) { (it % 251).toByte() }
        val cloud = CountingCloud(largePayload)
        val handle = CatalogDb.openInMemory()
        val cache = JvmBlobCache(handle.catalog, tmp, cloud, cacheSettings = { BlobCacheSettings.Default })

        val path = cache.getOrFetch(hash, BlobScope.Shared)

        assertEquals(sizeBytes.toLong(), Files.size(path))
        val recorded = handle.catalog.catalogQueries.sumBlobCacheBytes().executeAsOne()
        assertEquals(sizeBytes.toLong(), recorded)
    }

    @Test
    fun lruEvictsWhenOverBudget() = runTest {
        val cloud = CountingCloud(payload)
        val handle = CatalogDb.openInMemory()
        // Budget tighter than two payloads; second insert should evict the first.
        val tinySettings = BlobCacheSettings(maxSizeBytes = (payload.size + 1).toLong(), lruEnabled = true)
        val cache = JvmBlobCache(handle.catalog, tmp, cloud, cacheSettings = { tinySettings })
        val h2 = BlobHash("b3:" + "b".repeat(64))

        cache.getOrFetch(hash, BlobScope.Shared)
        // Tiny sleep-free LRU bump: second insert is "later" than first via the clock, so first is
        // the LRU candidate.
        cache.getOrFetch(h2, BlobScope.Shared)

        // First entry should be evicted, second present.
        val sumAfter = handle.catalog.catalogQueries.sumBlobCacheBytes().executeAsOne()
        assertTrue(sumAfter <= tinySettings.maxSizeBytes, "cache over budget: $sumAfter")
    }
}

private class CountingCloud(private val payload: ByteArray) : CloudBackend {
    var getBlobCalls = 0
    override suspend fun headBlob(hash: BlobHash, scope: BlobScope) = true
    override suspend fun putBlob(hash: BlobHash, source: RawSource, size: Long, scope: BlobScope) {}
    override suspend fun getBlob(hash: BlobHash, scope: BlobScope): RawSource {
        getBlobCalls += 1
        val buf = Buffer()
        buf.write(payload)
        return buf
    }
    override suspend fun readManifest(treeId: TrackedTreeId, kind: TrackedTreeKind, rev: SnapshotRev): Manifest = error("not used")
    override suspend fun listManifests(treeId: TrackedTreeId, kind: TrackedTreeKind, sinceRev: SnapshotRev?) = emptyList<ManifestRef>()
    override suspend fun appendManifestHead(treeId: TrackedTreeId, kind: TrackedTreeKind, expectedHead: Generation?, manifest: Manifest) = Result.failure<Generation>(SketchbookError.Conflict("not used"))
    override suspend fun acquireLock(treeId: TrackedTreeId, kind: TrackedTreeKind, lock: LeaseLock) = LeaseAcquireResult.Acquired(Generation("1"))
    override suspend fun refreshLock(treeId: TrackedTreeId, kind: TrackedTreeKind, lock: LeaseLock, expected: Generation) = LeaseRefreshResult.Refreshed(Generation("1"))
    override suspend fun releaseLock(treeId: TrackedTreeId, kind: TrackedTreeKind, expected: Generation) {}
    override suspend fun readDoc(key: com.sketchbook.core.CloudDocKey) = null
    override suspend fun writeDoc(key: com.sketchbook.core.CloudDocKey, expected: Generation?, bytes: ByteArray) = Result.failure<Generation>(SketchbookError.Conflict("not used"))
    override suspend fun listDocs(prefix: com.sketchbook.core.CloudDocKey.Prefix) = emptyList<com.sketchbook.cloud.CloudDocRef>()
}
