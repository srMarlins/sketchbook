package com.sketchbook.sync.migration

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * JVM impl of [FirstRunMigration]. Reads `projects` rows from the v0.1 catalog, reconciles
 * `.audio-id` sidecars with `project_identity`, and seeds `sync_state` rows. Each project is its
 * own DB transaction so a partial run survives.
 *
 * Path resolution: `projects.path` points at the `.als` file; the project *directory* (where
 * the sidecar lives) is its parent. We use the parent-of-parent for Project files inside an
 * `Ableton Project` subdir? No — Ableton stores `.als` directly inside the project folder, so
 * `parentFile` of the `.als` file is correct.
 */
class JvmFirstRunMigration(
    private val catalog: Catalog,
    private val sidecar: AudioIdSidecar = JvmAudioIdSidecar(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.System,
    private val uuidGen: () -> String = { UUID.randomUUID().toString() },
) : FirstRunMigration {

    override fun run(): Flow<MigrationProgress> = flow {
        val rows = catalog.catalogQueries.selectAllProjectsForMigration().executeAsList()
        emit(MigrationProgress.Started(totalProjects = rows.size))

        var migrated = 0
        var alreadyMigrated = 0
        var conflicts = 0
        var failed = 0

        rows.forEachIndexed { index, row ->
            val outcome = migrateOne(
                projectId = ProjectId(row.id),
                alsPath = row.path,
            )
            when (outcome) {
                is ProjectMigrationOutcome.Migrated -> migrated++
                is ProjectMigrationOutcome.AlreadyMigrated -> alreadyMigrated++
                is ProjectMigrationOutcome.SidecarConflictResolved -> conflicts++
                is ProjectMigrationOutcome.Failed -> failed++
            }
            emit(
                MigrationProgress.Project(
                    projectId = ProjectId(row.id),
                    path = row.path,
                    outcome = outcome,
                    processed = index + 1,
                    total = rows.size,
                ),
            )
        }

        emit(
            MigrationProgress.Completed(
                total = rows.size,
                migrated = migrated,
                alreadyMigrated = alreadyMigrated,
                conflicts = conflicts,
                failed = failed,
            ),
        )
    }.flowOn(ioDispatcher)

    private fun migrateOne(projectId: ProjectId, alsPath: String): ProjectMigrationOutcome {
        val projectDir = File(alsPath).parentFile?.absolutePath
            ?: return ProjectMigrationOutcome.Failed("project file has no parent directory: $alsPath")

        val sidecarUuid = sidecar.read(projectDir)
        val dbUuid = catalog.catalogQueries
            .selectIdentityByProjectId(projectId.value)
            .executeAsOneOrNull()
            ?.uuid
            ?.let(::ProjectUuid)

        return when {
            sidecarUuid != null && dbUuid != null && sidecarUuid == dbUuid -> {
                ensureSyncState(sidecarUuid)
                ProjectMigrationOutcome.AlreadyMigrated(sidecarUuid)
            }

            sidecarUuid != null && dbUuid != null && sidecarUuid != dbUuid -> {
                writeIdentity(projectId, sidecarUuid, replace = true)
                ensureSyncState(sidecarUuid)
                ProjectMigrationOutcome.SidecarConflictResolved(
                    keptUuid = sidecarUuid,
                    replacedUuid = dbUuid,
                )
            }

            sidecarUuid != null && dbUuid == null -> {
                writeIdentity(projectId, sidecarUuid, replace = false)
                ensureSyncState(sidecarUuid)
                ProjectMigrationOutcome.Migrated(sidecarUuid, sidecarWritten = false)
            }

            sidecarUuid == null && dbUuid != null -> {
                val wrote = sidecar.write(projectDir, dbUuid)
                if (!wrote) {
                    return ProjectMigrationOutcome.Failed("could not write sidecar for $projectDir")
                }
                ensureSyncState(dbUuid)
                ProjectMigrationOutcome.Migrated(dbUuid, sidecarWritten = true)
            }

            else -> {
                val newUuid = ProjectUuid(uuidGen())
                val wrote = sidecar.write(projectDir, newUuid)
                if (!wrote) {
                    return ProjectMigrationOutcome.Failed("could not write sidecar for $projectDir")
                }
                writeIdentity(projectId, newUuid, replace = false)
                ensureSyncState(newUuid)
                ProjectMigrationOutcome.Migrated(newUuid, sidecarWritten = true)
            }
        }
    }

    private fun writeIdentity(projectId: ProjectId, uuid: ProjectUuid, replace: Boolean) {
        val createdAt: Instant = clock.now()
        catalog.catalogQueries.transaction {
            if (replace) {
                catalog.catalogQueries.upsertProjectIdentity(
                    project_id = projectId.value,
                    uuid = uuid.value,
                    created_at = createdAt.toString(),
                )
            } else {
                catalog.catalogQueries.insertProjectIdentityIfAbsent(
                    project_id = projectId.value,
                    uuid = uuid.value,
                    created_at = createdAt.toString(),
                )
            }
        }
    }

    private fun ensureSyncState(uuid: ProjectUuid) {
        val existing = catalog.catalogQueries.selectSyncState(uuid.value).executeAsOneOrNull()
        if (existing != null) return
        catalog.catalogQueries.insertOrReplaceSyncState(
            project_uuid = uuid.value,
            local_rev = 0L,
            cloud_head_rev = 0L,
            dirty = 0L,
            self_contained = 0L,
        )
    }
}
