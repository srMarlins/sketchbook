package com.sketchbook.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Raw FTS5 ops. SQLDelight 2.1's static typer can't handle the `MATCH` operator or `bm25()`
 * on virtual tables (it recurses on column-type resolution). Until upstream lands a fix, we
 * drive FTS through `SqlDriver.execute` / `executeQuery` directly. The schema for `projects_fts`
 * still lives in `Catalog.sq` so migrations stay in one place.
 */
class CatalogFts(private val driver: SqlDriver) {

    fun upsert(
        rowid: Long,
        name: String,
        parentDir: String,
        pluginNames: String,
        sampleFilenames: String,
        notes: String,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO projects_fts (rowid, name, parent_dir, plugin_names, sample_filenames, notes)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 6,
        ) {
            bindLong(0, rowid)
            bindString(1, name)
            bindString(2, parentDir)
            bindString(3, pluginNames)
            bindString(4, sampleFilenames)
            bindString(5, notes)
        }
    }

    fun delete(rowid: Long) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM projects_fts WHERE rowid = ?",
            parameters = 1,
        ) {
            bindLong(0, rowid)
        }
    }

    /** Return matching project rowids ordered by relevance (bm25 ascending = best first). */
    fun search(query: String): List<Long> {
        val out = mutableListOf<Long>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT rowid
                FROM projects_fts
                WHERE projects_fts MATCH ?
                ORDER BY bm25(projects_fts)
            """.trimIndent(),
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    cursor.getLong(0)?.let { out += it }
                }
                QueryResult.Unit
            },
            parameters = 1,
        ) {
            bindString(0, query)
        }
        return out
    }
}

@Suppress("unused")
private fun touchPreparedStatement(): SqlPreparedStatement? = null
