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

class BlobInstallerTest {
    private val root = createTempDirectory("blob-installer-test-")

    @AfterTest fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun hardlinksOnSameVolume() {
        val blob = root.resolve("blob.bin").also { it.writeBytes("hello".toByteArray()) }
        val target = root.resolve("project/Samples/k.wav")

        val outcome = BlobInstaller.install(blob, target)

        assertEquals(BlobInstaller.Outcome.Hardlinked, outcome)
        assertTrue(Files.exists(target))
        assertEquals("hello", Files.readString(target))
        assertTrue(BlobInstaller.sameInode(blob, target))
    }

    @Test
    fun idempotentReturnsAlreadyPresent() {
        val blob = root.resolve("blob.bin").also { it.writeBytes("hello".toByteArray()) }
        val target = root.resolve("project/Samples/k.wav")
        BlobInstaller.install(blob, target)

        val again = BlobInstaller.install(blob, target)
        assertEquals(BlobInstaller.Outcome.AlreadyPresent, again)
    }

    @Test
    fun refusesToClobberDifferentContent() {
        val blob = root.resolve("blob.bin").also { it.writeBytes("hello".toByteArray()) }
        val target =
            root.resolve("project/Samples/k.wav").also {
                Files.createDirectories(it.parent)
                it.writeBytes("other content".toByteArray())
            }
        assertFailsWith<FileAlreadyExistsException> {
            BlobInstaller.install(blob, target)
        }
    }

    @Test
    fun rejectsBlobThatIsNotARegularFile() {
        val blob = root.resolve("nope.bin")
        val target = root.resolve("project/Samples/k.wav")
        assertFailsWith<IllegalArgumentException> {
            BlobInstaller.install(blob, target)
        }
    }
}
