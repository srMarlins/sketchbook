package com.sketchbook.cloud.metadata

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Wire shape of a lease-lock document at `/users/<uid>/locks/<treeId>`. Replaces the
 * pre-Phase-3 GCS object at `<uid>/locks/<treeId>.lock`.
 *
 * **Why a discrete type:** the MetadataStore's `acquireLock` / `refreshLock` / `releaseLock`
 * primitives are by-value (boolean returns); the UI surface needs the full holder / expiry
 * info to render "held by X until Y" badges. `LockRepository.observe` reads the doc with
 * this serializer to power the UI.
 *
 * - [holder] — host id of the lease holder. Stable per-machine ID written from
 *   `DesktopAppGraph.hostIdentity().id`.
 * - [holderName] — human-readable host name; used in UI strings only.
 * - [acquiredAt] / [expiresAt] — wall-clock instants in the lease holder's local time.
 *   Client clocks can skew; treat [expiresAt] with ~30s slack on the read side rather than
 *   on the write side so refresh rotations don't accidentally release a still-valid lease.
 * - [heartbeatSeq] — monotonic counter incremented on each successful refresh. Currently
 *   unused; future "missed heartbeat → soft warn UI" would key off this.
 */
@Serializable
data class LockDoc(
    val holder: String,
    val holderName: String = "",
    val acquiredAt: Instant,
    val expiresAt: Instant,
    val heartbeatSeq: Long = 0,
)
