package com.sketchbook.syncio

import java.nio.file.Path
import kotlin.time.Instant

/**
 * Filesystem save event surfaced by [Watcher]. One event per debounced write — Live's
 * atomic-rename save dance is collapsed to a single `Saved` event.
 */
sealed interface SaveEvent {
    val path: Path
    val timestamp: Instant

    data class Saved(override val path: Path, override val timestamp: Instant) : SaveEvent
    data class Removed(override val path: Path, override val timestamp: Instant) : SaveEvent
}
