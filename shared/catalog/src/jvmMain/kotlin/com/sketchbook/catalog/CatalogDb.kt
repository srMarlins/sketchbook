package com.sketchbook.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sketchbook.catalog.db.Catalog
import java.nio.file.Path
import java.util.Properties

/**
 * Bundles a [Catalog] with the [SqlDriver] backing it. Some operations (FTS5 with the `MATCH`
 * operator, raw `PRAGMA`s) need the driver directly because SQLDelight 2.1's static typer
 * can't model them.
 */
data class CatalogHandle(val catalog: Catalog, val driver: SqlDriver)

/**
 * Factory for the JVM SQLDelight driver. Two flavors:
 * - [openOnDisk]: file-backed catalog at the given path. Used by `app-desktop` + `app-mcp`.
 * - [openInMemory]: ephemeral DB. Used in tests.
 *
 * Foreign keys are enabled; WAL is enabled for the disk driver to allow read-while-write
 * (the desktop app + MCP server share a catalog.db).
 */
object CatalogDb {

    fun openOnDisk(path: Path): CatalogHandle {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}", Properties())
        ensureSchema(driver)
        applyPragmas(driver, journalModeWal = true)
        return CatalogHandle(Catalog(driver), driver)
    }

    fun openInMemory(): CatalogHandle {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        // In-memory always starts at version 0; create unconditionally.
        Catalog.Schema.create(driver)
        writeUserVersion(driver, Catalog.Schema.version)
        applyPragmas(driver, journalModeWal = false)
        return CatalogHandle(Catalog(driver), driver)
    }

    /**
     * Idempotent schema bring-up. SQLDelight's `Schema.create(driver)` issues raw `CREATE TABLE`
     * statements that fail with `table … already exists` on the second launch. Gate on
     * `PRAGMA user_version` and run `Schema.migrate` for incremental upgrades.
     */
    private fun ensureSchema(driver: SqlDriver) {
        val current = readUserVersion(driver)
        val target = Catalog.Schema.version
        // Existing DBs created before user_version tracking landed will report 0 even though the
        // tables are already present. Detect that case via sqlite_master and adopt `target`
        // without re-running CREATE (which would fail with "already exists").
        val schemaPresent = tableExists(driver, "projects")
        when {
            !schemaPresent -> {
                Catalog.Schema.create(driver)
                writeUserVersion(driver, target)
            }
            current == 0L && schemaPresent -> {
                // Pre-tracking DB; record current version as target so future launches no-op.
                writeUserVersion(driver, target)
            }
            current < target -> {
                Catalog.Schema.migrate(driver, current, target)
                writeUserVersion(driver, target)
            }
            // current >= target: schema is up to date; nothing to do.
        }
    }

    private fun tableExists(driver: SqlDriver, name: String): Boolean {
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

    private fun writeUserVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version;", 0)
    }

    private fun applyPragmas(driver: SqlDriver, journalModeWal: Boolean) {
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        if (journalModeWal) {
            driver.execute(null, "PRAGMA journal_mode = WAL;", 0)
            // synchronous=NORMAL is the recommended pairing with WAL: durable across process
            // crashes (only loses the last in-flight transaction on a power cut), one fsync per
            // checkpoint instead of per-commit. The scan loop touches ~1,628 rows in
            // back-to-back transactions; FULL means ~1,628 fsyncs and is the single biggest
            // cause of cold-scan time on spinning disks.
            driver.execute(null, "PRAGMA synchronous = NORMAL;", 0)
            // 64 MB page cache (negative = KiB). Default 2 MB chokes on the FTS5 `MATCH` joins
            // when the catalog passes a few thousand rows.
            driver.execute(null, "PRAGMA cache_size = -65536;", 0)
            // 256 MB mmap window. Lets the kernel page DB blocks without copying into the JVM
            // heap on read-heavy paths (UI list refresh after scan).
            driver.execute(null, "PRAGMA mmap_size = 268435456;", 0)
            // Keep temp B-trees and intermediate sort buffers in memory rather than spilling to
            // a tempfile under %TEMP% / /tmp.
            driver.execute(null, "PRAGMA temp_store = MEMORY;", 0)
            // Auto-checkpoint every 1,000 pages of WAL (~4 MB) so the WAL doesn't grow
            // unboundedly during a long scan.
            driver.execute(null, "PRAGMA wal_autocheckpoint = 1000;", 0)
        }
    }
}
