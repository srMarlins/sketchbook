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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
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
    ): TreeJournalEntry =
        withContext(ioDispatcher) {
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
                    // O(1) PK lookup — selectTreeSnapshots would re-read the whole tree's
                    // history every poll tick.
                    val existing =
                        catalog.catalogQueries
                            .selectTreeSnapshotByRev(
                                tree_id = treeId.value,
                                rev = manifest.rev.value,
                            ).executeAsOneOrNull() != null
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
            TreeJournalEntry(
                treeId = treeId,
                kind = kind,
                timestamp = manifest.timestamp,
                hostId = manifest.hostId,
                event = event,
                rev = manifest.rev,
                sequence = sequence,
            )
        }

    override suspend fun appendEvent(entry: TreeJournalEntry): TreeJournalEntry =
        withContext(ioDispatcher) {
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
            entry.copy(sequence = sequence)
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
        /**
         * Forward-compat: an older binary should ignore an unknown event variant rather than
         * crash when reading the journal. `ignoreUnknownKeys` only skips unknown *fields* — to
         * survive an unknown polymorphic discriminator we register a default deserializer that
         * routes those rows to [TreeJournalEvent.Unknown]. Mirrors [SqlJournalRepository]'s
         * lenient stance.
         */
        val DefaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                classDiscriminator = "type"
                serializersModule =
                    SerializersModule {
                        polymorphic(TreeJournalEvent::class) {
                            defaultDeserializer { TreeJournalEvent.Unknown.serializer() }
                        }
                    }
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

/**
 * Strict parse. The previous "fall back to [SnapshotKind.Auto]" behaviour silently corrupted the
 * timeline view when SQLite returned an unknown enum string — flipped a `Named` snapshot to look
 * `Auto` in the UI. Throwing here turns a corrupted catalog row into a loud failure (the row
 * never reached `dbName()` to begin with, so this only fires when the catalog was hand-edited or
 * a future schema introduced a kind this build doesn't know about).
 */
private fun parseSnapshotKind(raw: String): SnapshotKind =
    when (raw) {
        "auto" -> SnapshotKind.Auto
        "named" -> SnapshotKind.Named
        "branch" -> SnapshotKind.Branch
        else -> error("unknown snapshot_kind '$raw' in tree_snapshots row")
    }
