package com.sketchbook.cloud

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Lease lock primitives. Stored in cloud as `locks/<project_uuid>.lock` JSON. CAS via the
 * provider's conditional-write header (`x-goog-if-generation-match` on GCS).
 */
@Serializable
data class LeaseLock(
    val ownerHostId: String,
    val ownerHostName: String,
    val acquiredAt: Instant,
    val expiresAt: Instant,
    val heartbeatSeq: Long = 0,
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
