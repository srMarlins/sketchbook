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
 * **Disk driver pools connections.** SQLite in WAL mode lets readers run concurrently with the
 * single writer, but only across *separate* JDBC connections. The previous setup used a single
 * `JdbcSqliteDriver` connection, which serialized every read behind any in-flight write
 * transaction (scanner, setTags, etc.). The disk driver wraps a [SQLiteDataSource] in HikariCP
 * so SQLDelight's `Query.asFlow()` reads can run in parallel with the scanner's writer
 * transactions.
 *
 * **Pragmas land on each connection.** `journal_mode = WAL` is sticky at the database level,
 * but `synchronous`, `cache_size`, `mmap_size`, `temp_store`, and `foreign_keys` are
 * per-connection — HikariCP's connection-init hook runs them on every new connection. The
 * desktop app + MCP server share a single `catalog.db`, so each process pools its own
 * connections; the WAL coordinates writes between them.
 */
object CatalogDb {

    /** PRAGMAs applied to the live JDBC connection on bring-up. */
    private val PER_CONNECTION_PRAGMAS: List<String> = listOf(
        "PRAGMA foreign_keys = ON",
        // synchronous=NORMAL is the recommended pairing with WAL: durable across process
        // crashes (only loses the last in-flight transaction on a power cut), one fsync per
        // checkpoint instead of per-commit.
        "PRAGMA synchronous = NORMAL",
        // 64 MB page cache (negative = KiB). Default 2 MB chokes on the FTS5 `MATCH` joins
        // when the catalog passes a few thousand rows.
        "PRAGMA cache_size = -65536",
        // 256 MB mmap window. Lets the kernel page DB blocks without copying into the JVM
        // heap on read-heavy paths (UI list refresh after scan).
        "PRAGMA mmap_size = 268435456",
        // Keep temp B-trees and intermediate sort buffers in memory.
        "PRAGMA temp_store = MEMORY",
        // Auto-checkpoint every 1,000 pages of WAL (~4 MB).
        "PRAGMA wal_autocheckpoint = 1000",
    )

    fun openOnDisk(path: Path): CatalogHandle {
        // Single-connection JdbcSqliteDriver. The earlier `pooled.asJdbcDriver()` setup didn't
        // fail because of HikariCP — `DataSource.asJdbcDriver()` in SQLDelight 2.x is documented
        // to return a driver whose `addListener`/`removeListener`/`notifyListeners` are
        // **no-ops**. See drivers/jdbc-driver/.../JdbcDriver.kt:
        //
        //   override fun addListener(...)    { /* No-op. JDBC Driver is not set up for
        //                                         observing queries by default. */ }
        //   override fun notifyListeners(...) { /* No-op. */ }
        //
        // `Query.asFlow().mapToList()` produces the initial fetch but then waits forever for a
        // notification that the driver is contractually incapable of sending. User-visible
        // symptom: project list frozen during scan. CatalogDbReactiveInvalidationTest pins it.
        //
        // Fix: use `JdbcSqliteDriver(jdbcUrl)` — its `addListener`/`notifyListeners` actually
        // store and dispatch to a `LinkedHashMap<String, MutableSet<Query.Listener>>`. Single
        // connection isn't a real downgrade for this workload: xerial-sqlite-jdbc serializes
        // reads through a single Connection mutex anyway, so the pool was buying nothing in
        // a single-process app. WAL still gives us multi-process concurrency for the desktop ↔
        // MCP setup. If we ever need a pool with listeners, the upstream-recommended path is a
        // custom JdbcDriver subclass that copies JdbcSqliteDriver's listener map.
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${path.toAbsolutePath()}",
            Properties(),
        )
        // Apply both the sticky DB-level pragma (journal_mode=WAL) and the per-connection set on
        // the single live connection.
        driver.execute(identifier = null, sql = "PRAGMA journal_mode = WAL", parameters = 0)
        for (pragma in PER_CONNECTION_PRAGMAS) {
            driver.execute(identifier = null, sql = pragma, parameters = 0)
        }
        ensureSchema(driver)
        return CatalogHandle(Catalog(driver), driver)
    }

    fun openInMemory(): CatalogHandle {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        // In-memory always starts at version 0; create unconditionally.
        Catalog.Schema.create(driver)
        writeUserVersion(driver, Catalog.Schema.version)
        applyInMemoryPragmas(driver)
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
        // tables are already present. Detect that case via sqlite_master and probe known columns
        // to figure out which schema version they're actually at, then run only the migrations
        // missing from there to the current target.
        val schemaPresent = tableExists(driver, "projects")
        when {
            !schemaPresent -> {
                Catalog.Schema.create(driver)
                writeUserVersion(driver, target)
            }

            current == 0L && schemaPresent -> {
                // Pre-tracking DB. Probe per-version markers to derive the actual installed
                // schema version. Each marker is a column added in the corresponding `<n>.sqm`
                // migration; presence means migrations up to and including <n> have already
                // been applied. List in *ascending* order so the lowest missing marker wins.
                val detected = when {
                    // before 1.sqm
                    !columnExists(driver, "sync_state", "updated_at") -> 1L

                    // before 2.sqm
                    !tableExists(driver, "repair_acks") -> 2L

                    // before 3.sqm
                    !tableExists(driver, "journal_entries") -> 3L

                    // before 4.sqm
                    !indexExists(driver, "idx_projects_key") -> 4L

                    // before 5.sqm
                    !columnExists(driver, "project_plugins", "is_installed") -> 5L

                    // before 7.sqm — project_name added by this PR's 7.sqm
                    !columnExists(driver, "journal_entries", "project_name") -> 7L

                    else -> target
                }
                if (detected < target) {
                    Catalog.Schema.migrate(driver, detected, target)
                }
                writeUserVersion(driver, target)
            }

            current < target -> {
                Catalog.Schema.migrate(driver, current, target)
                writeUserVersion(driver, target)
            }
            // current >= target: schema is up to date; nothing to do.
        }
    }

    private fun columnExists(driver: SqlDriver, table: String, column: String): Boolean {
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { c: SqlCursor ->
                while (c.next().value) {
                    // table_info columns: cid, name, type, notnull, dflt_value, pk
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

    private fun indexExists(driver: SqlDriver, name: String): Boolean {
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

    private fun applyInMemoryPragmas(driver: SqlDriver) {
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    }
}
