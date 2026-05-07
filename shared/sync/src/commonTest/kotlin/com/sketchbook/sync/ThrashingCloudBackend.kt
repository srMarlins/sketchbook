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
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import kotlinx.io.RawSource

/**
 * Wraps a [FakeCloudBackend] so every [appendManifestHead] call against the configured
 * `(treeId, kind)` returns `Conflict`. Used to test merge-retry exhaustion: the pipeline
 * eventually bails after [SnapshotPipeline]'s retry budget.
 */
class ThrashingCloudBackend(
    private val delegate: FakeCloudBackend,
    private val targetTreeId: TrackedTreeId,
    private val targetKind: TrackedTreeKind,
) : CloudBackend {

    override suspend fun headBlob(hash: BlobHash, scope: BlobScope): Boolean = delegate.headBlob(hash, scope)
    override suspend fun putBlob(hash: BlobHash, source: RawSource, size: Long, scope: BlobScope) =
        delegate.putBlob(hash, source, size, scope)
    override suspend fun getBlob(hash: BlobHash, scope: BlobScope): RawSource = delegate.getBlob(hash, scope)
    override suspend fun readManifest(treeId: TrackedTreeId, kind: TrackedTreeKind, rev: SnapshotRev): Manifest =
        delegate.readManifest(treeId, kind, rev)
    override suspend fun listManifests(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        sinceRev: SnapshotRev?,
    ): List<ManifestRef> = delegate.listManifests(treeId, kind, sinceRev)

    override suspend fun appendManifestHead(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expectedHead: Generation?,
        manifest: Manifest,
    ): Result<Generation> {
        if (treeId == targetTreeId && kind == targetKind) {
            return Result.failure(SketchbookError.Conflict("thrashing: simulated CAS loss"))
        }
        return delegate.appendManifestHead(treeId, kind, expectedHead, manifest)
    }

    override suspend fun acquireLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
    ): LeaseAcquireResult = delegate.acquireLock(treeId, kind, lock)
    override suspend fun refreshLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
        expected: Generation,
    ): LeaseRefreshResult = delegate.refreshLock(treeId, kind, lock, expected)
    override suspend fun releaseLock(treeId: TrackedTreeId, kind: TrackedTreeKind, expected: Generation) =
        delegate.releaseLock(treeId, kind, expected)
    override suspend fun readDoc(key: CloudDocKey): CloudDocRead? = delegate.readDoc(key)
    override suspend fun writeDoc(key: CloudDocKey, expected: Generation?, bytes: ByteArray): Result<Generation> =
        delegate.writeDoc(key, expected, bytes)
    override suspend fun listDocs(prefix: CloudDocKey.Prefix): List<CloudDocRef> = delegate.listDocs(prefix)
}
