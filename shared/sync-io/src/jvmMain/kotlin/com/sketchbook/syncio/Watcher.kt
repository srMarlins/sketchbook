package com.sketchbook.syncio

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * File-watcher Flow over a directory tree. Wraps `io.methvin:directory-watcher` so callers see
 * a coroutines-native API.
 *
 * **Per-path debounce:** Live's save dance writes a temp file, fsyncs, then renames over the
 * target. A render-export burst can fire dozens of CREATE/MODIFY events on the same path in
 * milliseconds. We coalesce those by holding a single per-path [Job] that, on each new event,
 * cancels its predecessor and re-arms a [debounce]-length timer. Only the last timer fires —
 * one [SaveEvent.Saved] per save, regardless of how noisy the underlying filesystem is.
 *
 * The previous implementation `launch`ed a new coroutine per raw event and let them race on a
 * shared map; under a 50-event burst that meant 50 simultaneous coroutines and a polling check
 * to figure out which one was "last." This version is one coroutine per outstanding path.
 */
class Watcher(
    private val debounce: Duration = 300.milliseconds,
    private val clock: Clock = Clock.System,
) {

    fun watch(root: Path): Flow<SaveEvent> = channelFlow {
        // Per-path pending debounce job. Atomic replace+cancel pattern: each new event swaps in
        // a fresh job and cancels whatever was there. Cleared by the job itself once it fires.
        val pending = ConcurrentHashMap<Path, Job>()
        val saveScope = this

        val watcher = DirectoryWatcher.builder()
            .path(root)
            .listener { event ->
                if (!saveScope.isActive) return@listener
                val target = event.path() ?: return@listener
                when (event.eventType()) {
                    DirectoryChangeEvent.EventType.CREATE,
                    DirectoryChangeEvent.EventType.MODIFY,
                    -> {
                        val newJob = saveScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                            delay(debounce)
                            val now = clock.now()
                            // Only emit if we're still the registered job for this path.
                            // If a later event swapped us out, our cancellation already fired.
                            pending.remove(target, coroutineContext[Job])
                            trySend(SaveEvent.Saved(target, now))
                        }
                        val previous = pending.put(target, newJob)
                        previous?.cancel()
                        newJob.start()
                    }
                    DirectoryChangeEvent.EventType.DELETE -> {
                        // Cancel any in-flight Save debounce for the same path so a delete
                        // immediately after a save doesn't race.
                        pending.remove(target)?.cancel()
                        trySend(SaveEvent.Removed(target, clock.now()))
                    }
                    DirectoryChangeEvent.EventType.OVERFLOW -> Unit
                }
            }
            .build()

        watcher.watchAsync()
        awaitClose {
            // Cancel any outstanding debouncers so a graceful shutdown doesn't strand
            // coroutines that would emit after the channel is closed.
            pending.values.forEach { it.cancel() }
            pending.clear()
            watcher.close()
        }
    }
}
