package com.sketchbook.syncio

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MaterializerTest {

    private val root = createTempDirectory("materializer-test-")

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test
    fun hardlinksOnSameVolume() {
        val blob = root.resolve("blob.bin").also { it.writeBytes("hello".toByteArray()) }
        val target = root.resolve("project/Samples/k.wav")

        val outcome = Materializer.materialize(blob, target)

        assertEquals(Materializer.Outcome.Hardlinked, outcome)
        assertTrue(Files.exists(target))
        assertEquals("hello", Files.readString(target))
        assertTrue(Materializer.sameInode(blob, target))
    }

    @Test
    fun idempotentReturnsAlreadyPresent() {
        val blob = root.resolve("blob.bin").also { it.writeBytes("hello".toByteArray()) }
        val target = root.resolve("project/Samples/k.wav")
        Materializer.materialize(blob, target)

        val again = Materializer.materialize(blob, target)
        assertEquals(Materializer.Outcome.AlreadyPresent, again)
    }

    @Test
    fun refusesToClobberDifferentContent() {
        val blob = root.resolve("blob.bin").also { it.writeBytes("hello".toByteArray()) }
        val target = root.resolve("project/Samples/k.wav").also {
            Files.createDirectories(it.parent)
            it.writeBytes("other content".toByteArray())
        }
        assertFailsWith<FileAlreadyExistsException> {
            Materializer.materialize(blob, target)
        }
    }

    @Test
    fun rejectsBlobThatIsNotARegularFile() {
        val blob = root.resolve("nope.bin")
        val target = root.resolve("project/Samples/k.wav")
        assertFailsWith<IllegalArgumentException> {
            Materializer.materialize(blob, target)
        }
    }
}
