package com.sketchbook.mcp.app

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.mcp.FileProposalsWriter
import com.sketchbook.mcp.McpServer
import com.sketchbook.mcp.Tools
import com.sketchbook.mcp.runStdio
import com.sketchbook.repo.ProjectFtsSearcher
import com.sketchbook.repo.impl.SqlJournalRepository
import com.sketchbook.repo.impl.SqlProjectRepository
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Stdio MCP entry point. Composes the same SQLite-backed catalog the desktop app uses (read-only
 * from this process — writes are mediated through `propose_batch` which lands JSON files under
 * `<dataDir>/proposals/` for the desktop UI to surface and execute).
 *
 * The catalog DB path is shared with the desktop app:
 *  - Windows: `%APPDATA%\Sketchbook\catalog.db`
 *  - macOS:   `~/Library/Application Support/Sketchbook/catalog.db`
 *  - Linux:   `~/.local/share/sketchbook/catalog.db`
 *
 * Override with `SKETCHBOOK_DB_PATH` (test/CI). Proposals override is `SKETCHBOOK_PROPOSALS_DIR`.
 */
fun main() {
    val dbPath = catalogDbPath()
    val proposalsDir = proposalsDir()
    Files.createDirectories(proposalsDir)

    System.err.println("sketchbook MCP server: db=$dbPath proposals=$proposalsDir")

    val handle = CatalogDb.openOnDisk(dbPath)
    val fts = CatalogFts(handle.driver)
    val journal = SqlJournalRepository(catalog = handle.catalog, ioDispatcher = Dispatchers.IO)
    val repository =
        SqlProjectRepository(
            catalog = handle.catalog,
            ioDispatcher = Dispatchers.IO,
            journal = journal,
            fts = ProjectFtsSearcher { query -> fts.search(query) },
        )
    val proposalsWriter = FileProposalsWriter(root = proposalsDir)
    val tools = Tools(repository = repository, proposalsWriter = proposalsWriter)
    val server = McpServer(tools = tools)

    runStdio(server)
}

private fun catalogDbPath(): Path {
    System.getenv("SKETCHBOOK_DB_PATH")?.let { override ->
        val p = Paths.get(override)
        Files.createDirectories(p.parent ?: p)
        return p
    }
    return dataDir().resolve("catalog.db")
}

private fun proposalsDir(): Path {
    System.getenv("SKETCHBOOK_PROPOSALS_DIR")?.let { return Paths.get(it) }
    return dataDir().resolve("proposals")
}

private fun dataDir(): Path {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = Paths.get(System.getProperty("user.home"))
    val dir =
        when {
            os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: home.toString()).resolve("Sketchbook")
            os.contains("mac") -> home.resolve("Library/Application Support/Sketchbook")
            else -> home.resolve(".local/share/sketchbook")
        }
    Files.createDirectories(dir)
    return dir
}
