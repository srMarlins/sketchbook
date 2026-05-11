package com.sketchbook.cloud.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Wire shape of a lease-lock document at `/users/<uid>/locks/<treeId>`. Replaces the
 * pre-Phase-3 GCS object at `<uid>/locks/<treeId>.lock`.
 *
 * **Wire field naming.** Snake_case on the wire via [@SerialName] so the document shape is
 * consistent with [TreeDoc] / [MachineDoc] and future cross-runtime readers (the Firebase
 * Admin SDK in Node/JS, or a Cloud Function evaluating rule predicates) don't trip over
 * camelCase Kotlin idioms. Property names stay camelCase Kotlin-side; only the wire shape
 * changes (M8).
 *
 * The pre-Phase-3 `heartbeatSeq` counter was dropped (N22) — nothing consumes it, every
 * refresh wrote a new value that no reader interpreted, and the field was named `heartbeat`
 * in a system where the heartbeat cadence isn't the lock-doc primary signal anyway.
 *
 * - [holder] — host id of the lease holder. Stable per-machine ID written from
 *   `DesktopAppGraph.hostIdentity().id`.
 * - [holderName] — human-readable host name; used in UI strings only.
 * - [acquiredAt] / [expiresAt] — wall-clock instants in the lease holder's local time.
 *   Client clocks can skew; treat [expiresAt] with ~30s slack on the read side rather than
 *   on the write side so refresh rotations don't accidentally release a still-valid lease.
 */
@Serializable
data class LockDoc(
    @SerialName("holder") val holder: String,
    @SerialName("holder_name") val holderName: String = "",
    @SerialName("acquired_at") val acquiredAt: Instant,
    @SerialName("expires_at") val expiresAt: Instant,
)
