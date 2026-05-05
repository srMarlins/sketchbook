package com.sketchbook.syncio

import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HasherTest {

    @Test
    fun emptyInputProducesKnownHash() {
        // BLAKE3 of empty input is well-known.
        val hash = Hasher.hash(ByteArray(0))
        assertEquals("b3:af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262", hash.value)
    }

    @Test
    fun knownStringHashShape() {
        val hash = Hasher.hash("abc".toByteArray(Charsets.UTF_8))
        assertTrue(hash.value.startsWith("b3:"))
        assertEquals(67, hash.value.length) // "b3:" + 64 hex
        // Deterministic.
        assertEquals(hash, Hasher.hash("abc".toByteArray(Charsets.UTF_8)))
        // Pinned reference value for this BLAKE3 lib (rctcwyvrn:blake3 1.3).
        assertEquals(
            "b3:6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85",
            hash.value,
        )
    }

    @Test
    fun pathStreamMatchesByteArray() {
        val data = "the quick brown fox".toByteArray(Charsets.UTF_8)
        val tmp = createTempFile("hasher", ".bin").also { it.writeBytes(data) }
        try {
            assertEquals(Hasher.hash(data), Hasher.hash(tmp))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun streamingHandlesMultiBufferInputs() {
        // 200 KB > BUFFER_SIZE (64 KB) → exercises the multi-read loop.
        val bytes = ByteArray(200 * 1024) { (it % 256).toByte() }
        val tmp = createTempFile("hasher-large", ".bin").also { it.writeBytes(bytes) }
        try {
            assertEquals(Hasher.hash(bytes), Hasher.hash(tmp))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun availableReportsTrueOnSupportedPlatforms() {
        assertTrue(Hasher.available)
    }
}
