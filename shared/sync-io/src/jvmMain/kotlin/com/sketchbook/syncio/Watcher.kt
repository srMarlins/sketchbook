package com.sketchbook.syncio

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * File-watcher Flow over a directory tree. Wraps `io.methvin:directory-watcher` so callers see
 * a coroutines-native API.
 *
 * **Debounce:** Live's save dance writes a temp file, fsyncs, then renames over the target. We
 * collapse rapid Modify/Create runs on the same path to a single [SaveEvent.Saved] emitted
 * [debounce] after the last raw event for that path.
 */
class Watcher(
    private val debounce: Duration = 300.milliseconds,
    private val clock: Clock = Clock.System,
) {

    fun watch(root: Path): Flow<SaveEvent> = channelFlow {
        val pending = ConcurrentHashMap<Path, Long>() // path → epochMs of latest raw event
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
                        pending[target] = clock.now().toEpochMilliseconds()
                        saveScope.launch {
                            delay(debounce)
                            val lastSeen = pending[target] ?: return@launch
                            val now = clock.now().toEpochMilliseconds()
                            if (now - lastSeen >= debounce.inWholeMilliseconds) {
                                if (pending.remove(target, lastSeen)) {
                                    trySend(SaveEvent.Saved(target, kotlinx.datetime.Instant.fromEpochMilliseconds(now)))
                                }
                            }
                        }
                    }
                    DirectoryChangeEvent.EventType.DELETE -> {
                        pending.remove(target)
                        trySend(SaveEvent.Removed(target, clock.now()))
                    }
                    DirectoryChangeEvent.EventType.OVERFLOW -> Unit
                }
            }
            .build()

        watcher.watchAsync()
        awaitClose { watcher.close() }
    }
}
