package com.sketchbook.sync

import com.sketchbook.cloud.Generation
import com.sketchbook.core.CloudDocKey
import com.sketchbook.core.SketchbookError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour tests for the [com.sketchbook.cloud.CloudBackend] CloudDoc methods through the
 * shared [FakeCloudBackend]. The DirectGcsBackend impl gets HTTP-level coverage in
 * :shared:cloud:jvmTest.
 */
class CloudDocTest {
    private val key = CloudDocKey("registry.json")

    @Test
    fun readDocReturnsNullWhenAbsent() =
        runTest {
            val cloud = FakeCloudBackend()
            assertNull(cloud.readDoc(key))
        }

    @Test
    fun writeThenReadRoundTrips() =
        runTest {
            val cloud = FakeCloudBackend()
            val bytes = "{\"v\":1}".encodeToByteArray()
            val gen = cloud.writeDoc(key, expected = Generation.ZERO, bytes = bytes).getOrThrow()
            val read = cloud.readDoc(key)!!
            assertEquals(bytes.toList(), read.bytes.toList())
            assertEquals(gen, read.generation)
        }

    @Test
    fun writeWithMustNotExistFailsWhenPresent() =
        runTest {
            val cloud = FakeCloudBackend()
            cloud.writeDoc(key, Generation.ZERO, "a".encodeToByteArray()).getOrThrow()
            val r = cloud.writeDoc(key, Generation.ZERO, "b".encodeToByteArray())
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull() is SketchbookError.Conflict)
        }

    @Test
    fun writeCasFailsOnGenerationMismatch() =
        runTest {
            val cloud = FakeCloudBackend()
            cloud.writeDoc(key, Generation.ZERO, "a".encodeToByteArray()).getOrThrow()
            val r = cloud.writeDoc(key, Generation("999"), "b".encodeToByteArray())
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull() is SketchbookError.Conflict)
        }

    @Test
    fun writeCasSucceedsOnMatchingGeneration() =
        runTest {
            val cloud = FakeCloudBackend()
            val gen1 = cloud.writeDoc(key, Generation.ZERO, "a".encodeToByteArray()).getOrThrow()
            val gen2 = cloud.writeDoc(key, gen1, "b".encodeToByteArray()).getOrThrow()
            val read = cloud.readDoc(key)!!
            assertEquals(gen2, read.generation)
            assertEquals("b", read.bytes.decodeToString())
        }

    @Test
    fun listDocsFiltersByPrefix() =
        runTest {
            val cloud = FakeCloudBackend()
            cloud.writeDoc(CloudDocKey("profile/plugin_manifest_a.json"), null, byteArrayOf(1)).getOrThrow()
            cloud.writeDoc(CloudDocKey("profile/plugin_manifest_b.json"), null, byteArrayOf(2)).getOrThrow()
            cloud.writeDoc(CloudDocKey("registry.json"), null, byteArrayOf(3)).getOrThrow()
            val refs = cloud.listDocs(CloudDocKey.Prefix("profile/"))
            assertEquals(2, refs.size)
            assertTrue(refs.all { it.key.path.startsWith("profile/") })
        }
}
