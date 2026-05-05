package com.sketchbook.repo.impl

import com.sketchbook.core.SketchbookError
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory journal. Used in tests today and as the default until PR-8 (`actions`) lands a
 * disk writer that round-trips with the v0.1 Python format.
 */
class InMemoryJournalRepository : JournalRepository {

    private val state = MutableStateFlow<List<JournalEntry>>(emptyList())
    private val seqMutex = Mutex()
    private var nextSequence = 1L

    override fun observeRecent(limit: Int): Flow<List<JournalEntry>> =
        state.map { it.take(limit) }

    override suspend fun append(entry: JournalEntry): Result<JournalEntry> {
        val seq = seqMutex.withLock { nextSequence++ }
        val stamped = entry.copy(sequence = seq)
        state.value = listOf(stamped) + state.value
        return Result.success(stamped)
    }

    override suspend fun undoLast(): Result<JournalEntry> {
        val current = state.value
        if (current.isEmpty()) {
            return Result.failure(SketchbookError.NotFound("journal is empty"))
        }
        val head = current.first()
        state.value = current.drop(1)
        return Result.success(head)
    }
}
