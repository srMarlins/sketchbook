package com.sketchbook.repo

import com.sketchbook.core.PluginRef
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.SampleRef
import com.sketchbook.core.SketchbookError
import kotlinx.coroutines.flow.Flow

/**
 * The single seam between catalog data and feature modules. Features and the sync engine both
 * write through here; nothing reaches past this interface into SQLDelight types.
 *
 * **Read API** — Flow-based, hot. Repository owns the dispatcher choice; callers never
 * `flowOn(Dispatchers.IO)` themselves.
 *
 * **Write API** — `suspend` + `Result<JournalEntry>`. Domain-level errors are
 * [SketchbookError] subclasses wrapped via `Result.failure`; unexpected exceptions propagate.
 */
interface ProjectRepository {

    /**
     * Live list of non-archived projects matching [query]. Empty query returns everything,
     * ordered by `last_modified DESC`. Non-empty query goes through FTS5 (`MATCH`).
     */
    fun observeProjects(query: String = ""): Flow<List<ProjectRow>>

    /** Live list of archived projects, newest first. Excluded from [observeProjects]. */
    fun observeArchivedProjects(): Flow<List<ProjectRow>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Live single-project view by local PK. Emits new state whenever the row mutates. */
    fun observeProject(id: ProjectId): Flow<ProjectRow?>

    /** Plugins extracted by the parser for [id]. Empty when the project hasn't parsed yet. */
    fun observePlugins(id: ProjectId): Flow<List<PluginRef>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Sample refs the parser found, with their resolved missing/found status. */
    fun observeSamples(id: ProjectId): Flow<List<SampleEntry>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Move the project's working tree to a new parent directory. Path-rename only; no FS I/O. */
    suspend fun move(id: ProjectId, newParentDir: String): Result<JournalEntry>

    /** Rename the project (catalog-level — folder rename is the caller's job). */
    suspend fun rename(id: ProjectId, newName: String): Result<JournalEntry>

    /** Toggle archived. Same call archives or unarchives based on current state. */
    suspend fun archive(id: ProjectId, archived: Boolean = true): Result<JournalEntry>

    /** Replace the project's tag set (creates missing tags as a side effect). */
    suspend fun setTags(id: ProjectId, tags: List<String>): Result<JournalEntry>
}

/**
 * Per-project sample entry as the UI sees it. The parser writes raw paths plus a missing flag
 * to `project_samples`; this is a typed view over those rows. Distinct from [SampleRef] so the
 * domain `SampleRef` (which lives on `ProjectMetadata`) doesn't have to carry resolution state.
 */
data class SampleEntry(
    val rawPath: String,
    val isMissing: Boolean,
    val sizeBytes: Long?,
) {
    /** Last path component — what the user sees in the Samples tab. */
    val displayName: String
        get() = rawPath.substringAfterLast('/').substringAfterLast('\\').ifEmpty { rawPath }
}

/** Convenience: report a [SketchbookError] as a `Result.failure`. */
internal fun <T> failed(error: SketchbookError): Result<T> = Result.failure(error)
