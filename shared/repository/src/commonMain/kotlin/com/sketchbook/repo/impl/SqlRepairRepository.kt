package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.RepairFindings
import com.sketchbook.repo.RepairRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Catalog-backed [RepairRepository]. Findings are *derived* on every observe — the catalog is
 * the single source of truth, so there's no separate "findings" table to keep in sync. The
 * repository surface only adds:
 *
 *  - **Persistent acknowledgements** via the `repair_acks` table. Without this, a dismissed
 *    mac-import would re-surface on the next scan because `mac_paths_count` is permanent on
 *    the project row. Acks are scoped per `(kind, project_id, payload)` so dismissing one
 *    missing sample doesn't mute the other 17 misses on the same project.
 *
 *  - **Truncation metadata** so the UI can show "N more not shown" without doing its own
 *    count query — `selectMissingSamples` caps at `limit` and `countMissingSamples` is a
 *    separate aggregate.
 *
 * Auto-match suggestions for missing samples (the [MissingSampleFinding.autoMatch] /
 * `candidates` fields) are not yet computed here — they need a `samples` corpus query that
 * walks user library roots, which the v1 scanner doesn't populate. PR-C.2 will wire that.
 */
class SqlRepairRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
) : RepairRepository {

    /**
     * Bumped on every ack write so observers re-emit. SQLDelight's hot Flow already covers the
     * underlying selects, but `selectMacImportProjects` references `repair_acks` — that table
     * isn't tracked by the auto-Flow because it isn't the queried table. The trigger covers it.
     */
    private val ackTick = MutableStateFlow(0L)

    override fun observeFindings(projectId: ProjectId?, limit: Int): Flow<RepairFindings> {
        // Mac-import + missing-sample queries combined into a single emission. Re-runs whenever
        // either underlying query updates OR the ack tick fires (a dismissal happened).
        val macFlow = catalog.catalogQueries.selectMacImportProjects()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.filter { projectId == null || it.id == projectId.value } }
        val missingFlow = catalog.catalogQueries.selectMissingSamples(limit.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.filter { projectId == null || it.project_id == projectId.value } }
        return combine(macFlow, missingFlow, ackTick.onStart { emit(0L) }) { mac, miss, _ ->
            val macFindings = mac.map { row ->
                MacImportFinding(
                    projectId = ProjectId(row.id),
                    path = row.path,
                    name = row.name,
                    parentDir = row.parent_dir,
                    macPathsCount = (row.mac_paths_count ?: 0L).toInt(),
                    projectInfoMissing = (row.has_project_info ?: 1L) == 0L,
                )
            }
            val missingFindings = miss.map { row ->
                MissingSampleFinding(
                    projectId = ProjectId(row.project_id),
                    projectPath = row.project_path,
                    projectName = row.project_name,
                    missingPath = row.sample_path,
                )
            }
            val total = withContext(ioDispatcher) {
                catalog.catalogQueries.countMissingSamples().executeAsOne().toInt()
            }
            RepairFindings(
                macImports = macFindings,
                missingSamples = missingFindings,
                missingSamplesTotal = total,
                missingSamplesTruncated = total > missingFindings.size,
            )
        }
    }

    override suspend fun acknowledgeMacImport(projectId: ProjectId): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                catalog.transaction {
                    catalog.catalogQueries.insertRepairAck(
                        scope = SCOPE_MAC,
                        project_id = projectId.value,
                        payload = "",
                        acked_at = Clock.System.now().toString(),
                    )
                }
                ackTick.value = ackTick.value + 1
            }
        }

    override suspend fun dismissMissingSample(
        projectId: ProjectId,
        missingPath: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            catalog.transaction {
                catalog.catalogQueries.insertRepairAck(
                    scope = SCOPE_MISS,
                    project_id = projectId.value,
                    payload = missingPath,
                    acked_at = Clock.System.now().toString(),
                )
            }
            ackTick.value = ackTick.value + 1
        }
    }

    private companion object {
        const val SCOPE_MAC = "mac_import"
        const val SCOPE_MISS = "missing_sample"
    }
}
