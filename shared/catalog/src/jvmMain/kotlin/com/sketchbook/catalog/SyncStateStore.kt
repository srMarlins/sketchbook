package com.sketchbook.catalog

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * Catalog-backed accessor for the v1 sync tables (`project_identity`, `sync_state`). Wraps the
 * SQLDelight queries so the desktop's `GcsSyncQueue` doesn't have to know schema details.
 *
 * **Identity:** every project that has ever been synced has a row in `project_identity` mapping
 * its local PK to a stable [ProjectUuid]. UUIDs are minted on first push via [identityFor];
 * once minted they round-trip across restarts, machines, and clones (the uuid persists through
 * `INSERT OR IGNORE` so concurrent syncs converge instead of fighting).
 *
 * **State:** the `sync_state` table tracks per-project (`local_rev`, `cloud_head_rev`, `dirty`,
 * `self_contained`). Reads are wrapped in a hot [Flow] so the UI's `observe()` can react to
 * pushes; writes flip [bumpVersion] which the queue mirrors into a `MutableStateFlow` for
 * cheap downstream observation.
 */
class SyncStateStore(private val catalog: Catalog) {

    /** Bumped on every write. Consumers can debounce reads by collecting this. */
    private val version = MutableStateFlow(0L)

    /** Hot ticker — every successful write increments. Observers re-query on tick. */
    fun observeVersion(): Flow<Long> = version.asStateFlow()

    /** Get the [ProjectUuid] for [id], minting one if necessary. Idempotent across restarts. */
    fun identityFor(id: ProjectId): ProjectUuid {
        val existing = catalog.catalogQueries.selectIdentityByProjectId(id.value).executeAsOneOrNull()
        if (existing != null) return ProjectUuid(existing.uuid)
        val fresh = generateUuid()
        catalog.transaction {
            catalog.catalogQueries.insertProjectIdentityIfAbsent(
                project_id = id.value,
                uuid = fresh,
                created_at = Clock.System.now().toString(),
            )
        }
        // Re-read in case a concurrent writer beat us to it (the IF-ABSENT clause means the
        // row we get back may carry a different UUID than `fresh`).
        val final = catalog.catalogQueries.selectIdentityByProjectId(id.value).executeAsOne()
        version.value = version.value + 1
        return ProjectUuid(final.uuid)
    }

    /** Inverse mapping: which local row is this UUID? Null if never imported. */
    fun projectIdFor(uuid: ProjectUuid): ProjectId? {
        val r = catalog.catalogQueries.selectIdentityByUuid(uuid.value).executeAsOneOrNull()
        return r?.let { ProjectId(it.project_id) }
    }

    /** Snapshot of the per-project sync row. Null when no row has been written yet. */
    fun stateOf(uuid: ProjectUuid): SyncStateRow? {
        val r = catalog.catalogQueries.selectSyncState(uuid.value).executeAsOneOrNull() ?: return null
        return SyncStateRow(
            uuid = uuid,
            localRev = r.local_rev,
            cloudHeadRev = r.cloud_head_rev,
            dirty = r.dirty != 0L,
            selfContained = r.self_contained != 0L,
        )
    }

    /** All sync_state rows. Used by the queue's aggregate observer. */
    fun all(): List<SyncStateRow> =
        catalog.catalogQueries.selectAllSyncStates().executeAsList().map { r ->
            SyncStateRow(
                uuid = ProjectUuid(r.project_uuid),
                localRev = r.local_rev,
                cloudHeadRev = r.cloud_head_rev,
                dirty = r.dirty != 0L,
                selfContained = r.self_contained != 0L,
            )
        }

    /**
     * Dirty rows ordered oldest-first by `updated_at`. Used by the GcsSyncQueue background
     * drain to pick the longest-pending project off the queue. Tiebreaker is uuid for
     * determinism (matters in tests; users won't notice).
     */
    fun dirtyOldestFirst(): List<SyncStateRow> =
        catalog.catalogQueries.selectDirtyOldestFirst().executeAsList().map { r ->
            SyncStateRow(
                uuid = ProjectUuid(r.project_uuid),
                localRev = r.local_rev,
                cloudHeadRev = r.cloud_head_rev,
                dirty = r.dirty != 0L,
                selfContained = r.self_contained != 0L,
            )
        }

    /**
     * Commit the result of a successful push: clamp dirty=0, set local_rev = cloud_head_rev =
     * [newRev]. Idempotent.
     */
    fun markSynced(uuid: ProjectUuid, newRev: Long) {
        catalog.transaction {
            catalog.catalogQueries.insertOrReplaceSyncState(
                project_uuid = uuid.value,
                local_rev = newRev,
                cloud_head_rev = newRev,
                dirty = 0L,
                self_contained = stateOf(uuid)?.let { if (it.selfContained) 1L else 0L } ?: 0L,
                updated_at = nowMillis(),
            )
        }
        version.value = version.value + 1
    }

    /** Mark a project dirty (something changed locally). */
    fun markDirty(uuid: ProjectUuid) {
        val existing = stateOf(uuid)
        catalog.transaction {
            catalog.catalogQueries.insertOrReplaceSyncState(
                project_uuid = uuid.value,
                local_rev = existing?.localRev ?: 0L,
                cloud_head_rev = existing?.cloudHeadRev ?: 0L,
                dirty = 1L,
                self_contained = if (existing?.selfContained == true) 1L else 0L,
                updated_at = nowMillis(),
            )
        }
        version.value = version.value + 1
    }

    /**
     * Advance `cloud_head_rev` on the sync_state row for [uuid]. Called by the PullPoller when
     * a new manifest lands in the cloud. Idempotent — same rev twice is a no-op.
     */
    fun markCloudHead(uuid: ProjectUuid, rev: Long) {
        val existing = stateOf(uuid)
        if (existing != null && existing.cloudHeadRev >= rev) return
        catalog.transaction {
            catalog.catalogQueries.markSyncStateCloudHead(
                project_uuid = uuid.value,
                cloud_head_rev = rev,
                updated_at = nowMillis(),
            )
        }
        version.value = version.value + 1
    }

    /**
     * Live-emitting wrapper around [all]: emits once on subscribe, then on every successful
     * write. Consumers (PullPoller wiring in DesktopAppGraph) use this to spawn one polling
     * coroutine per project.
     */
    fun observeAll(): Flow<List<SyncStateRow>> = kotlinx.coroutines.flow.flow {
        emit(all())
        observeVersion().collect { emit(all()) }
    }

    /** Set the per-project self-contained flag (no cross-project blob dedup for this uuid). */
    fun setSelfContained(uuid: ProjectUuid, value: Boolean) {
        val existing = stateOf(uuid)
        catalog.transaction {
            catalog.catalogQueries.insertOrReplaceSyncState(
                project_uuid = uuid.value,
                local_rev = existing?.localRev ?: 0L,
                cloud_head_rev = existing?.cloudHeadRev ?: 0L,
                dirty = if (existing?.dirty == true) 1L else 0L,
                self_contained = if (value) 1L else 0L,
                updated_at = nowMillis(),
            )
        }
        version.value = version.value + 1
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private fun generateUuid(): String {
        // Plain UUIDv4 — the design doc allows ULIDs but UUIDv4 round-trips through the existing
        // ProjectUuid validator (alphanumeric + dash, <= 64 chars) without extra dependencies.
        return java.util.UUID.randomUUID().toString()
    }
}

/** Typed view of a `sync_state` row. */
data class SyncStateRow(
    val uuid: ProjectUuid,
    val localRev: Long,
    val cloudHeadRev: Long,
    val dirty: Boolean,
    val selfContained: Boolean,
)
