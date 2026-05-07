package com.sketchbook.sync.migration

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.flow.Flow

/**
 * First-run migration from a v0.1 catalog.db to v1's sync-enabled state. Walks every project row
 * once, ensuring each has:
 *
 *   1. A stable `ProjectUuid` — read from a `.audio-id` sidecar if one exists (canonical), or
 *      from `project_identity` if the DB already has one, or freshly minted as a UUID4.
 *   2. A `.audio-id` sidecar at `<project_dir>/.audio-id` containing the UUID. Written if
 *      missing; a divergent existing sidecar (DB UUID != sidecar UUID) is reported as a
 *      conflict and the **sidecar wins** (filesystem is the cross-machine source of truth).
 *   3. A row in `project_identity` aligned with the canonical UUID.
 *   4. A row in `sync_state` with rev 0 / not-dirty / not-self-contained — defaults until a
 *      first cloud sync runs.
 *
 * Idempotency: re-running over an already-migrated catalog is a no-op (every per-row outcome
 * resolves to [ProjectMigrationOutcome.AlreadyMigrated]).
 */
interface FirstRunMigration {
    /**
     * Runs the migration end-to-end. Emits per-project [MigrationProgress.Project] events as
     * each row resolves, then a single [MigrationProgress.Completed] when done.
     *
     * Cancellation: the upstream coroutine cancellation propagates — partial progress already
     * committed to the DB stays committed (each project is its own DB transaction).
     */
    fun run(): Flow<MigrationProgress>
}

sealed interface MigrationProgress {
    data class Started(
        val totalProjects: Int,
    ) : MigrationProgress

    /** A single project has resolved (success or skip). */
    data class Project(
        val projectId: ProjectId,
        val path: String,
        val outcome: ProjectMigrationOutcome,
        val processed: Int,
        val total: Int,
    ) : MigrationProgress

    data class Completed(
        val total: Int,
        val migrated: Int,
        val alreadyMigrated: Int,
        val conflicts: Int,
        val failed: Int,
    ) : MigrationProgress
}

sealed interface ProjectMigrationOutcome {
    /** First time this project saw migration; UUID minted and sidecar written. */
    data class Migrated(
        val uuid: ProjectUuid,
        val sidecarWritten: Boolean,
    ) : ProjectMigrationOutcome

    /** DB and sidecar already aligned; nothing to do. */
    data class AlreadyMigrated(
        val uuid: ProjectUuid,
    ) : ProjectMigrationOutcome

    /**
     * Sidecar UUID differs from the UUID stored in `project_identity`. The sidecar wins; DB row
     * is rewritten. Reported separately so the UI can surface a "this is unusual" notice.
     */
    data class SidecarConflictResolved(
        val keptUuid: ProjectUuid,
        val replacedUuid: ProjectUuid,
    ) : ProjectMigrationOutcome

    /** Per-project failure (project dir missing, sidecar unwritable, etc.). */
    data class Failed(
        val reason: String,
    ) : ProjectMigrationOutcome
}
