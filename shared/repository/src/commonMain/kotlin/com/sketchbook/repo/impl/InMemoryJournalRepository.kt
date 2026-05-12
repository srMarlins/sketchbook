package com.sketchbook.repo.impl

import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory journal — used by tests that don't want a SQLDelight driver. Production graphs
 * use `SqlJournalRepository`.
 */
class InMemoryJournalRepository : JournalRepository {
    private val state = MutableStateFlow<List<JournalEntry>>(emptyList())
    private val seqMutex = Mutex()
    private var nextSequence = 1L

    override fun observeRecent(limit: Int): Flow<List<JournalEntry>> = state.map { it.take(limit) }

    override suspend fun append(entry: JournalEntry): JournalEntry {
        val seq = seqMutex.withLock { nextSequence++ }
        val stamped = entry.copy(sequence = seq)
        state.update { listOf(stamped) + it }
        return stamped
    }

    override suspend fun undoLast(): JournalEntry? {
        // getAndUpdate atomically returns the previous list while replacing it with the tail.
        // The lambda is pure (no side effects), so we read the head off the snapshot it returns.
        val previous =
            state.getAndUpdate { current ->
                if (current.isEmpty()) current else current.drop(1)
            }
        return previous.firstOrNull()
    }
}
