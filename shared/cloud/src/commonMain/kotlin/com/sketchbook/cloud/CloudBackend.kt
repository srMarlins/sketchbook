package com.sketchbook.cloud

import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import kotlinx.io.RawSource

/**
 * Provider-agnostic cloud client. v1 ships [FirebaseBlobStore] (jvmMain) against Google Cloud
 * Storage; v1.2 may add an R2/B2 backend at credit expiry. The interface only speaks domain
 * types and provider-neutral [Generation] tokens.
 */
interface CloudBackend {
    /** True iff a blob with [hash] exists in the bucket within [scope]. */
    suspend fun headBlob(
        hash: BlobHash,
        scope: BlobScope = BlobScope.Shared,
    ): Boolean

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
    suspend fun getBlob(
        hash: BlobHash,
        scope: BlobScope = BlobScope.Shared,
    ): RawSource

    /** Read a single manifest by `(uuid, rev)`. */
    suspend fun readManifest(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): Manifest

    /** List manifests for a project, optionally only those after [sinceRev] (for incremental pull). */
    suspend fun listManifests(
        uuid: ProjectUuid,
        sinceRev: SnapshotRev?,
    ): List<ManifestRef>

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

    // NOTE (Phase 3, 2026-05-10): the lease-lock methods (acquireLock / refreshLock /
    // releaseLock) moved from this interface to com.sketchbook.cloud.metadata.MetadataStore
    // as Firestore-backed `/users/{uid}/locks/{treeId}` docs. See the Phase 3 entry findings
    // in docs/plans/2026-05-08-firebase-migration-design.md.
}
