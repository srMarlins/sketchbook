package com.sketchbook.sync.migration

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmFirstRunMigrationTest {
    private val tmp = Files.createTempDirectory("sketchbook-migration").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun setup(): Catalog = CatalogDb.openInMemory().catalog

    private fun seedProject(
        catalog: Catalog,
        name: String,
        projectDir: File,
    ): Long {
        projectDir.mkdirs()
        val alsPath = File(projectDir, "$name.als").absolutePath
        catalog.catalogQueries.insertOrReplaceProject(
            path = alsPath,
            name = name,
            parent_dir = projectDir.parentFile.absolutePath,
            tempo = 120.0,
            time_sig_num = 4,
            time_sig_den = 4,
            key = null,
            track_count = 0,
            audio_tracks = 0,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = "12.0.0",
            last_modified = 0.0,
            last_scanned = 0.0,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = 0L,
        )
        return catalog.catalogQueries.selectProjectIdByPath(alsPath).executeAsOne()
    }

    @Test
    fun mintsUuidWhenNeitherSidecarNorIdentityExists() =
        runTest {
            val catalog = setup()
            val dir = File(tmp, "fresh-project")
            seedProject(catalog, "fresh-project", dir)

            val migration = JvmFirstRunMigration(catalog, uuidGen = { "uuid-fresh-1" })
            val events = migration.run().toList()

            val started = events.first() as MigrationProgress.Started
            assertEquals(1, started.totalProjects)

            val projectEvent = events.filterIsInstance<MigrationProgress.Project>().single()
            val outcome = projectEvent.outcome as ProjectMigrationOutcome.Migrated
            assertEquals("uuid-fresh-1", outcome.uuid.value)
            assertTrue(outcome.sidecarWritten)

            val sidecarFile = File(dir, AUDIO_ID_SIDECAR_NAME)
            assertTrue(sidecarFile.exists())
            assertEquals("uuid-fresh-1", sidecarFile.readText().trim())

            val identity = catalog.catalogQueries.selectIdentityByProjectId(1L).executeAsOne()
            assertEquals("uuid-fresh-1", identity.uuid)

            val sync = catalog.catalogQueries.selectSyncState("uuid-fresh-1").executeAsOne()
            assertEquals(0L, sync.local_rev)
            assertEquals(0L, sync.cloud_head_rev)
        }

    @Test
    fun reusesSidecarUuidWhenIdentityRowMissing() =
        runTest {
            val catalog = setup()
            val dir = File(tmp, "carried-project")
            seedProject(catalog, "carried-project", dir)
            // simulate a project carried over from another machine — sidecar exists, DB row doesn't.
            File(dir, AUDIO_ID_SIDECAR_NAME).writeText("uuid-carried-9\n")

            val migration = JvmFirstRunMigration(catalog, uuidGen = { error("should not be called") })
            val events = migration.run().toList()
            val outcome = events.filterIsInstance<MigrationProgress.Project>().single().outcome
            val migrated = outcome as ProjectMigrationOutcome.Migrated
            assertEquals("uuid-carried-9", migrated.uuid.value)
            assertEquals(false, migrated.sidecarWritten)

            val identity = catalog.catalogQueries.selectIdentityByProjectId(1L).executeAsOne()
            assertEquals("uuid-carried-9", identity.uuid)
        }

    @Test
    fun writesSidecarWhenIdentityExistsButSidecarMissing() =
        runTest {
            val catalog = setup()
            val dir = File(tmp, "db-only-project")
            seedProject(catalog, "db-only-project", dir)
            catalog.catalogQueries.insertProjectIdentityIfAbsent(
                project_id = 1L,
                uuid = "uuid-db-only",
                created_at = "2026-01-01T00:00:00Z",
            )

            val migration = JvmFirstRunMigration(catalog, uuidGen = { error("should not be called") })
            val outcome =
                migration
                    .run()
                    .toList()
                    .filterIsInstance<MigrationProgress.Project>()
                    .single()
                    .outcome as ProjectMigrationOutcome.Migrated
            assertEquals("uuid-db-only", outcome.uuid.value)
            assertTrue(outcome.sidecarWritten)

            val sidecarFile = File(dir, AUDIO_ID_SIDECAR_NAME)
            assertTrue(sidecarFile.exists())
            assertEquals("uuid-db-only", sidecarFile.readText().trim())
        }

    @Test
    fun isIdempotentOnSecondRun() =
        runTest {
            val catalog = setup()
            val dir = File(tmp, "idempotent-project")
            seedProject(catalog, "idempotent-project", dir)

            var counter = 0
            val migration = JvmFirstRunMigration(catalog, uuidGen = { "uuid-${++counter}" })

            migration.run().toList()
            val secondRun = migration.run().toList()

            val secondOutcome = secondRun.filterIsInstance<MigrationProgress.Project>().single().outcome
            val already = secondOutcome as ProjectMigrationOutcome.AlreadyMigrated
            assertEquals("uuid-1", already.uuid.value)
            assertEquals(1, counter, "uuidGen should only have fired during the first run")
        }

    @Test
    fun sidecarWinsConflict() =
        runTest {
            val catalog = setup()
            val dir = File(tmp, "conflict-project")
            seedProject(catalog, "conflict-project", dir)
            catalog.catalogQueries.insertProjectIdentityIfAbsent(
                project_id = 1L,
                uuid = "uuid-db-side",
                created_at = "2026-01-01T00:00:00Z",
            )
            File(dir, AUDIO_ID_SIDECAR_NAME).writeText("uuid-sidecar-side\n")

            val migration = JvmFirstRunMigration(catalog, uuidGen = { error("should not be called") })
            val outcome =
                migration
                    .run()
                    .toList()
                    .filterIsInstance<MigrationProgress.Project>()
                    .single()
                    .outcome as ProjectMigrationOutcome.SidecarConflictResolved

            assertEquals(ProjectUuid("uuid-sidecar-side"), outcome.keptUuid)
            assertEquals(ProjectUuid("uuid-db-side"), outcome.replacedUuid)

            val identity = catalog.catalogQueries.selectIdentityByProjectId(1L).executeAsOne()
            assertEquals("uuid-sidecar-side", identity.uuid)
        }

    @Test
    fun summaryCountsMatchOutcomes() =
        runTest {
            val catalog = setup()
            seedProject(catalog, "p1", File(tmp, "p1"))
            seedProject(catalog, "p2", File(tmp, "p2"))
            seedProject(catalog, "p3", File(tmp, "p3"))

            var counter = 0
            val events = JvmFirstRunMigration(catalog, uuidGen = { "uuid-${++counter}" }).run().toList()
            val completed = events.last() as MigrationProgress.Completed
            assertEquals(3, completed.total)
            assertEquals(3, completed.migrated)
            assertEquals(0, completed.alreadyMigrated)
            assertEquals(0, completed.conflicts)
            assertEquals(0, completed.failed)
        }
}
