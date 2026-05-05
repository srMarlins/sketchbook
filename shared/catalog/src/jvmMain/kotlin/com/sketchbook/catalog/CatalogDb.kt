package com.sketchbook.catalog

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
        Catalog.Schema.create(driver)
        applyPragmas(driver, journalModeWal = true)
        return CatalogHandle(Catalog(driver), driver)
    }

    fun openInMemory(): CatalogHandle {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        Catalog.Schema.create(driver)
        applyPragmas(driver, journalModeWal = false)
        return CatalogHandle(Catalog(driver), driver)
    }

    private fun applyPragmas(driver: SqlDriver, journalModeWal: Boolean) {
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        if (journalModeWal) driver.execute(null, "PRAGMA journal_mode = WAL;", 0)
    }
}
