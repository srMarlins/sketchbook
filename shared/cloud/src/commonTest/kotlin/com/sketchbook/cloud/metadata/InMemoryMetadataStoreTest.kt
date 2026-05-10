package com.sketchbook.cloud.metadata

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Contract tests for [InMemoryMetadataStore] — same contract the [FirestoreMetadataStore]
 * promises. The fake is the substitute used by SyncCoordinator / lock-repo tests downstream;
 * if a CRUD or lock semantic drifts, those downstream tests stop being meaningful.
 */
class InMemoryMetadataStoreTest {
    @Test
    fun `setDoc + getDoc round-trips a value`() =
        runTest {
            val store = InMemoryMetadataStore()
            val path = DocPath.tree("u", "t1")

            assertNull(store.getDoc(path, Sample.serializer()))
            store.setDoc(path, Sample("hello", 42), Sample.serializer())
            assertEquals(Sample("hello", 42), store.getDoc(path, Sample.serializer()))
        }

    @Test
    fun `updateDoc CAS reads the existing value and writes the transformed one`() =
        runTest {
            val store = InMemoryMetadataStore()
            val path = DocPath.tree("u", "t1")
            store.setDoc(path, Sample("orig", 1), Sample.serializer())

            val result =
                store.updateDoc(path, Sample.serializer()) { current ->
                    requireNotNull(current).copy(n = current.n + 10)
                }

            assertEquals(Sample("orig", 11), result)
            assertEquals(Sample("orig", 11), store.getDoc(path, Sample.serializer()))
        }

    @Test
    fun `deleteDoc removes the value and propagates to observers`() =
        runTest {
            val store = InMemoryMetadataStore()
            val path = DocPath.tree("u", "t1")
            store.setDoc(path, Sample("x", 1), Sample.serializer())

            store.deleteDoc(path)

            assertNull(store.getDoc(path, Sample.serializer()))
            assertNull(store.observeDoc(path, Sample.serializer()).first())
        }

    @Test
    fun `observeCollection emits only direct children of the collection`() =
        runTest {
            val store = InMemoryMetadataStore()
            val u = "user1"
            store.setDoc(DocPath.tree(u, "a"), Sample("a", 1), Sample.serializer())
            store.setDoc(DocPath.tree(u, "b"), Sample("b", 2), Sample.serializer())
            // A doc deeper in the path tree (a journal entry under tree a) must not appear in
            // the trees collection observer.
            store.setDoc(
                DocPath("users/$u/trees/a/journal/e1"),
                Sample("journal", 99),
                Sample.serializer(),
            )

            val items = store.observeCollection(CollectionPath.trees(u), Sample.serializer()).first()

            assertEquals(setOf(Sample("a", 1), Sample("b", 2)), items.toSet())
        }

    @Test
    fun `acquireLock succeeds on a fresh path and rejects a second holder`() =
        runTest {
            val store = InMemoryMetadataStore()
            val path = DocPath.lock("u", "t1")

            assertTrue(store.acquireLock(path, "host-a", 10.minutes))
            assertFalse(store.acquireLock(path, "host-b", 10.minutes))
        }

    @Test
    fun `acquireLock by the same holder succeeds and bumps the heartbeat`() =
        runTest {
            val store = InMemoryMetadataStore()
            val path = DocPath.lock("u", "t1")

            assertTrue(store.acquireLock(path, "host-a", 10.minutes))
            assertTrue(store.acquireLock(path, "host-a", 10.minutes))

            val lock = store.getDoc(path, LockDoc.serializer())!!
            assertEquals(2L, lock.heartbeatSeq)
        }

    @Test
    fun `acquireLock takes over an expired lease`() =
        runTest {
            val controllable = ControllableClock(Instant.fromEpochSeconds(1_700_000_000))
            val store = InMemoryMetadataStore(clock = controllable)
            val path = DocPath.lock("u", "t1")

            assertTrue(store.acquireLock(path, "host-a", 1.seconds))
            controllable.advanceBy(2.seconds)
            assertTrue(store.acquireLock(path, "host-b", 10.minutes))

            assertEquals("host-b", store.getDoc(path, LockDoc.serializer())!!.holder)
        }

    @Test
    fun `refreshLock fails if a different holder owns the lease`() =
        runTest {
            val store = InMemoryMetadataStore()
            val path = DocPath.lock("u", "t1")
            store.acquireLock(path, "host-a", 10.minutes)

            assertFalse(store.refreshLock(path, "host-b", 10.minutes))
            // Original lease is intact.
            assertEquals("host-a", store.getDoc(path, LockDoc.serializer())!!.holder)
        }

    @Test
    fun `releaseLock no-ops when caller is not the holder`() =
        runTest {
            val store = InMemoryMetadataStore()
            val path = DocPath.lock("u", "t1")
            store.acquireLock(path, "host-a", 10.minutes)

            store.releaseLock(path, "host-b")

            assertEquals("host-a", store.getDoc(path, LockDoc.serializer())!!.holder)
        }

    @Test
    fun `LockDoc serializer round-trips kotlin time Instant via kotlinx-serialization 1_11`() =
        runTest {
            // Regression guard: kotlinx-serialization 1.11 added native kotlin.time.Instant
            // support. If that breaks on a future bump, LockDoc fails to encode and every
            // lock-related test goes red. Pin behavior here so the failure is localized.
            val store = InMemoryMetadataStore()
            val path = DocPath.lock("u", "t1")
            store.acquireLock(path, "host-a", 5.minutes)

            val raw = store.rawDoc(path.value)!!
            assertTrue(raw.contains("holder"), "expected holder field in encoded JSON: $raw")
            assertTrue(raw.contains("acquiredAt"), "expected acquiredAt field in encoded JSON: $raw")
        }

    @Serializable
    private data class Sample(
        val name: String,
        val n: Int,
    )

    private class ControllableClock(
        private var now: Instant,
    ) : Clock {
        override fun now(): Instant = now

        fun advanceBy(by: kotlin.time.Duration) {
            now += by
        }
    }
}
