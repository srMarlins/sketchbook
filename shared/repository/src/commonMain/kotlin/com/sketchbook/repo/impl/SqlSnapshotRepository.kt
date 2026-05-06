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
 * one row per `recordSnapshot`. Materialization is still a stub — the real laydown lives in
 * the sync engine (PR-22) and will swap this impl's [materializeAt] when it lands.
 *
 * Why this exists in PR-C: the Timeline screen needs real history per project, and the
 * `SnapshotPipeline` (PR-B) writes manifests but doesn't persist a domain `Snapshot` row
 * itself. The desktop's pipeline integration calls [recordSnapshot] from `GcsSyncQueue` after
 * a successful CAS — that path lands as a follow-up; for v1 the table is populated by the
 * scanner's first-run migration when sidecar files exist.
 */
class SqlSnapshotRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
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
                    new_bytes = 0L,
                )
            }
        }
    }

    override suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> =
        Result.success(Unit) // Real impl lands with the sync engine's Materializer (PR-22).
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
)
