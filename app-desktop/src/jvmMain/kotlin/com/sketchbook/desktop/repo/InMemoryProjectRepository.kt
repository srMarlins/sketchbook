package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Stub `ProjectRepository` for the desktop shell so the Metro graph wires up before the SQL
 * impl reaches `commonMain`. Holds an in-memory list seeded by [seed] so the UI has something
 * to render at first launch.
 */
class InMemoryProjectRepository(
    seed: List<ProjectRow> = emptyList(),
    private val clock: Clock = Clock.System,
) : ProjectRepository {

    private val rows = MutableStateFlow(seed)

    /**
     * Replace all rows. Used by the desktop scanner when a library root is added/refreshed —
     * the scanner reads the filesystem, builds a list of [ProjectRow]s, and hands them here.
     */
    fun replaceAll(newRows: List<ProjectRow>) {
        rows.value = newRows
    }

    /** Append rows discovered by an incremental scan; deduped by id. */
    fun addRows(newRows: List<ProjectRow>) {
        rows.value = (rows.value + newRows).distinctBy { it.id.value }
    }

    override fun observeProjects(query: String): Flow<List<ProjectRow>> = rows.map { all ->
        if (query.isBlank()) all else all.filter { query.lowercase() in it.name.lowercase() }
    }

    override fun observeProject(id: ProjectId): Flow<ProjectRow?> = rows.map { all ->
        all.firstOrNull { it.id == id }
    }

    override suspend fun move(id: ProjectId, newParentDir: String): Result<JournalEntry> =
        Result.success(stubEntry(id, ActionRecord.Move("", newParentDir)))

    override suspend fun rename(id: ProjectId, newName: String): Result<JournalEntry> =
        Result.success(stubEntry(id, ActionRecord.Rename("", newName)))

    override suspend fun archive(id: ProjectId, archived: Boolean): Result<JournalEntry> =
        Result.success(stubEntry(id, ActionRecord.Archive(!archived, archived)))

    override suspend fun setTags(id: ProjectId, tags: List<String>): Result<JournalEntry> =
        Result.success(stubEntry(id, ActionRecord.SetTags(emptyList(), tags)))

    private fun stubEntry(id: ProjectId, action: ActionRecord): JournalEntry =
        JournalEntry(timestamp = clock.now(), projectId = id, action = action)
}
