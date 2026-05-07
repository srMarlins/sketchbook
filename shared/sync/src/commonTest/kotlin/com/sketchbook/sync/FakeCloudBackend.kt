package com.sketchbook.sync

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
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * In-memory [CloudBackend] for unit tests. Tracks blobs by hash, manifests per tree, and a
 * single `head` per tree. Conditional writes use a synthetic monotonically increasing
 * generation counter.
 */
class FakeCloudBackend : CloudBackend {
    private val blobs = mutableMapOf<BlobKey, ByteArray>()
    private val manifests = mutableMapOf<TreeKey, MutableList<StoredManifest>>()
    private val locks = mutableMapOf<TreeKey, StoredLock>()
    private val docs = mutableMapOf<CloudDocKey, StoredDoc>()
    private var generationCounter: Long = 1

    private data class StoredDoc(
        val bytes: ByteArray,
        val generation: Generation,
    )

    private data class StoredManifest(
        val ref: ManifestRef,
        val manifest: Manifest,
    )

    private data class StoredLock(
        val lock: LeaseLock,
        val generation: Generation,
    )

    private data class BlobKey(
        val scope: BlobScope,
        val hash: BlobHash,
    )

    private data class TreeKey(
        val treeId: TrackedTreeId,
        val kind: TrackedTreeKind,
    )

    fun nextGeneration(): Generation = Generation((generationCounter++).toString())

    override suspend fun headBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): Boolean = blobs.containsKey(BlobKey(scope, hash))

    override suspend fun putBlob(
        hash: BlobHash,
        source: RawSource,
        size: Long,
        scope: BlobScope,
    ) {
        val key = BlobKey(scope, hash)
        if (blobs.containsKey(key)) return
        val bytes = source.buffered().readByteArray()
        blobs[key] = bytes
    }

    override suspend fun getBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): RawSource = error("not used in these tests")

    override suspend fun readManifest(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        rev: SnapshotRev,
    ): Manifest {
        val list =
            manifests[TreeKey(treeId, kind)]
                ?: throw SketchbookError.NotFound("no manifests for tree=${treeId.value}")
        return list.firstOrNull { it.manifest.rev == rev }?.manifest
            ?: throw SketchbookError.NotFound("rev $rev not found")
    }

    override suspend fun listManifests(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        sinceRev: SnapshotRev?,
    ): List<ManifestRef> {
        val list = manifests[TreeKey(treeId, kind)] ?: return emptyList()
        return list.map { it.ref }.filter {
            sinceRev == null || it.rev > sinceRev.value
        }
    }

    override suspend fun appendManifestHead(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expectedHead: Generation?,
        manifest: Manifest,
    ): Result<Generation> {
        val list = manifests.getOrPut(TreeKey(treeId, kind)) { mutableListOf() }
        val currentHead = list.lastOrNull()
        if (expectedHead != null) {
            if (expectedHead == Generation.ZERO) {
                if (currentHead != null) return Result.failure(SketchbookError.Conflict("HEAD exists"))
            } else if (currentHead == null || currentHead.ref.generation != expectedHead) {
                return Result.failure(SketchbookError.Conflict("HEAD generation mismatch"))
            }
        }
        val gen = nextGeneration()
        val ref =
            ManifestRef(
                rev = manifest.rev.value,
                path = "manifests/${treeId.value}/${manifest.rev.value.toString().padStart(8, '0')}-${manifest.timestamp}.json",
                generation = gen,
            )
        list += StoredManifest(ref, manifest)
        return Result.success(gen)
    }

    override suspend fun acquireLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
    ): LeaseAcquireResult {
        val key = TreeKey(treeId, kind)
        val existing = locks[key]
        if (existing != null) {
            return LeaseAcquireResult.Held(existing.lock, existing.generation)
        }
        val gen = nextGeneration()
        locks[key] = StoredLock(lock, gen)
        return LeaseAcquireResult.Acquired(gen)
    }

    override suspend fun refreshLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
        expected: Generation,
    ): LeaseRefreshResult {
        val key = TreeKey(treeId, kind)
        val existing = locks[key] ?: return LeaseRefreshResult.Stale
        if (existing.generation != expected) return LeaseRefreshResult.Stale
        val gen = nextGeneration()
        locks[key] = StoredLock(lock, gen)
        return LeaseRefreshResult.Refreshed(gen)
    }

    override suspend fun releaseLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expected: Generation,
    ) {
        val key = TreeKey(treeId, kind)
        val existing = locks[key] ?: return
        if (existing.generation == expected) locks.remove(key)
    }

    override suspend fun readDoc(key: CloudDocKey): CloudDocRead? {
        val doc = docs[key] ?: return null
        return CloudDocRead(doc.bytes, doc.generation)
    }

    override suspend fun writeDoc(
        key: CloudDocKey,
        expected: Generation?,
        bytes: ByteArray,
    ): Result<Generation> {
        val existing = docs[key]
        if (expected != null) {
            if (expected == Generation.ZERO) {
                if (existing != null) return Result.failure(SketchbookError.Conflict("doc exists at ${key.path}"))
            } else if (existing == null || existing.generation != expected) {
                return Result.failure(SketchbookError.Conflict("doc generation mismatch at ${key.path}"))
            }
        }
        val gen = nextGeneration()
        docs[key] = StoredDoc(bytes, gen)
        return Result.success(gen)
    }

    override suspend fun listDocs(prefix: CloudDocKey.Prefix): List<CloudDocRef> =
        docs
            .filter { it.key.path.startsWith(prefix.value) }
            .map { CloudDocRef(it.key, it.value.generation) }

    // Test-only helpers — projects-only, mirroring the v=1 wire layout.

    private fun projectKey(uuid: ProjectUuid) = TreeKey(TrackedTreeId(uuid.value), TrackedTreeKind.Project)

    fun seedManifest(
        uuid: ProjectUuid,
        manifest: Manifest,
    ): Generation {
        val list = manifests.getOrPut(projectKey(uuid)) { mutableListOf() }
        val gen = nextGeneration()
        val ref =
            ManifestRef(
                rev = manifest.rev.value,
                path = "manifests/${uuid.value}/${manifest.rev.value}.json",
                generation = gen,
            )
        list += StoredManifest(ref, manifest)
        return gen
    }

    fun seedTreeManifest(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        manifest: Manifest,
    ): Generation {
        val list = manifests.getOrPut(TreeKey(treeId, kind)) { mutableListOf() }
        val gen = nextGeneration()
        val ref =
            ManifestRef(
                rev = manifest.rev.value,
                path = "trees/${kind.wireName}/${treeId.value}/manifests/${manifest.rev.value}.json",
                generation = gen,
            )
        list += StoredManifest(ref, manifest)
        return gen
    }

    fun manifestsForTree(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
    ): List<Manifest> = manifests[TreeKey(treeId, kind)]?.map { it.manifest } ?: emptyList()

    fun blobsCount(): Int = blobs.size

    fun sharedBlobsCount(): Int = blobs.keys.count { it.scope == BlobScope.Shared }

    fun privateBlobsCount(uuid: ProjectUuid): Int = blobs.keys.count { (it.scope as? BlobScope.Private)?.uuid == uuid }

    fun manifestsFor(uuid: ProjectUuid): List<Manifest> = manifests[projectKey(uuid)]?.map { it.manifest } ?: emptyList()

    fun forceLock(
        uuid: ProjectUuid,
        lock: LeaseLock,
    ): Generation {
        val gen = nextGeneration()
        locks[projectKey(uuid)] = StoredLock(lock, gen)
        return gen
    }
}
