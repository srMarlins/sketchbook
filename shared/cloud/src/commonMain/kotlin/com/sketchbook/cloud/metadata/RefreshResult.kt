package com.sketchbook.cloud.metadata

/**
 * Outcome of [MetadataStore.refreshLock]. Distinguishes the three cases the lease-holder
 * needs to react to differently:
 *
 *  - [Refreshed] — the existing lease was extended; keep holding.
 *  - [Lost] — the doc no longer names us (stolen, expired-and-replaced, or deleted). The
 *    caller should treat this as a takeover and abort the heartbeat — the next listener
 *    emission will surface the new holder. **Terminal** from the holder's point of view.
 *  - [Failed] — the operation itself failed (permission denied, network down). The lease
 *    may or may not still be held; the holder should retry on the next heartbeat cadence
 *    rather than abandoning the lease. The previous TTL is still in force so a transient
 *    blip doesn't immediately expire the lease.
 *
 * Replaces the previous `Boolean` return, which conflated [Lost] with [Failed] and made
 * heartbeat retry logic in [com.sketchbook.sync.SnapshotPipeline] unreachable.
 */
sealed interface RefreshResult {
    object Refreshed : RefreshResult

    object Lost : RefreshResult

    data class Failed(
        val cause: Throwable,
    ) : RefreshResult
}
