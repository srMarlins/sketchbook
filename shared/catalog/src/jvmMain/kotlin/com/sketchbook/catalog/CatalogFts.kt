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

    /**
     * Return matching project rowids ordered by relevance (bm25 ascending = best first).
     *
     * Bounds the result set at [DEFAULT_LIMIT] to keep search responsive — a bare-stem query
     * like `s` against an FTS5 index over a 1,628-project catalog can return thousands of
     * rowids that the caller would then re-fetch and ship through the Compose differ.
     *
     * Sanitises caller input by quoting it as a single FTS5 phrase. Without this, characters
     * with FTS5 syntactic meaning (`-`, `:`, `"`, `(`, `)`, `*`, `^`, AND/OR/NOT) raise a
     * SyntaxException out of the SQLite engine on user typing — e.g. searching for "co-write".
     * Phrase-quoting also gives prefix queries a stable shape (the caller can append `*` per
     * the FTS5 prefix-match docs once we surface that as a UI choice).
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<Long> {
        val sanitized = sanitize(query)
        if (sanitized.isEmpty()) return emptyList()
        val out = ArrayList<Long>(minOf(limit, 64))
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT rowid
                FROM projects_fts
                WHERE projects_fts MATCH ?
                ORDER BY bm25(projects_fts)
                LIMIT ?
            """.trimIndent(),
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    cursor.getLong(0)?.let { out += it }
                }
                QueryResult.Unit
            },
            parameters = 2,
        ) {
            bindString(0, sanitized)
            bindLong(1, limit.toLong())
        }
        return out
    }

    private fun sanitize(raw: String): String {
        // Strip everything that isn't a word character or whitespace, then re-quote each
        // surviving token as a phrase. FTS5 treats unquoted `-`, `:`, etc. as operators and
        // raises a SyntaxException; quoted phrases turn them back into literals.
        val tokens = raw.trim().split(WHITESPACE)
            .map { it.replace(QUOTE_OR_BAD, "") }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "\"$it\"" }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 200
        private val WHITESPACE = Regex("\\s+")

        // Strip FTS5 syntax characters and the quote we'll be wrapping with.
        private val QUOTE_OR_BAD = Regex("[\"():*^]")
    }
}

@Suppress("unused")
private fun touchPreparedStatement(): SqlPreparedStatement? = null
