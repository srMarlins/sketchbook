package com.sketchbook.syncio

import app.cash.turbine.test
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WatcherTest {

    private val root = createTempDirectory("watcher-test-")

    @AfterTest fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun emitsSingleSavedAfterDebounceWindow() = kotlinx.coroutines.runBlocking {
        val watcher = Watcher(debounce = 200.milliseconds)
        val target = root.resolve("Project.als")
        watcher.watch(root).test(timeout = 10.seconds) {
            delay(150)

            target.writeBytes("v1".toByteArray())
            delay(50)
            target.writeBytes("v2".toByteArray())

            val event = awaitItem()
            assertEquals(target, event.path)
            assertEquals(true, event is SaveEvent.Saved)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emitsRemovedOnDelete() = kotlinx.coroutines.runBlocking {
        val watcher = Watcher(debounce = 100.milliseconds)
        val target = root.resolve("Project.als")
        target.writeBytes("v1".toByteArray())
        watcher.watch(root).test(timeout = 10.seconds) {
            delay(200)
            target.deleteIfExists()
            while (true) {
                val event = awaitItem()
                if (event is SaveEvent.Removed && event.path == target) break
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
