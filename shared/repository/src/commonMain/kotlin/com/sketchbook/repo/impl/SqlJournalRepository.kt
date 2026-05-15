package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.catalog.db.Journal_entries
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.core.SketchbookError
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.time.Instant

/**
 * SQLDelight-backed [JournalRepository] persisting into the `journal_entries` table from
 * migration 3.sqm. Each [ActionRecord] variant is serialized via kotlinx.serialization into the
 * `payload_json` column with the variant's [ActionRecord.typeKey] written to `action_type`, so
 * the column is human-readable and SQL filters (`action_type = 'Move'`) work without parsing
 * JSON. The keys are stable strings declared on the sealed hierarchy itself — see the contract
 * doc on [ActionRecord] — so they survive R8/ProGuard renames if those ever land.
 *
 * Mirrors [SqlProjectRepository]'s style: constructor-injected [Catalog], `withContext(ioDispatcher)`
 * around blocking JDBC calls, and `transactionWithResult { }` so the insert + the
 * `last_insert_rowid()` lookup are atomic.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlJournalRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val json: Json = DefaultJson,
) : JournalRepository {
    override fun observeRecent(limit: Int): Flow<List<JournalEntry>> =
        catalog.catalogQueries
            .selectJournalRecent(limit.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map(::toDomain) }

    override suspend fun append(entry: JournalEntry): JournalEntry =
        withContext(ioDispatcher) {
            try {
                // Centralized denormalization: capture the project's current name + path at write
                // time. Repository callers (project mutators, repair flows, MCP) never need to
                // thread these through themselves — the journal just looks them up. If the entry
                // already carries one (e.g. a repair flow that already has the path in scope, an
                // in-memory fake, or a forward-from-MCP), respect it. We avoid the catalog read
                // entirely when both fields are pre-supplied so a caller that wants to journal an
                // already-orphaned project_id (no row exists) doesn't get a confusing miss.
                val (resolvedName, resolvedPath) =
                    if (entry.projectName != null && entry.projectPath != null) {
                        entry.projectName to entry.projectPath
                    } else {
                        val row =
                            catalog.catalogQueries
                                .selectProjectById(entry.projectId.value)
                                .executeAsOneOrNull()
                        (entry.projectName ?: row?.name) to (entry.projectPath ?: row?.path)
                    }
                val newId =
                    catalog.transactionWithResult<Long> {
                        catalog.catalogQueries.insertJournalEntry(
                            occurred_at = entry.timestamp.toEpochMilliseconds(),
                            actor = entry.actor,
                            action_type = entry.action.typeKey,
                            project_id = entry.projectId.value,
                            payload_json =
                                json.encodeToString(
                                    ActionRecordSerializer,
                                    entry.action,
                                ),
                            project_name = resolvedName,
                            project_path = resolvedPath,
                        )
                        catalog.catalogQueries.lastJournalEntryId().executeAsOne()
                    }
                entry.copy(
                    sequence = newId,
                    projectName = resolvedName,
                    projectPath = resolvedPath,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (s: SketchbookError) {
                throw s
            } catch (t: Throwable) {
                throw SketchbookError.IoFailure("journal append failed", t)
            }
        }

    override suspend fun undoLast(): JournalEntry? =
        withContext(ioDispatcher) {
            // Insert + delete must not interleave with another append/undo: read the head and
            // delete it inside one transaction so undo against an empty journal is a clean
            // miss rather than a TOCTOU between two callers.
            try {
                catalog.transactionWithResult<JournalEntry?> {
                    val row =
                        catalog.catalogQueries
                            .selectJournalRecent(limit_ = 1L)
                            .executeAsOneOrNull()
                    if (row == null) {
                        null
                    } else {
                        catalog.catalogQueries.deleteJournalEntryById(row.id)
                        toDomain(row)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (s: SketchbookError) {
                throw s
            } catch (t: Throwable) {
                throw SketchbookError.IoFailure("journal undoLast failed", t)
            }
        }

    private fun toDomain(row: Journal_entries): JournalEntry =
        JournalEntry(
            timestamp = Instant.fromEpochMilliseconds(row.occurred_at),
            projectId = ProjectId(row.project_id),
            action = json.decodeFromString(ActionRecordSerializer, row.payload_json),
            sequence = row.id,
            actor = row.actor,
            projectName = row.project_name,
            projectPath = row.project_path,
        )

    private companion object {
        /** Lenient enough for forward-compat: an MCP build that adds an ActionRecord variant
         *  shouldn't crash a desktop on the older schema reading the same DB. */
        val DefaultJson: Json = Json { ignoreUnknownKeys = true }

        /** Polymorphic serializer for the sealed [ActionRecord] hierarchy. Cached so we don't
         *  re-resolve it on every append/decode call. */
        val ActionRecordSerializer = serializer<ActionRecord>()
    }
}
