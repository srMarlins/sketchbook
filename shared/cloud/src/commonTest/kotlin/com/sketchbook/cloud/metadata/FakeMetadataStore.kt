package com.sketchbook.cloud.metadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * In-memory [MetadataStore] for unit tests. Keys docs by path string; stores serialized JSON
 * to keep `getDoc / observeDoc` reproducible across types. Listener flows are backed by a
 * [MutableStateFlow] keyed by path/collection prefix so a write fans out to every observer
 * instantly — no scheduler quirks.
 *
 * **Not a Firestore emulator.** Behavior matches the contract of [MetadataStore]; the wire
 * shape that an actual Firestore would impose (Map<String, Any?> field ordering, server-
 * timestamp materialization, snapshot metadata) is intentionally not modeled. Use the real
 * emulator (deferred follow-up) for those concerns.
 */
class FakeMetadataStore(
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
        transform: suspend (current: T?) -> T,
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
    ): Flow<List<T>> {
        val prefix = path.value + "/"
        return docs.map { all ->
            all.entries
                .filter { it.key.startsWith(prefix) && !it.key.substring(prefix.length).contains('/') }
                .mapNotNull { decode(it.value, serializer) }
        }
    }

    override suspend fun acquireLock(
        path: DocPath,
        holder: String,
        ttl: Duration,
    ): Boolean {
        val now: Instant = clock.now()
        return mutex.withLock {
            val current = decode(docs.value[path.value], LockDoc.serializer())
            val canAcquire =
                current == null ||
                    current.holder == holder ||
                    current.expiresAt < now
            if (!canAcquire) return@withLock false
            val nextSeq = (current?.heartbeatSeq ?: 0L) + 1
            val doc =
                LockDoc(
                    holder = holder,
                    holderName = "",
                    acquiredAt = now,
                    expiresAt = now + ttl,
                    heartbeatSeq = nextSeq,
                )
            docs.value = docs.value + (path.value to json.encodeToString(LockDoc.serializer(), doc))
            true
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
            val doc =
                current.copy(
                    expiresAt = now + ttl,
                    heartbeatSeq = current.heartbeatSeq + 1,
                )
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
