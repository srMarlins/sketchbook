package com.sketchbook.syncio

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmWorkingTreeIsInUseTest {
    private val tmp = createTempDirectory("isinuse-")

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun returnsFalseWhenFileMissing() {
        val missing = tmp.resolve("nope.als")
        assertFalse(isInUse(missing))
    }

    @Test
    fun returnsFalseForLockableFile() {
        val path = tmp.resolve("free.als")
        Files.writeString(path, "ok")
        assertFalse(isInUse(path), "freshly written file should be lockable")
    }

    @Test
    fun returnsTrueWhileAnotherChannelHoldsExclusiveLock() {
        val path = tmp.resolve("held.als")
        Files.writeString(path, "ok")
        val ch = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
        val lock = ch.lock()
        try {
            assertTrue(isInUse(path), "file held by exclusive lock should report busy")
        } finally {
            lock.release()
            ch.close()
        }
        // After releasing the lock the file should be lockable again.
        assertFalse(isInUse(path))
    }
}
