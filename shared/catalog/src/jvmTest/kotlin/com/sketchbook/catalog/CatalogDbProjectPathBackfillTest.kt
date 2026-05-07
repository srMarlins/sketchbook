package com.sketchbook.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sketchbook.catalog.db.Catalog
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the 9.sqm `project_path` backfill: after the migration runs against a v8-shaped DB, every
 * journal_entries row whose `project_id` still resolves in the catalog gets its `project_path`
 * filled from `projects.path`. Truly-orphaned rows (project_id no longer in `projects`) stay NULL
 * and the History UI falls through to its sentinel — that's the documented forward-looking
 * close-out, not a regression.
 *
 * Builds a deliberately old-shape `journal_entries` (no `project_path` column, mirrors what
 * 8.sqm-era DBs look like on disk) so we can exercise `Catalog.Schema.migrate(8, 9)` against a
 * realistic pre-9 state. Using `Schema.create()` then dropping the column is messier in SQLite,
 * which doesn't support DROP COLUMN cleanly across all supported versions.
 */
class CatalogDbProjectPathBackfillTest {
    @Test
    fun migrate8To9AddsProjectPathAndBackfillsResolvableEntries() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        try {
            // Minimum tables 9.sqm touches: projects (path source) + journal_entries (target).
            createV8ShapedTables(driver)

            // Two projects + three entries:
            //   - id=1: still resolves via projects → should get project_path filled.
            //   - id=2: still resolves via projects → should get project_path filled.
            //   - id=999: orphaned (no projects row) → stays NULL.
            insertProject(driver, id = 1, path = "/lib/Track A.als", name = "Track A")
            insertProject(driver, id = 2, path = "/lib/Track B.als", name = "Track B")
            insertJournalEntry(driver, id = 10, projectId = 1, projectName = "Track A")
            insertJournalEntry(driver, id = 11, projectId = 2, projectName = "Track B")
            insertJournalEntry(driver, id = 12, projectId = 999, projectName = null)

            // 9.sqm is registered at `oldVersion <= 9 && newVersion > 9` (SQLDelight numbers a
            // migration as "this brings you TO version N+1"). Drive migrate from 9 → 10 so the
            // ADD COLUMN + UPDATE land. The 8.sqm chunk (which updates project_name from the
            // catalog) is intentionally *not* exercised here — this hand-built schema is missing
            // columns that earlier chunks would touch, and the project_path backfill is what
            // we're pinning.
            Catalog.Schema.migrate(driver, oldVersion = 9L, newVersion = 10L)

            assertEquals("/lib/Track A.als", selectPath(driver, entryId = 10))
            assertEquals("/lib/Track B.als", selectPath(driver, entryId = 11))
            assertNull(selectPath(driver, entryId = 12), "orphaned entry should keep NULL project_path")
        } finally {
            driver.close()
        }
    }

    /**
     * The minimum subset of v8-shaped schema needed by 9.sqm. Mirrors the column set the 8-era
     * `Catalog.sq` produced before this PR added `project_path`. Indexes are skipped — they don't
     * affect the migration's correctness and SQLDelight's `Schema.create()` would also create
     * them, so the difference is only present-vs-absent.
     */
    private fun createV8ShapedTables(driver: SqlDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE projects (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,
              path            TEXT    NOT NULL UNIQUE,
              name            TEXT    NOT NULL,
              parent_dir      TEXT    NOT NULL DEFAULT '',
              last_modified   REAL    NOT NULL DEFAULT 0,
              last_scanned    REAL    NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE journal_entries (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              occurred_at  INTEGER NOT NULL,
              actor        TEXT    NOT NULL,
              action_type  TEXT    NOT NULL,
              project_id   INTEGER NOT NULL,
              payload_json TEXT    NOT NULL DEFAULT '{}',
              project_name TEXT
            )
            """.trimIndent(),
            0,
        )
    }

    private fun insertProject(
        driver: SqlDriver,
        id: Long,
        path: String,
        name: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO projects (id, path, name, parent_dir, last_modified, last_scanned) VALUES (?, ?, ?, '', 0, 0)",
            3,
        ) {
            bindLong(0, id)
            bindString(1, path)
            bindString(2, name)
        }
    }

    private fun insertJournalEntry(
        driver: SqlDriver,
        id: Long,
        projectId: Long,
        projectName: String?,
    ) {
        driver.execute(
            null,
            "INSERT INTO journal_entries (id, occurred_at, actor, action_type, project_id, payload_json, project_name) " +
                "VALUES (?, 0, 'user', 'Move', ?, '{}', ?)",
            3,
        ) {
            bindLong(0, id)
            bindLong(1, projectId)
            bindString(2, projectName)
        }
    }

    private fun selectPath(
        driver: SqlDriver,
        entryId: Long,
    ): String? {
        var out: String? = null
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT project_path FROM journal_entries WHERE id = ?",
            mapper = { c: SqlCursor ->
                if (c.next().value) {
                    found = true
                    out = c.getString(0)
                }
                QueryResult.Unit
            },
            parameters = 1,
        ) { bindLong(0, entryId) }
        check(found) { "entry $entryId not found" }
        return out
    }
}
