package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.AlsPatchService
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.RepairFindings
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SampleCandidate
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
 *  - **Auto-match candidates** for missing-sample findings, derived from the `samples` corpus
 *    populated by `JvmSampleScanner` over `LibraryRoot.UserSamples` roots. Filename+size is the
 *    high-confidence path; an exact-1 hit there flips on `autoMatch` and the UI offers a
 *    one-click apply. Filename-only is the fallback (multiple "kick.wav" files are common in
 *    sample libraries — we surface them as `candidates` but never auto-pick).
 */
class SqlRepairRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val journal: JournalRepository,
    private val patcher: AlsPatchService,
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
            val missingFindings = withContext(ioDispatcher) {
                miss.map { row ->
                    val filename = row.sample_path
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                    val sized = row.sample_size?.takeIf { it > 0 }?.let { size ->
                        catalog.catalogQueries
                            .selectSamplesByFilenameAndSize(filename = filename, size_bytes = size)
                            .executeAsList()
                    } ?: emptyList()
                    val raw = if (sized.isNotEmpty()) sized
                    else catalog.catalogQueries.selectSamplesByFilename(filename).executeAsList()
                    val candidates = raw.take(5).map {
                        SampleCandidate(path = it.path, filename = it.filename, sizeBytes = it.size_bytes)
                    }
                    // Only flip on the auto-match chip when the corpus uniquely identifies a
                    // file by filename+size — multiple matches are too ambiguous to auto-apply.
                    // (The user can still expand the candidates list and pick one manually.)
                    val autoMatch = candidates.firstOrNull()
                        ?.takeIf { sized.isNotEmpty() && raw.size == 1 }
                    MissingSampleFinding(
                        projectId = ProjectId(row.project_id),
                        projectPath = row.project_path,
                        projectName = row.project_name,
                        missingPath = row.sample_path,
                        autoMatch = autoMatch,
                        candidates = candidates,
                    )
                }
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

    override suspend fun applyMissingSampleMatch(
        projectId: ProjectId,
        missingPath: String,
        candidatePath: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            // Resolve the on-disk .als path before any catalog mutation so the patcher and the
            // journal entry agree on which file the user actually edited (a concurrent rename
            // would otherwise split the audit trail).
            val alsPath = catalog.catalogQueries
                .selectProjectById(projectId.value)
                .executeAsOne()
                .path

            // Rewrite the .als first; the catalog flip is a tiny in-memory transaction and the
            // patch is the slow + failure-prone step. If the patch raises, we honor the user's
            // pick anyway (catalog still flips below) — the journal records the outcome so a
            // later retry pass / Undo (PR-W W4) can act on un-rewritten files.
            val outcome = runCatching {
                patcher.patch(alsPath, mapOf(missingPath to candidatePath))
            }.getOrElse { AlsPatchService.Outcome.Failed }

            catalog.transaction {
                catalog.catalogQueries.applyMissingSampleMatch(
                    new_path = candidatePath,
                    project_id = projectId.value,
                    old_path = missingPath,
                )
            }
            // Re-emit findings so the row drops out of Needs Attention immediately. The
            // underlying SQLDelight Flow does observe project_samples writes, but bouncing the
            // tick here keeps the timing tight (no relying on whether the asFlow() observer has
            // landed yet).
            ackTick.value = ackTick.value + 1

            journal.append(
                JournalEntry(
                    timestamp = Clock.System.now(),
                    projectId = projectId,
                    action = ActionRecord.MissingSampleMapped(
                        missingPath = missingPath,
                        candidatePath = candidatePath,
                        alsOutcome = outcome.name,
                    ),
                ),
            )
            Unit
        }
    }

    private companion object {
        const val SCOPE_MAC = "mac_import"
        const val SCOPE_MISS = "missing_sample"
    }
}
