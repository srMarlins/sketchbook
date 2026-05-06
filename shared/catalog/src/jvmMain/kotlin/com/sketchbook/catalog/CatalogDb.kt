package com.sketchbook.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sketchbook.catalog.db.Catalog
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.sqlite.SQLiteDataSource
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

    /** Pool size for the disk driver. 1 writer + a few readers; SQLite WAL handles the rest. */
    private const val MAX_POOL_SIZE: Int = 4

    /** Per-connection PRAGMAs. Run on every new connection HikariCP opens. */
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
        val sqliteDs = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${path.toAbsolutePath()}"
        }
        // Open one bootstrap connection through the raw DataSource to set sticky DB-level
        // PRAGMAs (journal_mode=WAL) before any pooled connection sees the file. Doing this
        // through HikariCP's connectionInitSql races with concurrent reads on cold pool warm-up.
        sqliteDs.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode = WAL")
                for (pragma in PER_CONNECTION_PRAGMAS) st.execute(pragma)
            }
        }
        val hikariConfig = HikariConfig().apply {
            dataSource = sqliteDs
            maximumPoolSize = MAX_POOL_SIZE
            minimumIdle = 1
            poolName = "sketchbook-catalog"
            // HikariCP runs this once per new connection; xerial's driver allows one statement
            // per execute() call, so we configure pragmas via a Connection callback instead and
            // leave connectionInitSql empty.
        }
        val hikari = HikariDataSource(hikariConfig)
        // HikariCP doesn't expose a "after-acquire" hook directly; subclass the DataSource to
        // run pragmas on every fresh connection. We do this by registering a wrapper.
        val pooled = PerConnectionPragmaDataSource(hikari, PER_CONNECTION_PRAGMAS)
        val driver = pooled.asJdbcDriver()
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
                    !columnExists(driver, "sync_state", "updated_at") -> 1L  // before 1.sqm
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
