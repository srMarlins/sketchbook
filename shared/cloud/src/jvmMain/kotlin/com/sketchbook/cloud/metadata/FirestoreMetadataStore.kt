package com.sketchbook.cloud.metadata

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * gitlive-backed [MetadataStore]. The single point of contact between Sketchbook code and the
 * `dev.gitlive.firebase.*` namespace — every other consumer talks to the [MetadataStore]
 * interface.
 *
 * Construction is cheap; the underlying `Firebase.firestore` is a process singleton vended
 * by gitlive after `Firebase.initialize(...)` (see [com.sketchbook.auth.firebase.FirebaseSdkBootstrap]).
 * The bootstrap must run before any instance of this class makes a real call, because the
 * gitlive `Firebase` accessor lazily resolves to the initialized `FirebaseApp`. Construction
 * itself doesn't touch the SDK, so it's safe to wire this class into DI before bootstrap runs;
 * the first method call is where bootstrap must already be done.
 *
 * **gitlive 2.4.0 API notes** (locked-down by reading the JAR — these matter for keeping
 * the adapter compiling across minor bumps):
 *
 *  - `FirebaseFirestore.runTransaction { ... }` body is a `suspend Transaction.() -> T`
 *    **extension** lambda (zero params, `this` is the Transaction). The compiler can't
 *    coerce a `(Transaction) -> T` one-param lambda into the extension shape.
 *  - `Transaction.get(DocumentReference): DocumentSnapshot` is suspend.
 *  - `Transaction.set/delete/update` are NOT suspend — they enqueue writes that the
 *    surrounding runTransaction commits on body return.
 *  - `DocumentSnapshot.data(strategy)` decodes via kotlinx-serialization; only valid when
 *    `snap.exists` is true. Returns the deserialized value (not `Result<...>`).
 *
 * @param firestore optional override for the SDK instance. Production code uses the default
 *   (`Firebase.firestore`); tests may inject an emulator-backed instance.
 */
class FirestoreMetadataStore(
    /**
     * Suspends until the gitlive SDK is ready to use (Pattern A1 token injection + `Firebase
     * .initialize` complete). Production wiring points this at
     * `FirebaseSdkBootstrap::ensureInitialized` — idempotent after the first successful call.
     * Default is a no-op so tests + the in-memory adapter can construct without bootstrap.
     */
    private val ensureInitialized: suspend () -> Unit = {},
    /**
     * Lazy accessor for the gitlive `FirebaseFirestore` singleton. Default reads
     * `Firebase.firestore`, which must not be evaluated before `Firebase.initialize` runs.
     * Tests can override with an emulator-backed instance.
     */
    private val firestoreProvider: () -> FirebaseFirestore = { Firebase.firestore },
    private val clock: Clock = Clock.System,
) : MetadataStore {
    /** Resolved on first use — guarantees [ensureInitialized] has already run. */
    private val firestore: FirebaseFirestore by lazy { firestoreProvider() }

    override suspend fun <T : Any> getDoc(
        path: DocPath,
        serializer: KSerializer<T>,
    ): T? {
        ensureInitialized()
        val snap = firestore.document(path.value).get()
        return if (snap.exists) snap.data(serializer) else null
    }

    override suspend fun <T : Any> setDoc(
        path: DocPath,
        value: T,
        serializer: KSerializer<T>,
    ) {
        ensureInitialized()
        firestore.document(path.value).set(serializer, value)
    }

    override suspend fun <T : Any> updateDoc(
        path: DocPath,
        serializer: KSerializer<T>,
        transform: suspend (current: T?) -> T,
    ): T {
        ensureInitialized()
        // gitlive's runTransaction body is a `suspend Transaction.() -> T` extension lambda;
        // Firestore retries on contention. The `transform` callback in our port is `suspend`,
        // and Firestore's retry contract calls for idempotent bodies — callers must not start
        // unrelated side-effects inside transform.
        val ref = firestore.document(path.value)
        return firestore.runTransaction<T> {
            val snap = get(ref)
            val current: T? = if (snap.exists) snap.data(serializer) else null
            val next: T = transform(current)
            set(ref, serializer, next)
            next
        }
    }

    override suspend fun deleteDoc(path: DocPath) {
        ensureInitialized()
        firestore.document(path.value).delete()
    }

    override fun <T : Any> observeDoc(
        path: DocPath,
        serializer: KSerializer<T>,
    ): Flow<T?> =
        flow {
            // Defer the bootstrap call to flow collection time — observeDoc is not suspending
            // by contract, but the returned Flow is, so calling ensureInitialized inside the
            // flow builder satisfies "first SDK touch is gated by bootstrap" without forcing
            // the caller to call a suspend factory.
            ensureInitialized()
            emitAll(
                firestore.document(path.value).snapshots.map { snap ->
                    if (snap.exists) snap.data(serializer) else null
                },
            )
        }

    override fun <T : Any> observeCollection(
        path: CollectionPath,
        serializer: KSerializer<T>,
    ): Flow<List<CollectionEntry<T>>> =
        flow {
            ensureInitialized()
            emitAll(
                firestore.collection(path.value).snapshots.map { qs ->
                    qs.documents.map { CollectionEntry(id = it.id, value = it.data(serializer)) }
                },
            )
        }

    override suspend fun acquireLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
    ): Boolean {
        ensureInitialized()
        val now = clock.now()
        val ref = firestore.document(path.value)
        return runCatching {
            firestore.runTransaction<Boolean> {
                val snap = get(ref)
                val current = if (snap.exists) snap.data(LockDoc.serializer()) else null
                val canAcquire =
                    current == null ||
                        current.holder == holder ||
                        current.expiresAt < now
                if (canAcquire) {
                    val nextSeq = (current?.heartbeatSeq ?: 0L) + 1
                    set(
                        ref,
                        LockDoc.serializer(),
                        LockDoc(
                            holder = holder,
                            holderName = "",
                            acquiredAt = now,
                            expiresAt = now + ttl,
                            heartbeatSeq = nextSeq,
                        ),
                    )
                    true
                } else {
                    false
                }
            }
        }.getOrElse { false }
    }

    override suspend fun refreshLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
    ): Boolean {
        ensureInitialized()
        val now = clock.now()
        val ref = firestore.document(path.value)
        return runCatching {
            firestore.runTransaction<Boolean> {
                val snap = get(ref)
                val current = if (snap.exists) snap.data(LockDoc.serializer()) else null
                if (current != null && current.holder == holder) {
                    set(
                        ref,
                        LockDoc.serializer(),
                        current.copy(
                            expiresAt = now + ttl,
                            heartbeatSeq = current.heartbeatSeq + 1,
                        ),
                    )
                    true
                } else {
                    // Stolen / absent / replaced — caller treats as takeover.
                    false
                }
            }
        }.getOrElse { false }
    }

    override suspend fun releaseLock(
        path: DocPath,
        holder: String,
    ) {
        ensureInitialized()
        // Best-effort cleanup; never throws. Wraps the whole tx in runCatching because
        // releaseLock at the end of a snapshot pipeline run shouldn't bubble a Firestore
        // error up the stack — the pipeline succeeded by then.
        val ref = firestore.document(path.value)
        runCatching {
            firestore.runTransaction<Unit> {
                val snap = get(ref)
                if (snap.exists) {
                    val current = snap.data(LockDoc.serializer())
                    if (current.holder == holder) {
                        delete(ref)
                    }
                }
            }
        }
    }
}
