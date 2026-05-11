package com.sketchbook.cloud.metadata

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

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
     * Returns the value that was written (i.e. [transform]'s last return).
     */
    suspend fun <T : Any> updateDoc(
        path: DocPath,
        serializer: KSerializer<T>,
        transform: suspend (current: T?) -> T,
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
     * Acquire a lease lock at [path]. Atomic test-and-set: succeeds (returns `true`) iff the
     * doc is absent OR the existing lease has expired OR the existing lease is held by
     * [holder]. On success the doc is written with `holder`, `holderName` (UI label), now /
     * now + ttl. Returns `false` if a non-expired lease held by someone else exists.
     *
     * [holderName] is the human-readable display label the UI shows in "held by X until Y"
     * badges. Passed inline so the acquire writes the full LockDoc in a single transaction
     * — landing it via a follow-up `setDoc` would double the Firestore write cost and
     * latency for every lease operation.
     *
     * The returned bool is sufficient for binary "did we get it?"; richer "who holds it"
     * comes from [observeDoc] against the same path with [LockDoc.serializer].
     */
    suspend fun acquireLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
        holderName: String = "",
    ): Boolean

    /**
     * Heartbeat-extend a lease we already hold. Returns `true` if the doc still names us and
     * the expiry was bumped; `false` if it's been stolen or expired-and-replaced — caller
     * should treat as a takeover and re-acquire if it wants to keep going.
     */
    suspend fun refreshLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
    ): Boolean

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
