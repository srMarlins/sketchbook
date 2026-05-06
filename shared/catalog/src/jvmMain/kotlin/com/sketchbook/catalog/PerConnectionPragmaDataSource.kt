package com.sketchbook.catalog

import java.sql.Connection
import javax.sql.DataSource

/**
 * Thin wrapper around a [DataSource] that issues per-connection PRAGMAs on every connection
 * acquired from the pool. Used by [CatalogDb.openOnDisk] to apply SQLite session pragmas
 * (`synchronous`, `cache_size`, `mmap_size`, `temp_store`, `foreign_keys`,
 * `wal_autocheckpoint`) on each pooled JDBC connection.
 *
 * **Why a wrapper?** HikariCP's `connectionInitSql` only takes one statement, but the xerial
 * SQLite JDBC driver does not multiplex multiple semicolon-separated statements. Rather than
 * hand-rolling a multi-statement parser, this wrapper runs each pragma in its own
 * `Statement.execute` call after the underlying pool hands us a connection. The first time a
 * physical SQLite connection is borrowed it gets the pragmas; subsequent borrows of the same
 * already-warm connection get them again, which is a few microseconds each — cheap, and means
 * we don't have to track which connection has been initialised.
 *
 * The wrapper otherwise delegates all DataSource operations to the wrapped instance, so it
 * works transparently with `JdbcDrivers.fromDataSource(...)`.
 */
internal class PerConnectionPragmaDataSource(
    private val delegate: DataSource,
    private val pragmas: List<String>,
) : DataSource by delegate {

    override fun getConnection(): Connection = applyPragmas(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        applyPragmas(delegate.getConnection(username, password))

    private fun applyPragmas(connection: Connection): Connection {
        runCatching {
            connection.createStatement().use { st ->
                for (pragma in pragmas) st.execute(pragma)
            }
        }
        return connection
    }
}
