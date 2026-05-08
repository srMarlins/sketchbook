package com.sketchbook.cloud

import com.sketchbook.core.UserId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Lease lock primitives. Stored in cloud as `trees/<kind>/<tree_id>/lock` JSON (post-migration)
 * / `locks/<project_uuid>.lock` (legacy v=1). CAS via the provider's conditional-write header
 * (`x-goog-if-generation-match` on GCS).
 *
 * [ownerUserId] is forward-compat for v1.2 multi-user. v1 always sets it to [UserId.DEFAULT];
 * v1.2 fills in real ids. Already on the wire so the ACL semantics are stable across the v1 →
 * v1.2 transition.
 */
@Serializable
data class LeaseLock(
    val ownerUserId: UserId = UserId.DEFAULT,
    val ownerHostId: String,
    val ownerHostName: String,
    val acquiredAt: Instant,
    val expiresAt: Instant,
)

sealed interface LeaseAcquireResult {
    /** Lock acquired; [generation] is the object generation we just wrote. */
    data class Acquired(
        val generation: Generation,
    ) : LeaseAcquireResult

    /** Another host holds the lock; [held] describes who, [generation] is theirs. */
    data class Held(
        val held: LeaseLock,
        val generation: Generation,
    ) : LeaseAcquireResult
}

sealed interface LeaseRefreshResult {
    data class Refreshed(
        val generation: Generation,
    ) : LeaseRefreshResult

    /** Our generation no longer matches — someone else took the lock. */
    data object Stale : LeaseRefreshResult
}

/** Pointer to a manifest object in cloud storage. */
@Serializable
data class ManifestRef(
    val rev: Long,
    val path: String,
    val generation: Generation,
)
