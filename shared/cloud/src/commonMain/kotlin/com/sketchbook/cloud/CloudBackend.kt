package com.sketchbook.cloud

import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import kotlinx.io.RawSource

/**
 * Provider-agnostic cloud client. v1 ships [DirectGcsBackend] (jvmMain) against Google Cloud
 * Storage; v1.2 may add an R2/B2 backend at credit expiry. The interface only speaks domain
 * types and provider-neutral [Generation] tokens.
 */
interface CloudBackend {

    /** True iff a blob with [hash] exists in the bucket. */
    suspend fun headBlob(hash: BlobHash): Boolean

    /**
     * Upload a blob at the content-addressed path. No-op (returns successfully) if it already
     * exists — content-addressed uploads are idempotent.
     */
    suspend fun putBlob(hash: BlobHash, source: RawSource, size: Long)

    /** Download a blob. Caller closes the returned [RawSource]. */
    suspend fun getBlob(hash: BlobHash): RawSource

    /** Read a single manifest by `(uuid, rev)`. */
    suspend fun readManifest(uuid: ProjectUuid, rev: SnapshotRev): Manifest

    /** List manifests for a project, optionally only those after [sinceRev] (for incremental pull). */
    suspend fun listManifests(uuid: ProjectUuid, sinceRev: SnapshotRev?): List<ManifestRef>

    /**
     * Append a new manifest as the project's HEAD. CAS via [expectedHead]:
     * - `null` → no precondition (forced overwrite — discouraged).
     * - [Generation.ZERO] → must-not-exist (initial write).
     * - any other → must match the current HEAD generation; mismatch returns `Result.failure`
     *   with [com.sketchbook.core.SketchbookError.Conflict].
     *
     * On success, returns the new HEAD's [Generation].
     */
    suspend fun appendManifestHead(
        uuid: ProjectUuid,
        expectedHead: Generation?,
        manifest: Manifest,
    ): Result<Generation>

    /** CAS-acquire the lease lock for a project. */
    suspend fun acquireLock(uuid: ProjectUuid, lock: LeaseLock): LeaseAcquireResult

    /** Heartbeat-refresh an existing lease lock; fails if our generation no longer matches. */
    suspend fun refreshLock(uuid: ProjectUuid, lock: LeaseLock, expected: Generation): LeaseRefreshResult

    /** Release our lease lock. */
    suspend fun releaseLock(uuid: ProjectUuid, expected: Generation)
}
