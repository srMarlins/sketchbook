package com.sketchbook.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogDbTest {
    private val handle = CatalogDb.openInMemory()

    @Test
    fun schemaCreatesAllExpectedTables() {
        val tables = listSqliteObjects("table")
        for (t in listOf(
            "projects",
            "project_plugins",
            "project_samples",
            "samples",
            "tags",
            "project_tags",
            "project_identity",
            "snapshots",
            "blob_cache",
            "sync_state",
            "pending_uploads",
            "repair_acks",
            "proposal_acks",
            "journal_entries",
            "tree_sync_state",
            "tree_registry_cache",
            "tree_snapshots",
            "tree_journal",
        )) {
            assertTrue(t in tables, "missing table $t — got $tables")
        }
    }

    @Test
    fun ftsVirtualTableIsPresent() {
        val tables = listSqliteObjects("table")
        assertTrue("projects_fts" in tables)
        assertTrue("projects_fts_data" in tables, "FTS5 should create shadow tables; got $tables")
    }

    @Test
    fun foreignKeysEnabled() {
        val out = mutableListOf<Long>()
        handle.driver.executeQuery(null, "PRAGMA foreign_keys", { c ->
            while (c.next().value) c.getLong(0)?.let { out += it }
            QueryResult.Unit
        }, 0)
        assertEquals(1, out.first())
    }

    private fun listSqliteObjects(kind: String): Set<String> {
        val out = mutableSetOf<String>()
        handle.driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = ?",
            mapper = { c: SqlCursor ->
                while (c.next().value) c.getString(0)?.let { out += it }
                QueryResult.Unit
            },
            parameters = 1,
        ) { bindString(0, kind) }
        return out
    }
}
