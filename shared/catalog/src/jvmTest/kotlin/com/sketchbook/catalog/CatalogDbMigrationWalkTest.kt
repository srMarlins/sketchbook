package com.sketchbook.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sketchbook.catalog.db.Catalog
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Walks every SQLDelight migration end-to-end against a real on-disk SQLite. The build-time
 * `verifyMigration` task is disabled (xerial native-lib boot fails on some JDKs — see
 * `build.gradle.kts`), so this is the only thing pinning that 1.sqm → N.sqm actually compose
 * cleanly when applied in sequence.
 *
 * Two passes:
 *  - [migrationWalkFromHandBuiltV0AppliesEveryStep] hand-builds a minimal v0 schema (just the
 *    tables migrations 1..N reference) and runs `Schema.migrate(driver, 0, target)`. Pins that
 *    1.sqm → 9.sqm compose without "no such table/column" errors and that every additive change
 *    lands.
 *  - [preTrackingDbWithoutUserVersionDetectsCorrectVersionAndUpgrades] simulates the realistic
 *    upgrade path: a real on-disk catalog written by an older release that didn't track
 *    `user_version`. Exercises `CatalogDb.ensureSchema`'s heuristic-detection branches via
 *    `openOnDisk`.
 */
class CatalogDbMigrationWalkTest {
    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        // SQLite WAL mode leaves `<db>-wal` and `<db>-shm` siblings on disk; clean them up too
        // so /tmp doesn't accumulate sidecar files across runs.
        tempFiles.forEach { path ->
            path.deleteIfExists()
            path.resolveSibling("${path.fileName}-wal").deleteIfExists()
            path.resolveSibling("${path.fileName}-shm").deleteIfExists()
        }
        tempFiles.clear()
    }

    @Test
    fun migrationWalkFromHandBuiltV0AppliesEveryStep() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        try {
            createV0ShapedTables(driver)

            // Run every migration (1.sqm through (target-1).sqm) in sequence.
            Catalog.Schema.migrate(driver, oldVersion = 0L, newVersion = Catalog.Schema.version)

            // 1.sqm: sync_state.updated_at + idx_sync_state_dirty_updated_at
            assertTrue(columnExists(driver, "sync_state", "updated_at"), "1.sqm did not add sync_state.updated_at")
            assertTrue(indexExists(driver, "idx_sync_state_dirty_updated_at"), "1.sqm did not create dirty index")

            // 2.sqm: repair_acks + proposal_acks
            assertTrue(tableExists(driver, "repair_acks"), "2.sqm did not create repair_acks")
            assertTrue(tableExists(driver, "proposal_acks"), "2.sqm did not create proposal_acks")

            // 3.sqm: journal_entries + indexes
            assertTrue(tableExists(driver, "journal_entries"), "3.sqm did not create journal_entries")
            assertTrue(indexExists(driver, "journal_entries_project_idx"), "3.sqm did not create project idx")

            // 4.sqm: idx_projects_key
            assertTrue(indexExists(driver, "idx_projects_key"), "4.sqm did not create idx_projects_key")

            // 5.sqm: stage classification columns on projects
            assertTrue(columnExists(driver, "projects", "stage_inferred"), "5.sqm did not add projects.stage_inferred")
            assertTrue(columnExists(driver, "projects", "stage_override"), "5.sqm did not add projects.stage_override")
            assertTrue(columnExists(driver, "projects", "has_local_bounce"), "5.sqm did not add has_local_bounce")

            // 6.sqm: project_plugins.is_installed
            assertTrue(columnExists(driver, "project_plugins", "is_installed"), "6.sqm did not add is_installed")

            // 7.sqm: journal_entries.project_name
            assertTrue(columnExists(driver, "journal_entries", "project_name"), "7.sqm did not add project_name")

            // 9.sqm: journal_entries.project_path (8.sqm only backfills, no schema change)
            assertTrue(columnExists(driver, "journal_entries", "project_path"), "9.sqm did not add project_path")
        } finally {
            driver.close()
        }
    }

    @Test
    fun freshOpenOnDiskCreatesSchemaAndWritesUserVersion() {
        val path = newTempDbPath()
        val handle = CatalogDb.openOnDisk(path)
        try {
            assertEquals(
                Catalog.Schema.version,
                readUserVersion(handle.driver),
                "openOnDisk on a missing file should create schema and stamp user_version",
            )
            assertTrue(tableExists(handle.driver, "projects"))
            assertTrue(tableExists(handle.driver, "journal_entries"))
            assertTrue(columnExists(handle.driver, "journal_entries", "project_path"))
        } finally {
            handle.driver.close()
        }
    }

    @Test
    fun reopeningExistingOnDiskDbIsIdempotent() {
        val path = newTempDbPath()
        CatalogDb.openOnDisk(path).driver.close()
        // Second open must not re-CREATE TABLE (would crash with "table already exists") and must
        // not re-run migrations on top of an already-target DB (would crash on duplicate ADD
        // COLUMN). ensureSchema's `current >= target` branch covers this — pin it.
        val handle = CatalogDb.openOnDisk(path)
        try {
            assertEquals(Catalog.Schema.version, readUserVersion(handle.driver))
        } finally {
            handle.driver.close()
        }
    }

    @Test
    fun preTrackingDbWithoutUserVersionDetectsCorrectVersionAndUpgrades() {
        // Mimics what an early-release catalog looked like on disk: schema present at
        // approximately v3 (post 1.sqm + 2.sqm; pre journal_entries from 3.sqm), no
        // `user_version` because tracking hadn't been added yet. ensureSchema's heuristic
        // should detect "no journal_entries → start at 3" and migrate forward to target.
        val path = newTempDbPath()
        run {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}", Properties())
            try {
                createV0ShapedTables(driver)
                // Apply 1.sqm + 2.sqm only — leaves a v3-shape DB (sync_state.updated_at present,
                // repair_acks/proposal_acks present, journal_entries absent).
                driver.execute(
                    null,
                    "ALTER TABLE sync_state ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
                    0,
                )
                driver.execute(null, "CREATE INDEX idx_sync_state_dirty_updated_at ON sync_state(dirty, updated_at)", 0)
                driver.execute(
                    null,
                    """
                    CREATE TABLE repair_acks (
                      scope TEXT NOT NULL,
                      project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                      payload TEXT NOT NULL DEFAULT '',
                      acked_at TEXT NOT NULL,
                      PRIMARY KEY (scope, project_id, payload)
                    )
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    """
                    CREATE TABLE proposal_acks (
                      proposal_key TEXT PRIMARY KEY,
                      status TEXT NOT NULL,
                      decided_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                    0,
                )
                // Explicitly leave user_version at 0 — that's the whole point.
                driver.execute(null, "PRAGMA user_version = 0", 0)
            } finally {
                driver.close()
            }
        }

        val handle = CatalogDb.openOnDisk(path)
        try {
            assertEquals(
                Catalog.Schema.version,
                readUserVersion(handle.driver),
                "ensureSchema should have stamped user_version after detection + migrate",
            )
            assertTrue(tableExists(handle.driver, "journal_entries"), "3.sqm onward should have run")
            assertTrue(columnExists(handle.driver, "journal_entries", "project_path"), "9.sqm should have run")
            assertTrue(columnExists(handle.driver, "project_plugins", "is_installed"), "6.sqm should have run")
        } finally {
            handle.driver.close()
        }
    }

    /**
     * The minimum subset of v0-shape tables that migrations 1..N reference. Mirrors the column
     * sets that existed before any migration ran. Indexes/triggers/FTS shadows are skipped — the
     * migrations don't touch them, and including them would just slow the test down.
     */
    private fun createV0ShapedTables(driver: SqlDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE projects (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              path          TEXT    NOT NULL UNIQUE,
              name          TEXT    NOT NULL,
              parent_dir    TEXT    NOT NULL DEFAULT '',
              last_modified REAL    NOT NULL DEFAULT 0,
              last_scanned  REAL    NOT NULL DEFAULT 0,
              key           TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE sync_state (
              project_id INTEGER PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
              dirty      INTEGER NOT NULL DEFAULT 0,
              last_rev   TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE project_plugins (
              project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
              dev_name   TEXT NOT NULL,
              plugin_name TEXT NOT NULL,
              format     TEXT NOT NULL,
              PRIMARY KEY (project_id, dev_name, plugin_name, format)
            )
            """.trimIndent(),
            0,
        )
    }

    private fun newTempDbPath(): Path {
        val path = Files.createTempFile("sketchbook-migration-walk-", ".db")
        // openOnDisk wants the file to either exist (and contain a valid SQLite DB) or not exist.
        // createTempFile leaves a zero-byte file behind, which JDBC happily treats as empty —
        // that's the path we want for the fresh-create tests. For the pre-tracking test the
        // caller writes the v0/v3-shape directly into this same file.
        path.deleteIfExists()
        tempFiles.add(path)
        return path
    }

    private fun readUserVersion(driver: SqlDriver): Long {
        var version = 0L
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { c: SqlCursor ->
                if (c.next().value) version = c.getLong(0) ?: 0L
                QueryResult.Unit
            },
            parameters = 0,
        )
        return version
    }

    private fun columnExists(
        driver: SqlDriver,
        table: String,
        column: String,
    ): Boolean {
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { c: SqlCursor ->
                while (c.next().value) {
                    if (c.getString(1) == column) {
                        found = true
                        break
                    }
                }
                QueryResult.Unit
            },
            parameters = 0,
        )
        return found
    }

    private fun indexExists(
        driver: SqlDriver,
        name: String,
    ): Boolean {
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ? LIMIT 1",
            mapper = { c: SqlCursor ->
                if (c.next().value) found = true
                QueryResult.Unit
            },
            parameters = 1,
        ) { bindString(0, name) }
        return found
    }

    private fun tableExists(
        driver: SqlDriver,
        name: String,
    ): Boolean {
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            mapper = { c: SqlCursor ->
                if (c.next().value) found = true
                QueryResult.Unit
            },
            parameters = 1,
        ) { bindString(0, name) }
        return found
    }
}
