package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.catalog.db.Tree_journal
import com.sketchbook.catalog.db.Tree_snapshots
import com.sketchbook.core.AppScope
import com.sketchbook.core.Manifest
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.repo.TreeJournal
import com.sketchbook.repo.TreeJournalEntry
import com.sketchbook.repo.TreeJournalEvent
import com.sketchbook.repo.TreeSnapshotRow
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
 * SQLDelight-backed [TreeJournal]. Mirrors [SqlJournalRepository]'s style:
 * cancellation-aware error handling, transactional writes, and a polymorphic
 * [TreeJournalEvent] serializer cached in the companion.
 *
 * `recordSnapshot` writes the snapshot row + the matching event in a single transaction so
 * a concurrent observer never sees one without the other.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlTreeJournal(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val json: Json = DefaultJson,
) : TreeJournal {
    override suspend fun recordSnapshot(
        manifest: Manifest,
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        manifestPath: String,
    ): Result<TreeJournalEntry> =
        withContext(ioDispatcher) {
            try {
                val event =
                    TreeJournalEvent.Snapshot(
                        rev = manifest.rev.value,
                        parentRev = manifest.parentRev?.value,
                        fileCount = manifest.stats.fileCount,
                        totalBytes = manifest.stats.totalBytes,
                        newBytes = manifest.stats.newBytes,
                        manifestPath = manifestPath,
                    )
                val sequence =
                    catalog.transactionWithResult<Long?> {
                        // Idempotent on (tree_id, rev): if the snapshot row already exists,
                        // skip both writes so a re-subscribe doesn't double-up the journal.
                        // tree_snapshots itself is INSERT OR IGNORE; we mirror that here so
                        // the paired journal write doesn't duplicate either.
                        val existing =
                            catalog.catalogQueries
                                .selectTreeSnapshots(tree_id = treeId.value)
                                .executeAsList()
                                .any { it.rev == manifest.rev.value }
                        if (existing) {
                            null
                        } else {
                            catalog.catalogQueries.insertTreeSnapshot(
                                tree_id = treeId.value,
                                rev = manifest.rev.value,
                                parent_rev = manifest.parentRev?.value,
                                timestamp = manifest.timestamp.toEpochMilliseconds(),
                                host_id = manifest.hostId,
                                snapshot_kind = manifest.snapshotKind.dbName(),
                                label = manifest.label,
                                file_count = manifest.stats.fileCount.toLong(),
                                total_bytes = manifest.stats.totalBytes,
                                new_bytes = manifest.stats.newBytes,
                                manifest_path = manifestPath,
                            )
                            catalog.catalogQueries.insertTreeJournalEntry(
                                tree_id = treeId.value,
                                tree_kind = kind.wireName,
                                timestamp = manifest.timestamp.toEpochMilliseconds(),
                                host_id = manifest.hostId,
                                event_kind = event.typeKey,
                                payload_json = json.encodeToString(EventSerializer, event),
                                rev = manifest.rev.value,
                            )
                            catalog.catalogQueries.lastTreeJournalId().executeAsOne()
                        }
                    }
                Result.success(
                    TreeJournalEntry(
                        treeId = treeId,
                        kind = kind,
                        timestamp = manifest.timestamp,
                        hostId = manifest.hostId,
                        event = event,
                        rev = manifest.rev,
                        sequence = sequence,
                    ),
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    override suspend fun appendEvent(entry: TreeJournalEntry): Result<TreeJournalEntry> =
        withContext(ioDispatcher) {
            try {
                val sequence =
                    catalog.transactionWithResult<Long> {
                        catalog.catalogQueries.insertTreeJournalEntry(
                            tree_id = entry.treeId.value,
                            tree_kind = entry.kind.wireName,
                            timestamp = entry.timestamp.toEpochMilliseconds(),
                            host_id = entry.hostId,
                            event_kind = entry.event.typeKey,
                            payload_json = json.encodeToString(EventSerializer, entry.event),
                            rev = entry.rev?.value,
                        )
                        catalog.catalogQueries.lastTreeJournalId().executeAsOne()
                    }
                Result.success(entry.copy(sequence = sequence))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    override fun observeRecent(
        treeId: TrackedTreeId,
        limit: Int,
    ): Flow<List<TreeJournalEntry>> =
        catalog.catalogQueries
            .selectTreeJournal(tree_id = treeId.value, limit_ = limit.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map(::toJournalDomain) }

    override fun observeSnapshots(treeId: TrackedTreeId): Flow<List<TreeSnapshotRow>> =
        catalog.catalogQueries
            .selectTreeSnapshots(tree_id = treeId.value)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map(::toSnapshotDomain) }

    private fun toJournalDomain(row: Tree_journal): TreeJournalEntry =
        TreeJournalEntry(
            treeId = TrackedTreeId(row.tree_id),
            kind = TrackedTreeKind.fromWire(row.tree_kind),
            timestamp = Instant.fromEpochMilliseconds(row.timestamp),
            hostId = row.host_id,
            event = json.decodeFromString(EventSerializer, row.payload_json),
            rev = row.rev?.let(::SnapshotRev),
            sequence = row.id,
        )

    private fun toSnapshotDomain(row: Tree_snapshots): TreeSnapshotRow =
        TreeSnapshotRow(
            treeId = TrackedTreeId(row.tree_id),
            rev = SnapshotRev(row.rev),
            parentRev = row.parent_rev?.let(::SnapshotRev),
            timestamp = Instant.fromEpochMilliseconds(row.timestamp),
            hostId = row.host_id,
            snapshotKind = parseSnapshotKind(row.snapshot_kind),
            label = row.label,
            fileCount = row.file_count.toInt(),
            totalBytes = row.total_bytes,
            newBytes = row.new_bytes,
            manifestPath = row.manifest_path,
        )

    private companion object {
        // Forward-compat: an older binary should ignore an unknown event variant rather than
        // crash when reading the journal — mirrors SqlJournalRepository's lenient stance.
        val DefaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                classDiscriminator = "type"
            }

        val EventSerializer = serializer<TreeJournalEvent>()
    }
}

private fun SnapshotKind.dbName(): String =
    when (this) {
        SnapshotKind.Auto -> "auto"
        SnapshotKind.Named -> "named"
        SnapshotKind.Branch -> "branch"
    }

private fun parseSnapshotKind(raw: String): SnapshotKind =
    when (raw) {
        "auto" -> SnapshotKind.Auto
        "named" -> SnapshotKind.Named
        "branch" -> SnapshotKind.Branch
        else -> SnapshotKind.Auto
    }
