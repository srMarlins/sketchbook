package com.sketchbook.cloud.metadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * In-memory [MetadataStore]. Used as the fake for unit tests across modules and as a local-
 * only fallback when cloud creds aren't configured (so the lock surface still has well-defined
 * semantics without crossing the network). Lives in commonMain rather than commonTest because
 * downstream modules (`:shared:sync`, `:app-desktop`) need to construct it in their own tests
 * — the repo convention for cross-module fakes/in-memory impls (mirrors
 * `InMemoryJournalRepository`).
 *
 * Keys docs by path string; stores serialized JSON so `getDoc / observeDoc` go through the
 * same kotlinx-serialization paths the real adapter would. Listener flows are backed by a
 * single [MutableStateFlow] keyed by path/collection prefix; a write fans out to every
 * observer instantly with no scheduler quirks.
 *
 * **Not a Firestore emulator.** Behavior matches the contract of [MetadataStore]; the wire
 * shape that an actual Firestore would impose (Map<String, Any?> field ordering, server-
 * timestamp materialization, snapshot metadata) is intentionally not modeled. Use the real
 * emulator (deferred follow-up) for those concerns.
 */
class InMemoryMetadataStore(
    private val clock: Clock = Clock.System,
    private val json: Json = Json,
) : MetadataStore {
    private val mutex = Mutex()
    private val docs = MutableStateFlow<Map<String, String>>(emptyMap())

    override suspend fun <T : Any> getDoc(
        path: DocPath,
        serializer: KSerializer<T>,
    ): T? = decode(docs.value[path.value], serializer)

    override suspend fun <T : Any> setDoc(
        path: DocPath,
        value: T,
        serializer: KSerializer<T>,
    ) {
        mutex.withLock {
            docs.value = docs.value + (path.value to json.encodeToString(serializer, value))
        }
    }

    override suspend fun <T : Any> updateDoc(
        path: DocPath,
        serializer: KSerializer<T>,
        transform: (current: T?) -> T,
    ): T {
        // The mutex serializes the read-modify-write so two concurrent updateDoc calls don't
        // race (mirrors Firestore's tx semantics for a single-machine fake).
        return mutex.withLock {
            val current = decode(docs.value[path.value], serializer)
            val next = transform(current)
            docs.value = docs.value + (path.value to json.encodeToString(serializer, next))
            next
        }
    }

    override suspend fun deleteDoc(path: DocPath) {
        mutex.withLock { docs.value = docs.value - path.value }
    }

    override fun <T : Any> observeDoc(
        path: DocPath,
        serializer: KSerializer<T>,
    ): Flow<T?> = docs.map { decode(it[path.value], serializer) }

    override fun <T : Any> observeCollection(
        path: CollectionPath,
        serializer: KSerializer<T>,
    ): Flow<List<CollectionEntry<T>>> {
        val prefix = path.value + "/"
        return docs
            .map { all ->
                all.entries
                    .filter { it.key.startsWith(prefix) && !it.key.substring(prefix.length).contains('/') }
                    .mapNotNull { entry ->
                        decode(entry.value, serializer)?.let { v ->
                            CollectionEntry(id = entry.key.substring(prefix.length), value = v)
                        }
                    }
            }
            // The underlying docs flow emits on every write across the whole store. The
            // distinctUntilChanged here keeps observers of one collection from waking up on
            // unrelated writes to a different prefix (N10).
            .distinctUntilChanged()
    }

    override suspend fun acquireLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
        holderName: String,
    ): AcquireResult {
        val now: Instant = clock.now()
        return mutex.withLock {
            val current = decode(docs.value[path.value], LockDoc.serializer())
            val canAcquire =
                current == null ||
                    current.holder == holder ||
                    current.expiresAt < now
            if (!canAcquire) return@withLock AcquireResult.HeldByOther(current!!)
            val doc =
                LockDoc(
                    holder = holder,
                    holderName = holderName,
                    acquiredAt = now,
                    expiresAt = now + ttl,
                )
            docs.value = docs.value + (path.value to json.encodeToString(LockDoc.serializer(), doc))
            AcquireResult.Acquired
        }
    }

    override suspend fun refreshLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
    ): Boolean {
        val now: Instant = clock.now()
        return mutex.withLock {
            val current = decode(docs.value[path.value], LockDoc.serializer()) ?: return@withLock false
            if (current.holder != holder) return@withLock false
            val doc = current.copy(expiresAt = now + ttl)
            docs.value = docs.value + (path.value to json.encodeToString(LockDoc.serializer(), doc))
            true
        }
    }

    override suspend fun releaseLock(
        path: DocPath,
        holder: String,
    ) {
        mutex.withLock {
            val current = decode(docs.value[path.value], LockDoc.serializer()) ?: return@withLock
            if (current.holder == holder) {
                docs.value = docs.value - path.value
            }
        }
    }

    /** Snapshot the entire backing map. Useful for assertions. */
    fun snapshot(): Map<String, String> = docs.value

    /** Direct doc lookup for test convenience (no path validation). */
    fun rawDoc(path: String): String? = docs.value[path]

    private fun <T : Any> decode(
        encoded: String?,
        serializer: KSerializer<T>,
    ): T? = encoded?.let { json.decodeFromString(serializer, it) }
}
