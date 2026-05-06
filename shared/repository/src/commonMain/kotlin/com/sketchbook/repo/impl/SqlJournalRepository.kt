package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.catalog.db.Journal_entries
import com.sketchbook.core.ProjectId
import com.sketchbook.core.SketchbookError
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
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
class SqlJournalRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val json: Json = DefaultJson,
) : JournalRepository {

    override fun observeRecent(limit: Int): Flow<List<JournalEntry>> =
        catalog.catalogQueries.selectJournalRecent(limit.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map(::toDomain) }

    override suspend fun append(entry: JournalEntry): Result<JournalEntry> =
        withContext(ioDispatcher) {
            // Don't use `runCatching` here: it catches `Throwable`, including
            // `CancellationException`, which would silently break structured concurrency the
            // moment anyone adds a suspend call inside the body. Pattern matches JvmScanner /
            // McpServer: explicit re-throw of cancellation, Result.failure for everything else.
            try {
                val newId = catalog.transactionWithResult<Long> {
                    catalog.catalogQueries.insertJournalEntry(
                        occurred_at = entry.timestamp.toEpochMilliseconds(),
                        actor = entry.actor,
                        action_type = entry.action.typeKey,
                        project_id = entry.projectId.value,
                        payload_json = json.encodeToString(
                            ActionRecordSerializer,
                            entry.action,
                        ),
                    )
                    catalog.catalogQueries.lastJournalEntryId().executeAsOne()
                }
                Result.success(entry.copy(sequence = newId))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    override suspend fun undoLast(): Result<JournalEntry> =
        withContext(ioDispatcher) {
            // Insert + delete must not interleave with another append/undo: read the head and
            // delete it inside one transaction so undo against an empty journal is a clean
            // miss rather than a TOCTOU between two callers. Same cancellation discipline as
            // append: re-throw CancellationException, wrap everything else.
            try {
                val popped = catalog.transactionWithResult<JournalEntry?> {
                    val row = catalog.catalogQueries.selectJournalRecent(limit_ = 1L)
                        .executeAsOneOrNull()
                    if (row == null) {
                        null
                    } else {
                        catalog.catalogQueries.deleteJournalEntryById(row.id)
                        toDomain(row)
                    }
                }
                if (popped == null) {
                    Result.failure(SketchbookError.NotFound("journal is empty"))
                } else {
                    Result.success(popped)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    private fun toDomain(row: Journal_entries): JournalEntry = JournalEntry(
        timestamp = Instant.fromEpochMilliseconds(row.occurred_at),
        projectId = ProjectId(row.project_id),
        action = json.decodeFromString(ActionRecordSerializer, row.payload_json),
        sequence = row.id,
        actor = row.actor,
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
