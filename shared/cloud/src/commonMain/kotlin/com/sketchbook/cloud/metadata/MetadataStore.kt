package com.sketchbook.cloud.metadata

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Provider-neutral, document-oriented metadata store. The Firebase migration's port for
 * Firestore: per-doc CRUD, real-time listener subscriptions, optimistic-CAS via [updateDoc],
 * and a small lock primitive that maps onto `/users/<uid>/locks/<treeId>` Firestore docs.
 *
 * **Why a port:** gitlive's `firebase-kotlin-sdk` is officially "alpha" (v0.6.x at time of
 * writing) — minor bumps have historically broken common APIs. App code talks to this
 * interface; the jvmMain `FirestoreMetadataStore` adapter is the only place that imports
 * `dev.gitlive.firebase.*`. A future move off Firestore (Supabase, self-hosted Postgres +
 * WS server) swaps the adapter; everything above this line is unchanged.
 *
 * **What the port does not cover:**
 *  - Multi-doc transactions across paths. Not a current need; lock operations expose
 *    atomicity primitives directly. If we later need a real transaction (e.g. atomic
 *    cross-doc state machine), add `transaction { … }` as an explicit method.
 *  - Pagination on [observeCollection]. Sketchbook's per-user tree count is bounded
 *    (~1000 trees) and well under Firestore's 1 MB per snapshot limit; cross that bridge
 *    when collaborator collections push past it.
 *  - Server timestamps. Listener-arrival ordering supplies "happened-before"; clients write
 *    `Instant.now()` values themselves. Firestore's `serverTimestamp()` sentinel would
 *    introduce a wire-shape leak (kClass-based polymorphic JSON), and the use case for
 *    server time on user-private docs is weak.
 */
interface MetadataStore {
    /**
     * Read a single doc by path. Returns `null` if the doc doesn't exist. Throws on any other
     * failure (permission denied, network, etc.) — callers wrap in `runCatching` when they
     * need a soft failure mode.
     */
    suspend fun <T : Any> getDoc(
        path: DocPath,
        serializer: KSerializer<T>,
    ): T?

    /**
     * Replace the doc at [path] with [value]. No precondition — last-writer-wins. For CAS
     * semantics use [updateDoc].
     */
    suspend fun <T : Any> setDoc(
        path: DocPath,
        value: T,
        serializer: KSerializer<T>,
    )

    /**
     * Read-modify-write the doc at [path] atomically via a Firestore transaction. [transform]
     * receives the current value (or `null` if the doc doesn't exist) and returns the new
     * value. The transaction retries on conflict — [transform] must be idempotent (no side
     * effects beyond computing the new value).
     *
     * [transform] is intentionally **not** `suspend` (M15): the transaction body retries on
     * conflict, and a suspending body invites callers to perform unrelated I/O inside the
     * retry loop. Compute the new value purely from [current] + closed-over state.
     *
     * Returns the value that was written (i.e. [transform]'s last return).
     */
    suspend fun <T : Any> updateDoc(
        path: DocPath,
        serializer: KSerializer<T>,
        transform: (current: T?) -> T,
    ): T

    /**
     * Delete the doc at [path]. No-op if it doesn't exist.
     */
    suspend fun deleteDoc(path: DocPath)

    /**
     * Real-time subscription. Emits the current value on collection, then a new value every
     * time the doc changes (including deletion → `null`). Errors propagate as exceptions on
     * the flow.
     */
    fun <T : Any> observeDoc(
        path: DocPath,
        serializer: KSerializer<T>,
    ): Flow<T?>

    /**
     * Real-time subscription to a whole collection. Emits the full list on every change
     * (small N — single-user trees/machines/plugins are bounded). Each entry carries the
     * doc id ([CollectionEntry.id]) alongside the decoded value so SyncCoordinator-style
     * consumers can route per-doc deltas without embedding the id in the wire shape.
     * Listeners deliver collection-internal ordering by document id; sort client-side if
     * you need anything else.
     */
    fun <T : Any> observeCollection(
        path: CollectionPath,
        serializer: KSerializer<T>,
    ): Flow<List<CollectionEntry<T>>>

    /**
     * Acquire a lease lock at [path]. Atomic test-and-set: succeeds (returns
     * [AcquireResult.Acquired]) iff the doc is absent OR the existing lease has expired OR
     * the existing lease is held by [holder]. On success the doc is written with `holder`,
     * `holderName` (UI label), now / now + ttl. Returns [AcquireResult.HeldByOther] if a
     * non-expired lease held by someone else exists, or [AcquireResult.Failed] if the
     * operation itself failed (permission denied, network down — distinguishing genuine
     * contention from operational failure, M1).
     *
     * [holderName] is the human-readable display label the UI shows in "held by X until Y"
     * badges. Passed inline so the acquire writes the full LockDoc in a single transaction
     * — landing it via a follow-up `setDoc` would double the Firestore write cost and
     * latency for every lease operation.
     */
    suspend fun acquireLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
        holderName: String = "",
    ): AcquireResult

    /**
     * Heartbeat-extend a lease we already hold. Returns a typed [RefreshResult]:
     *
     *  - [RefreshResult.Refreshed] — doc still names us, expiry bumped.
     *  - [RefreshResult.Lost] — doc no longer names us (stolen, expired-and-replaced, or
     *    deleted). Terminal: caller should abort heartbeating and let the listener path
     *    surface the new holder.
     *  - [RefreshResult.Failed] — operational failure (network blip, permission denied).
     *    The lease's previous TTL is still in force; caller should retry on the next
     *    heartbeat cadence rather than abandoning the lock.
     *
     * The typed result is what lets [com.sketchbook.sync.SnapshotPipeline]'s heartbeat keep
     * the lease alive through a transient blip during a long save — folding all failures
     * into a Boolean would have terminated heartbeats on the first network hiccup.
     */
    suspend fun refreshLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
    ): RefreshResult

    /**
     * Release a lease we hold. Best-effort: only deletes the doc if it still names us. Safe
     * to call even after a takeover (no-op in that case) or after expiry. Never throws on
     * absence — releaseLock is the cleanup path; cleanups shouldn't fail loudly.
     */
    suspend fun releaseLock(
        path: DocPath,
        holder: String,
    )
}

/**
 * Bounded-retry wrapper around [MetadataStore.updateDoc]. Firestore's `runTransaction`
 * already retries on contention, but the underlying contract is open-ended; in a pathological
 * write storm an unbounded retry could pin a coroutine indefinitely. This helper applies an
 * upper bound + exponential backoff (100ms, 200ms, …) before giving up (M7).
 *
 * Cancellation propagates: a cancelled scope throws [CancellationException] out of [delay]
 * without re-trying.
 */
suspend fun <T : Any> MetadataStore.updateDocBounded(
    path: DocPath,
    serializer: KSerializer<T>,
    maxAttempts: Int = 5,
    transform: (T?) -> T,
): T {
    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return updateDoc(path, serializer, transform)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            lastError = t
            if (attempt == maxAttempts - 1) return@repeat
            delay((100L * (1 shl attempt)).milliseconds)
        }
    }
    throw lastError ?: IllegalStateException("updateDoc failed after $maxAttempts attempts")
}
