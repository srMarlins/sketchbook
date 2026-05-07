package com.sketchbook.cloud

import com.sketchbook.core.BlobHash
import com.sketchbook.core.CloudDocKey
import com.sketchbook.core.Manifest
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import kotlinx.io.RawSource

/**
 * Provider-agnostic cloud client. v1 ships [DirectGcsBackend] (jvmMain) against Google Cloud
 * Storage; v1.2 may add an R2/B2 backend at credit expiry. The interface only speaks domain
 * types and provider-neutral [Generation] tokens.
 *
 * Manifest + lease APIs are keyed by `(TrackedTreeId, TrackedTreeKind)`: the kind drives
 * `KindPolicy` decisions in the pipeline (lease required? merge vs branch-fork?) without
 * forcing the cloud impl to interpret it. v=1 wire format remains in effect — callers pass
 * `kind = Project` and `treeId = TrackedTreeId(uuid.value)` until the migrator (commit 10)
 * mints real registry-backed ids.
 */
interface CloudBackend {

    /** True iff a blob with [hash] exists in the bucket within [scope]. */
    suspend fun headBlob(hash: BlobHash, scope: BlobScope = BlobScope.Shared): Boolean

    /**
     * Upload a blob at the content-addressed path within [scope]. No-op (returns successfully)
     * if it already exists at that scope — uploads are idempotent within a scope but a blob
     * present in [BlobScope.Shared] does NOT count as present in a [BlobScope.Private] pool.
     */
    suspend fun putBlob(
        hash: BlobHash,
        source: RawSource,
        size: Long,
        scope: BlobScope = BlobScope.Shared,
    )

    /** Download a blob. Caller closes the returned [RawSource]. */
    suspend fun getBlob(hash: BlobHash, scope: BlobScope = BlobScope.Shared): RawSource

    /** Read a single manifest by `(treeId, kind, rev)`. */
    suspend fun readManifest(treeId: TrackedTreeId, kind: TrackedTreeKind, rev: SnapshotRev): Manifest

    /** List manifests for a tree, optionally only those after [sinceRev] (for incremental pull). */
    suspend fun listManifests(treeId: TrackedTreeId, kind: TrackedTreeKind, sinceRev: SnapshotRev?): List<ManifestRef>

    /**
     * Append a new manifest as the tree's HEAD. CAS via [expectedHead]:
     * - `null` → no precondition (forced overwrite — discouraged).
     * - [Generation.ZERO] → must-not-exist (initial write).
     * - any other → must match the current HEAD generation; mismatch returns `Result.failure`
     *   with [com.sketchbook.core.SketchbookError.Conflict].
     *
     * On success, returns the new HEAD's [Generation].
     */
    suspend fun appendManifestHead(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expectedHead: Generation?,
        manifest: Manifest,
    ): Result<Generation>

    /** CAS-acquire the lease lock for a tree. */
    suspend fun acquireLock(treeId: TrackedTreeId, kind: TrackedTreeKind, lock: LeaseLock): LeaseAcquireResult

    /** Heartbeat-refresh an existing lease lock; fails if our generation no longer matches. */
    suspend fun refreshLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
        expected: Generation,
    ): LeaseRefreshResult

    /** Release our lease lock. */
    suspend fun releaseLock(treeId: TrackedTreeId, kind: TrackedTreeKind, expected: Generation)

    /**
     * Read a small structured-JSON [CloudDoc] at [key]. Returns `null` when the object does not
     * exist (callers distinguish "not yet written" from a real error). The returned [Generation]
     * is the precondition token to feed back into [writeDoc] for read-modify-write CAS.
     */
    suspend fun readDoc(key: CloudDocKey): CloudDocRead?

    /**
     * Write [bytes] at [key] under CAS. Same precondition rules as [appendManifestHead]:
     * - `null` → no precondition.
     * - [Generation.ZERO] → must-not-exist.
     * - any other → must match the current object's generation.
     *
     * Returns the new object's [Generation] on success; failure with
     * [com.sketchbook.core.SketchbookError.Conflict] on CAS mismatch.
     */
    suspend fun writeDoc(key: CloudDocKey, expected: Generation?, bytes: ByteArray): Result<Generation>

    /** List all [CloudDoc] objects whose key starts with [prefix]. */
    suspend fun listDocs(prefix: CloudDocKey.Prefix): List<CloudDocRef>
}

/** Result of a [CloudBackend.readDoc] call. */
class CloudDocRead(val bytes: ByteArray, val generation: Generation)

/** Pointer to a [CloudDoc] returned by [CloudBackend.listDocs]. */
data class CloudDocRef(val key: CloudDocKey, val generation: Generation)
