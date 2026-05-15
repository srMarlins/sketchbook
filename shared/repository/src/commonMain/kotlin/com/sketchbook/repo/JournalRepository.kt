package com.sketchbook.repo

import com.sketchbook.core.SketchbookError
import kotlinx.coroutines.flow.Flow

/**
 * Append-only journal of project actions. Mirrors v0.1 semantics so Python and Kotlin can read
 * each other's entries during the parity period.
 *
 * v0.1 stored entries as JSON files under `data/journal/<timestamp>.json`. PR-8 (`actions`)
 * adds the disk writer; this interface stays storage-agnostic.
 */
interface JournalRepository {
    /** Live tail, most recent first, capped at [limit]. */
    fun observeRecent(limit: Int = 100): Flow<List<JournalEntry>>

    /**
     * Append a new entry. Returns the entry with its assigned [JournalEntry.sequence].
     * Throws [SketchbookError.IoFailure] on a catalog write failure.
     */
    @Throws(SketchbookError::class)
    suspend fun append(entry: JournalEntry): JournalEntry

    /**
     * Pop the most recent entry; the returned entry is the one to undo. Returns `null` when the
     * journal is empty. Throws [SketchbookError.IoFailure] on a catalog write failure.
     */
    @Throws(SketchbookError::class)
    suspend fun undoLast(): JournalEntry?
}
