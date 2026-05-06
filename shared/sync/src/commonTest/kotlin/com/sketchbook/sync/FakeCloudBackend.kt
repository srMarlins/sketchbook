package com.sketchbook.sync

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
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * In-memory [CloudBackend] for unit tests. Tracks blobs by hash, manifests per project, and a
 * single `head` per project. Conditional writes use a synthetic monotonically increasing
 * generation counter.
 */
class FakeCloudBackend : CloudBackend {

    private val blobs = mutableMapOf<BlobKey, ByteArray>()
    private val manifests = mutableMapOf<ProjectUuid, MutableList<StoredManifest>>()
    private val locks = mutableMapOf<ProjectUuid, StoredLock>()
    private var generationCounter: Long = 1

    private data class StoredManifest(val ref: ManifestRef, val manifest: Manifest)
    private data class StoredLock(val lock: LeaseLock, val generation: Generation)
    private data class BlobKey(val scope: BlobScope, val hash: BlobHash)

    fun nextGeneration(): Generation = Generation((generationCounter++).toString())

    override suspend fun headBlob(hash: BlobHash, scope: BlobScope): Boolean = blobs.containsKey(BlobKey(scope, hash))

    override suspend fun putBlob(hash: BlobHash, source: RawSource, size: Long, scope: BlobScope) {
        val key = BlobKey(scope, hash)
        if (blobs.containsKey(key)) return
        val bytes = source.buffered().readByteArray()
        blobs[key] = bytes
    }

    override suspend fun getBlob(hash: BlobHash, scope: BlobScope): RawSource = error("not used in these tests")

    override suspend fun readManifest(uuid: ProjectUuid, rev: SnapshotRev): Manifest {
        val list = manifests[uuid] ?: throw SketchbookError.NotFound("no manifests for $uuid")
        return list.firstOrNull { it.manifest.rev == rev }?.manifest
            ?: throw SketchbookError.NotFound("rev $rev not found")
    }

    override suspend fun listManifests(uuid: ProjectUuid, sinceRev: SnapshotRev?): List<ManifestRef> {
        val list = manifests[uuid] ?: return emptyList()
        return list.map { it.ref }.filter {
            sinceRev == null || it.rev > sinceRev.value
        }
    }

    override suspend fun appendManifestHead(
        uuid: ProjectUuid,
        expectedHead: Generation?,
        manifest: Manifest,
    ): Result<Generation> {
        val list = manifests.getOrPut(uuid) { mutableListOf() }
        val currentHead = list.lastOrNull()
        if (expectedHead != null) {
            if (expectedHead == Generation.ZERO) {
                if (currentHead != null) return Result.failure(SketchbookError.Conflict("HEAD exists"))
            } else if (currentHead == null || currentHead.ref.generation != expectedHead) {
                return Result.failure(SketchbookError.Conflict("HEAD generation mismatch"))
            }
        }
        val gen = nextGeneration()
        val ref = ManifestRef(
            rev = manifest.rev.value,
            path = "manifests/${uuid.value}/${manifest.rev.value.toString().padStart(8, '0')}-${manifest.timestamp}.json",
            generation = gen,
        )
        list += StoredManifest(ref, manifest)
        return Result.success(gen)
    }

    override suspend fun acquireLock(uuid: ProjectUuid, lock: LeaseLock): LeaseAcquireResult {
        val existing = locks[uuid]
        if (existing != null) {
            return LeaseAcquireResult.Held(existing.lock, existing.generation)
        }
        val gen = nextGeneration()
        locks[uuid] = StoredLock(lock, gen)
        return LeaseAcquireResult.Acquired(gen)
    }

    override suspend fun refreshLock(uuid: ProjectUuid, lock: LeaseLock, expected: Generation): LeaseRefreshResult {
        val existing = locks[uuid] ?: return LeaseRefreshResult.Stale
        if (existing.generation != expected) return LeaseRefreshResult.Stale
        val gen = nextGeneration()
        locks[uuid] = StoredLock(lock, gen)
        return LeaseRefreshResult.Refreshed(gen)
    }

    override suspend fun releaseLock(uuid: ProjectUuid, expected: Generation) {
        val existing = locks[uuid] ?: return
        if (existing.generation == expected) locks.remove(uuid)
    }

    // Test-only helpers.

    fun seedManifest(uuid: ProjectUuid, manifest: Manifest): Generation {
        val list = manifests.getOrPut(uuid) { mutableListOf() }
        val gen = nextGeneration()
        val ref = ManifestRef(
            rev = manifest.rev.value,
            path = "manifests/${uuid.value}/${manifest.rev.value}.json",
            generation = gen,
        )
        list += StoredManifest(ref, manifest)
        return gen
    }

    fun blobsCount(): Int = blobs.size
    fun sharedBlobsCount(): Int = blobs.keys.count { it.scope == BlobScope.Shared }
    fun privateBlobsCount(uuid: ProjectUuid): Int = blobs.keys.count { (it.scope as? BlobScope.Private)?.uuid == uuid }
    fun manifestsFor(uuid: ProjectUuid): List<Manifest> = manifests[uuid]?.map { it.manifest } ?: emptyList()
    fun forceLock(uuid: ProjectUuid, lock: LeaseLock): Generation {
        val gen = nextGeneration()
        locks[uuid] = StoredLock(lock, gen)
        return gen
    }
}
