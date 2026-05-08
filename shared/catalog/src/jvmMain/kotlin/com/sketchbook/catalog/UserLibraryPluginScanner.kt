package com.sketchbook.catalog

import com.sketchbook.als.AlsParser
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.PluginFormat
import com.sketchbook.core.PluginRef
import com.sketchbook.core.TrackedTreeId
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.time.Clock

/**
 * Walks the materialized User Library tree for files that carry plugin references and writes
 * the union into [Catalog.user_library_plugins]. Runs after each materialize event so the
 * bootstrap plugin checklist's "needs install" view covers templates + default racks +
 * Defaults `.als` files — anything in the UL that uses a plugin by name.
 *
 * Mirrors [JvmScanner]'s plugin-extraction subset for projects but writes to a different
 * table. The two share [AlsParser]; the table separation keeps project queries narrow (no
 * `WHERE kind = 'project'` everywhere) per the design doc's catalog rationale.
 *
 * **Files scanned:** `*.als` (Live Set / template) and `*.adg` (Audio/Instrument/MIDI
 * Effect Rack — also gzipped XML; the parser handles either). `*.adv` files (single-device
 * presets) intentionally aren't walked here — vendors save them under their own preset
 * folders, not the UL, and parsing them adds little value relative to the I/O cost.
 *
 * **Idempotency:** the scan upserts on `(tree_id, rel_path, plugin_name, plugin_type)`.
 * Re-scanning the same tree replaces stale rows; relpaths that disappear from the UL get
 * cleaned via [pruneRemoved].
 */
class UserLibraryPluginScanner(
    private val catalog: Catalog,
    private val parser: PluginRefParser = PluginRefParser.AlsParserAdapter,
    private val clock: Clock = Clock.System,
) {
    /**
     * Scan [root] and upsert one row per `(rel_path, plugin)` discovered. Returns the rel
     * paths actually scanned so the caller can reconcile against a future tombstone-driven
     * removal pass (commit 14 wires this).
     */
    fun scan(
        treeId: TrackedTreeId,
        root: Path,
    ): ScanResult {
        if (!Files.exists(root)) {
            return ScanResult(scannedRelpaths = emptyList(), pluginCount = 0)
        }
        val now = clock.now().toEpochMilliseconds()
        // Don't follow symlinks — UL setups commonly link out to other plugin install dirs (or to
        // network shares); we want the user-content surface only, and we also don't want
        // FileSystemLoopException to take down the scan if the user has a cycle.
        val files: List<Path> =
            Files.walk(root).use { stream ->
                stream
                    .asSequence()
                    .filter { it.isRegularFile() }
                    .filter { p ->
                        val n = p.fileName.toString()
                        n.endsWith(".als", ignoreCase = true) || n.endsWith(".adg", ignoreCase = true)
                    }.toList()
            }
        val scannedRelpaths = mutableListOf<String>()
        var totalPlugins = 0
        catalog.transaction {
            for (file in files) {
                val rel = root.relativize(file).toString().replace('\\', '/')
                scannedRelpaths += rel
                // Replace the prior row-set for this (tree, rel) so a removed plugin from a
                // re-saved template doesn't linger.
                catalog.catalogQueries.deleteUserLibraryPluginsForTreeRel(
                    tree_id = treeId.value,
                    rel_path = rel,
                )
                // A bad ALS/ADG should drop *its* plugins, not the rest of the scan. We treat
                // parser failures as "this file has no extractable plugins" rather than aborting.
                // CancellationException is rethrown so the parent scope can still cancel mid-scan
                // — runCatching used to swallow it. Audit tracked in #132.
                val plugins =
                    try {
                        parser.parsePlugins(file)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        emptyList()
                    }
                for (plugin in plugins) {
                    catalog.catalogQueries.upsertUserLibraryPlugin(
                        tree_id = treeId.value,
                        rel_path = rel,
                        plugin_name = plugin.name,
                        plugin_type = plugin.format.wireName,
                        is_installed = 0L,
                        last_seen_at = now,
                    )
                    totalPlugins += 1
                }
            }
        }
        return ScanResult(scannedRelpaths = scannedRelpaths, pluginCount = totalPlugins)
    }

    /**
     * Drop every row for [treeId] whose [rel_path] isn't in [keepRelpaths]. Called by the
     * bootstrap pipeline (commit 14) when a tombstone removes a UL file so the plugin
     * checklist doesn't keep promoting "needs install" rows for files that no longer exist.
     */
    fun pruneRemoved(
        treeId: TrackedTreeId,
        keepRelpaths: Set<String>,
    ) {
        catalog.transaction {
            val rows =
                catalog.catalogQueries
                    .selectUserLibraryPluginsForTree(tree_id = treeId.value)
                    .executeAsList()
            for (row in rows) {
                if (row.rel_path !in keepRelpaths) {
                    catalog.catalogQueries.deleteUserLibraryPluginsForTreeRel(
                        tree_id = row.tree_id,
                        rel_path = row.rel_path,
                    )
                }
            }
        }
    }

    data class ScanResult(
        val scannedRelpaths: List<String>,
        val pluginCount: Int,
    )

    /**
     * Indirection so tests can supply fixture plugin lists without going through the full
     * gzipped-XML parser. Production wires [AlsParserAdapter].
     */
    fun interface PluginRefParser {
        fun parsePlugins(path: Path): List<PluginRef>

        companion object {
            val AlsParserAdapter: PluginRefParser =
                PluginRefParser { path -> AlsParser.parse(path).plugins }
        }
    }
}
