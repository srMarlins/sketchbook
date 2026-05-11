package com.sketchbook.cloud.metadata

/**
 * Outcome of [MetadataStore.acquireLock]. Distinguishes the three meaningful cases the
 * pipeline + UI need to surface differently:
 *
 *  - [Acquired] — we hold the lease.
 *  - [HeldByOther] — a non-expired lease is held by someone else. UI shows the holder.
 *  - [Failed] — the operation itself failed (permission denied, network down, etc.). The
 *    underlying [Throwable] is preserved so callers / logs see "permission denied" rather
 *    than a misleading "lock held."
 *
 * Replaces the previous `Boolean` return which conflated genuine contention with operational
 * failures and surfaced both as "locked by another host" in the UI (M1).
 */
sealed interface AcquireResult {
    object Acquired : AcquireResult

    data class HeldByOther(
        val current: LockDoc,
    ) : AcquireResult

    data class Failed(
        val cause: Throwable,
    ) : AcquireResult
}
