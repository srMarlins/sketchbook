package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.Stage
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.ProjectFtsSearcher
import com.sketchbook.repo.ProjectRepository
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * SQLDelight-backed [ProjectRepository]. Reads via SQLDelight `Query.asFlow()` so observers see
 * live updates after writes. Writes go through `transactionWithResult { }` so journal-emit and
 * row-mutate are atomic (either both happen or neither).
 *
 * FTS search uses raw driver calls (see `:shared:catalog`'s `CatalogFts`); the JVM-only impl
 * is adapted to [ProjectFtsSearcher] in the desktop graph so this class stays in `commonMain`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlProjectRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val journal: JournalRepository,
    private val fts: ProjectFtsSearcher,
    private val clock: Clock = Clock.System,
) : ProjectRepository {

    private val ftsTrigger = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeProjects(query: String): Flow<List<ProjectRow>> {
        val q = query.trim()
        return if (q.isEmpty()) {
            // Combine the rows flow with a bulk tag-pairs flow. SQLDelight re-emits both whenever
            // the underlying tables change, so a setTags() call in another component flows through
            // to the chips on this list without a manual invalidation. The pairs query returns at
            // most a few thousand rows on a 1,628-project library — bulk-grouping in memory is
            // cheaper than a per-row tag query (1,628 round-trips vs 1).
            combine(
                catalog.catalogQueries.selectAllProjectsWithMissing()
                    .asFlow()
                    .mapToList(ioDispatcher),
                catalog.catalogQueries.selectAllProjectTagPairs()
                    .asFlow()
                    .mapToList(ioDispatcher),
            ) { rows, pairs ->
                val tagsByProject = pairs.groupBy({ it.project_id }, { it.name })
                rows.map { it.toDomain(tags = tagsByProject[it.id].orEmpty()) }
            }
        } else {
            // FTS search produces a row-id list; map back to full rows. Re-runs whenever
            // ftsTrigger ticks (after writes that may invalidate prior search results). The
            // FTS callback is a blocking JDBC call, so hop to ioDispatcher before invoking it
            // — the caller's dispatcher is typically the UI's stateIn scope, where blocking
            // SQLite would stall recomposition.
            ftsTrigger.flatMapLatest {
                val ids = withContext(ioDispatcher) { fts.search(q) }
                if (ids.isEmpty()) flowOf(emptyList()) else loadByIds(ids)
            }
        }
    }

    override fun observeArchivedProjects(): Flow<List<ProjectRow>> =
        combine(
            catalog.catalogQueries.selectArchivedProjectsWithMissing()
                .asFlow()
                .mapToList(ioDispatcher),
            catalog.catalogQueries.selectAllProjectTagPairs()
                .asFlow()
                .mapToList(ioDispatcher),
        ) { rows, pairs ->
            val tagsByProject = pairs.groupBy({ it.project_id }, { it.name })
            rows.map { it.toDomain(tags = tagsByProject[it.id].orEmpty()) }
        }

    override fun observeDistinctKeys(): Flow<List<String>> =
        catalog.catalogQueries.selectDistinctKeys()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.mapNotNull { it } }

    override fun observeProject(id: ProjectId): Flow<ProjectRow?> =
        combine(
            catalog.catalogQueries.selectProjectByIdWithMissing(id.value)
                .asFlow()
                .mapToOneOrNull(ioDispatcher),
            catalog.catalogQueries.selectTagsForProject(id.value)
                .asFlow()
                .mapToList(ioDispatcher),
        ) { row, tags -> row?.toDomain(tags = tags) }

    override fun observePlugins(id: ProjectId): Flow<List<com.sketchbook.core.PluginRef>> =
        catalog.catalogQueries.selectPluginsForProject(id.value)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows ->
                rows.map { r ->
                    com.sketchbook.core.PluginRef(
                        name = r.plugin_name,
                        format = pluginFormatFor(r.plugin_type),
                        trackName = r.track_name,
                    )
                }
            }

    /**
     * PR-BB: library health stream. SQLDelight's `asFlow()` already re-emits on any write to
     * `projects` / `sync_state` / `project_samples` (the tables the query reads), but per the
     * PR-BB spec we *also* trigger off the journal's most-recent entry — that catches sync-engine
     * writes that bypass the project repo (the journal sees them all). The combine collapses both
     * sources to a fresh aggregate on either signal. `EMPTY` is the seed so the chip renders a
     * grey "Health: —" until the first DB read lands rather than blocking the sidebar.
     */
    override fun observeLibraryHealth(): Flow<com.sketchbook.repo.LibraryHealth> =
        combine(
            catalog.catalogQueries.selectLibraryHealth().asFlow().mapToOneOrNull(ioDispatcher),
            journal.observeRecent(limit = 1),
        ) { row, _ ->
            row?.let {
                com.sketchbook.repo.LibraryHealth(
                    total = it.total.toInt(),
                    // SUM over zero rows is NULL; coalesce to 0 so the UI sees a clean numeric.
                    synced = (it.synced ?: 0L).toInt(),
                    sampleClean = (it.sample_clean ?: 0L).toInt(),
                )
            } ?: com.sketchbook.repo.LibraryHealth.EMPTY
        }

    override fun observeMissingPluginCoverage(): Flow<List<com.sketchbook.repo.MissingPluginRow>> =
        catalog.catalogQueries.selectMissingPluginCoverage()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows ->
                rows.map { r ->
                    com.sketchbook.repo.MissingPluginRow(
                        name = r.name,
                        format = pluginFormatFor(r.type),
                        affectedProjects = r.affected_projects.toInt(),
                    )
                }
            }

    override fun observeMissingPluginSummary(): Flow<com.sketchbook.repo.MissingPluginSummary?> =
        catalog.catalogQueries.selectMissingPluginSummary()
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { row ->
                row?.let {
                    com.sketchbook.repo.MissingPluginSummary(
                        missingPluginCount = it.missing_count.toInt(),
                        affectedProjects = it.affected_projects.toInt(),
                    )
                }
            }

    override fun observeSamples(id: ProjectId): Flow<List<com.sketchbook.repo.SampleEntry>> =
        catalog.catalogQueries.selectSampleEntriesForProject(id.value)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows ->
                rows.map { r ->
                    com.sketchbook.repo.SampleEntry(
                        rawPath = r.sample_path,
                        isMissing = r.is_missing != 0L,
                        sizeBytes = r.size_bytes,
                    )
                }
            }

    private fun pluginFormatFor(raw: String): com.sketchbook.core.PluginFormat = when (raw.lowercase()) {
        "vst2" -> com.sketchbook.core.PluginFormat.Vst2
        "vst3" -> com.sketchbook.core.PluginFormat.Vst3
        "au" -> com.sketchbook.core.PluginFormat.Au
        "abletonnative" -> com.sketchbook.core.PluginFormat.AbletonNative
        else -> com.sketchbook.core.PluginFormat.Unknown
    }

    // Inverse of pluginFormatFor — maps the typed enum back to the lowercase string the parser
    // writes into project_plugins.plugin_type. Kept private to this file so the SQL string
    // representation stays an implementation detail.
    private fun pluginTypeStringFor(format: com.sketchbook.core.PluginFormat): String? = when (format) {
        com.sketchbook.core.PluginFormat.Vst2 -> "vst2"
        com.sketchbook.core.PluginFormat.Vst3 -> "vst3"
        com.sketchbook.core.PluginFormat.Au -> "au"
        com.sketchbook.core.PluginFormat.AbletonNative -> "abletonnative"
        // Unknown is a UI-side fallback for parser misses; it's never written into the catalog,
        // so passing it through as a filter would always return zero rows. Treat it as "any".
        com.sketchbook.core.PluginFormat.Unknown -> null
    }

    override fun observeProjectsUsing(
        pluginName: String,
        format: com.sketchbook.core.PluginFormat?,
        excludeProjectId: ProjectId?,
    ): Flow<List<com.sketchbook.repo.PluginUsage>> =
        catalog.catalogQueries.selectProjectsUsingPlugin(
            plugin_name = pluginName,
            plugin_type = format?.let { pluginTypeStringFor(it) },
            exclude_id = excludeProjectId?.value,
        )
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows ->
                rows.map { r ->
                    com.sketchbook.repo.PluginUsage(
                        id = ProjectId(r.id),
                        name = r.name,
                        path = r.path,
                        lastModified = r.last_modified,
                    )
                }
            }

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

    override suspend fun setStageOverride(
        id: ProjectId,
        stage: Stage?,
    ): Result<JournalEntry> = mutate(id) { row ->
        val before = row.stage_override
        val inferred = row.stage_inferred
        catalog.catalogQueries.updateStageOverride(
            stage_override = stage?.name,
            id = id.value,
        )
        ActionRecord.StageOverridden(
            stageInferred = inferred,
            stageBefore = before,
            stageAfter = stage?.name,
        )
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
            ftsTrigger.update { it + 1 }
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
            ftsTrigger.update { it + 1 }
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
        // Batch fetch + preserve relevance order from the FTS list. Tags are joined via the
        // `selectTagPairsForProjects` IN-clause query so the chips populate on the search
        // results too, not just the home dashboard.
        return combine(
            catalog.catalogQueries.selectProjectsByIds(ids)
                .asFlow()
                .mapToList(ioDispatcher),
            catalog.catalogQueries.selectTagPairsForProjects(ids)
                .asFlow()
                .mapToList(ioDispatcher),
        ) { rows, pairs ->
            val byId = rows.associateBy { it.id }
            val tagsByProject = pairs.groupBy({ it.project_id }, { it.name })
            ids.mapNotNull { id -> byId[id]?.toDomain(tags = tagsByProject[id].orEmpty()) }
        }
    }
}
