package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.SketchbookError
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * SQLDelight-backed [ProjectRepository]. Reads via SQLDelight `Query.asFlow()` so observers see
 * live updates after writes. Writes go through `transactionWithResult { }` so journal-emit and
 * row-mutate are atomic (either both happen or neither).
 *
 * FTS search uses raw driver calls (see `:shared:catalog`'s `CatalogFts`); the JVM-only impl
 * supplies its [ftsSearch] callback so this class stays in `commonMain`.
 */
class SqlProjectRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val journal: JournalRepository,
    /** Returns matching project ids for a non-empty FTS query. Empty query → null (use list). */
    private val ftsSearch: (String) -> List<Long>,
    private val clock: Clock = Clock.System,
) : ProjectRepository {

    private val ftsTrigger = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeProjects(query: String): Flow<List<ProjectRow>> {
        val q = query.trim()
        return if (q.isEmpty()) {
            catalog.catalogQueries.selectAllProjects()
                .asFlow()
                .mapToList(ioDispatcher)
                .map { rows -> rows.map { it.toDomain() } }
        } else {
            // FTS search produces a row-id list; map back to full rows. Re-runs whenever
            // ftsTrigger ticks (after writes that may invalidate prior search results).
            ftsTrigger.flatMapLatest {
                val ids = ftsSearch(q)
                if (ids.isEmpty()) flowOf(emptyList()) else loadByIds(ids)
            }
        }
    }

    override fun observeProject(id: ProjectId): Flow<ProjectRow?> =
        catalog.catalogQueries.selectProjectById(id.value)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { row -> row?.toDomain() }

    override suspend fun move(id: ProjectId, newParentDir: String): Result<JournalEntry> =
        mutate(id) { row ->
            val pathBefore = row.path
            val newPath = "$newParentDir/${row.name}.als"
            catalog.catalogQueries.updateProjectPath(
                path = newPath,
                parent_dir = newParentDir,
                id = id.value,
            )
            ActionRecord.Move(pathBefore = pathBefore, pathAfter = newPath)
        }

    override suspend fun rename(id: ProjectId, newName: String): Result<JournalEntry> =
        mutate(id) { row ->
            val nameBefore = row.name
            val newPath = row.path.substringBeforeLast('/') + "/$newName.als"
            catalog.catalogQueries.updateProjectName(
                name = newName,
                path = newPath,
                id = id.value,
            )
            ActionRecord.Rename(nameBefore = nameBefore, nameAfter = newName)
        }

    override suspend fun archive(id: ProjectId, archived: Boolean): Result<JournalEntry> =
        mutate(id) { row ->
            val wasArchived = row.is_archived != 0L
            // Direct UPDATE rather than insertOrReplace (avoids re-writing every column).
            catalog.transaction {
                catalog.catalogQueries.setArchived(if (archived) 1 else 0, id.value)
            }
            ActionRecord.Archive(wasArchived = wasArchived, isArchived = archived)
        }

    override suspend fun setTags(id: ProjectId, tags: List<String>): Result<JournalEntry> {
        return withContext(ioDispatcher) {
            val row = catalog.catalogQueries.selectProjectById(id.value).executeAsOneOrNull()
                ?: return@withContext Result.failure<JournalEntry>(
                    SketchbookError.NotFound("project $id not found"),
                )
            val before = catalog.catalogQueries.selectTagsForProject(id.value).executeAsList()
            catalog.transaction {
                catalog.catalogQueries.clearTagsForProject(id.value)
                for (tag in tags.distinct()) {
                    catalog.catalogQueries.insertTagIfAbsent(tag)
                    catalog.catalogQueries.attachTagToProject(id.value, tag)
                }
            }
            val entry = JournalEntry(
                timestamp = clock.now(),
                projectId = id,
                action = ActionRecord.SetTags(before = before, after = tags),
            )
            ftsTrigger.value++
            journal.append(entry)
        }
    }

    private suspend inline fun mutate(
        id: ProjectId,
        crossinline build: (com.sketchbook.catalog.db.Projects) -> ActionRecord,
    ): Result<JournalEntry> {
        return withContext(ioDispatcher) {
            val row = catalog.catalogQueries.selectProjectById(id.value).executeAsOneOrNull()
                ?: return@withContext Result.failure<JournalEntry>(
                    SketchbookError.NotFound("project $id not found"),
                )
            val record = catalog.transactionWithResult<ActionRecord> { build(row) }
            ftsTrigger.value++
            journal.append(
                JournalEntry(
                    timestamp = clock.now(),
                    projectId = id,
                    action = record,
                ),
            )
        }
    }

    private fun loadByIds(ids: List<Long>): Flow<List<ProjectRow>> {
        // Batch fetch + preserve relevance order from the FTS list.
        return catalog.catalogQueries.selectProjectsByIds(ids)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows ->
                val byId = rows.associateBy { it.id }
                ids.mapNotNull { byId[it]?.toDomain() }
            }
    }
}
