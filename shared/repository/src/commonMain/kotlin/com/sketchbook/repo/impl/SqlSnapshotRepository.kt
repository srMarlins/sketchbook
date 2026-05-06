package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    private val materialize: suspend (ProjectUuid, SnapshotRev) -> Result<Unit> =
        { _, _ -> Result.success(Unit) },
) : SnapshotRepository {

    override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> =
        catalog.catalogQueries.selectSnapshotsForProject(uuid.value)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun recordSnapshot(
        snapshot: Snapshot,
        manifestPath: String,
        manifestHash: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
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

    override suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> {
        val r = materialize(uuid, rev)
        if (r.isFailure) return r
        // Record the rewind itself as a synthetic snapshot row so the Timeline reflects it.
        withContext(ioDispatcher) {
            catalog.transaction {
                val existing = catalog.catalogQueries.selectSnapshotsForProject(uuid.value).executeAsList()
                val nextRev = (existing.maxOfOrNull { it.rev } ?: 0L) + 1L
                catalog.catalogQueries.insertSnapshot(
                    project_uuid = uuid.value,
                    rev = nextRev,
                    parent_rev = rev.value,
                    timestamp = kotlin.time.Clock.System.now().toString(),
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
        return Result.success(Unit)
    }
}

private fun SnapshotKind.dbName(): String = when (this) {
    SnapshotKind.Auto -> "auto"
    SnapshotKind.Named -> "named"
    SnapshotKind.Branch -> "branch"
}

private fun parseSnapshotKind(raw: String): SnapshotKind = when (raw) {
    "auto" -> SnapshotKind.Auto
    "named" -> SnapshotKind.Named
    "branch" -> SnapshotKind.Branch
    else -> SnapshotKind.Auto
}

private fun com.sketchbook.catalog.db.Snapshots.toDomain(): Snapshot = Snapshot(
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
