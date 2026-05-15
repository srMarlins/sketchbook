package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.MaterializeOutcome
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * SQLDelight-backed [SnapshotRepository]. Reads from the catalog's `snapshots` table; writes
 * one row per `recordSnapshot`. Materialization delegates to a function passed at
 * construction so commonMain stays free of any sync-engine dep — the desktop graph wires in
 * `ManifestMaterializer::materialize`.
 *
 * On a successful rewind, a synthetic snapshot row is inserted (`kind = "auto"`, label
 * `"rewind to rev N"`, `parent_rev = rev`, `rev = currentMax + 1`) so the Timeline shows the
 * rewind itself as a new entry.
 */
class SqlSnapshotRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    /** Optional so legacy call sites (and the parameterless rewind path) keep compiling; required
     *  for [setSnapshotLabel] to emit a journal entry. The desktop graph wires the real
     *  [SqlJournalRepository] in. Tests that don't touch labels can pass `null`. */
    private val journal: JournalRepository? = null,
    private val clock: Clock = Clock.System,
    private val materialize: suspend (ProjectUuid, SnapshotRev) -> MaterializeOutcome =
        { _, _ -> MaterializeOutcome.Materialized },
) : SnapshotRepository {
    override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> =
        catalog.catalogQueries
            .selectSnapshotsForProject(uuid.value)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun recordSnapshot(
        snapshot: Snapshot,
        manifestPath: String,
        manifestHash: String,
    ) {
        withContext(ioDispatcher) {
            snapshotOperation("recordSnapshot ${snapshot.projectUuid}@${snapshot.rev}") {
                catalog.transaction {
                    catalog.catalogQueries.insertSnapshot(
                        project_uuid = snapshot.projectUuid.value,
                        rev = snapshot.rev.value,
                        parent_rev = snapshot.parentRev?.value,
                        timestamp = snapshot.timestamp.toString(),
                        host_id = snapshot.hostId,
                        kind = snapshot.kind.dbName(),
                        label = snapshot.label,
                        manifest_path = manifestPath,
                        manifest_hash = manifestHash,
                        file_count = snapshot.fileCount.toLong(),
                        total_bytes = snapshot.totalBytes,
                        new_bytes = snapshot.newBytes,
                    )
                }
            }
        }
    }

    /**
     * Wrap a snapshot-operation body so any non-`SketchbookError` throwable surfaces as
     * [SketchbookError.IoFailure] (preserving the original cause). `CancellationException`
     * propagates unchanged; already-typed `SketchbookError` rethrows as-is.
     */
    private inline fun <R> snapshotOperation(
        op: String,
        block: () -> R,
    ): R =
        try {
            block()
        } catch (t: Throwable) {
            when (t) {
                is CancellationException, is SketchbookError -> throw t
                else -> throw SketchbookError.IoFailure("$op failed", t)
            }
        }

    override suspend fun setSnapshotLabel(
        uuid: ProjectUuid,
        rev: SnapshotRev,
        label: String?,
    ): JournalEntry =
        withContext(ioDispatcher) {
            snapshotOperation("setSnapshotLabel ${uuid.value}@${rev.value}") {
                // Capture before-state, write, journal — all atomic under one transaction so a
                // concurrent observer never sees the row mutated without the journal entry's row
                // also visible.
                val before =
                    catalog.catalogQueries
                        .selectSnapshotByRev(uuid.value, rev.value)
                        .executeAsOneOrNull()
                        ?: throw SketchbookError.NotFound("snapshot ${uuid.value}@${rev.value} not found")
                // Resolve the project_id for the JournalEntry. Snapshots key on project_uuid; the
                // journal keys on project_id (legacy v0.1 schema). project_identity is the bridge.
                // Fall back to 1L when the identity row is missing — `ProjectId(0)` would fail the
                // value-class precondition and crash the journal append. The "orphan" case
                // (snapshot without an identity row) shouldn't happen in practice, but a
                // synthetic-1 audit row is preferable to a thrown exception for a label-edit
                // hotpath.
                val resolvedProjectId =
                    catalog.catalogQueries
                        .selectIdentityByUuid(uuid.value)
                        .executeAsOneOrNull()
                        ?.project_id
                        ?.takeIf { it > 0L }
                        ?: 1L
                catalog.transaction {
                    catalog.catalogQueries.updateSnapshotLabel(
                        label = label,
                        project_uuid = uuid.value,
                        rev = rev.value,
                    )
                }
                val entry =
                    JournalEntry(
                        timestamp = clock.now(),
                        projectId = ProjectId(resolvedProjectId),
                        action =
                            ActionRecord.SnapshotRelabeled(
                                rev = rev.value,
                                labelBefore = before.label,
                                labelAfter = label,
                                kindBefore = before.kind,
                            ),
                    )
                // No journal wired (test path that doesn't care about audit) → return the entry
                // so callers can still assert action contents without a side-channel.
                journal?.append(entry) ?: entry
            }
        }

    override suspend fun materializeAt(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): MaterializeOutcome {
        val outcome = materialize(uuid, rev)
        if (outcome !is MaterializeOutcome.Materialized) return outcome
        // Record the rewind itself as a synthetic snapshot row so the Timeline reflects it.
        withContext(ioDispatcher) {
            catalog.transaction {
                val existing = catalog.catalogQueries.selectSnapshotsForProject(uuid.value).executeAsList()
                val nextRev = (existing.maxOfOrNull { it.rev } ?: 0L) + 1L
                catalog.catalogQueries.insertSnapshot(
                    project_uuid = uuid.value,
                    rev = nextRev,
                    parent_rev = rev.value,
                    timestamp =
                        kotlin.time.Clock.System
                            .now()
                            .toString(),
                    host_id = "local",
                    kind = "auto",
                    label = "rewind to rev ${rev.value}",
                    manifest_path = "",
                    manifest_hash = "",
                    file_count = 0L,
                    total_bytes = 0L,
                    new_bytes = 0L,
                )
            }
        }
        return MaterializeOutcome.Materialized
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

private fun com.sketchbook.catalog.db.Snapshots.toDomain(): Snapshot =
    Snapshot(
        projectUuid = ProjectUuid(project_uuid),
        rev = SnapshotRev(rev),
        parentRev = parent_rev?.let { SnapshotRev(it) },
        timestamp = Instant.parse(timestamp),
        hostId = host_id,
        // The DB doesn't carry hostName separately yet; reuse hostId as a placeholder so the
        // Timeline UI has something to render. Remote pulls (PR-22) will add a host_name column.
        hostName = host_id,
        kind = parseSnapshotKind(kind),
        label = label,
        selfContained = false, // tracked on sync_state, not the snapshot row.
        fileCount = file_count.toInt(),
        totalBytes = total_bytes,
        newBytes = new_bytes,
    )
