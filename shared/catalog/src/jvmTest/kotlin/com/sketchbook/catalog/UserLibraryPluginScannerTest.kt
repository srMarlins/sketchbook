package com.sketchbook.catalog

import com.sketchbook.core.PluginFormat
import com.sketchbook.core.PluginRef
import com.sketchbook.core.TrackedTreeId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class UserLibraryPluginScannerTest {
    private val ulRoot: Path = Files.createTempDirectory("sb-ul-scan-")
    private val treeId = TrackedTreeId("tt-ul-test")
    private val now = Instant.parse("2026-05-07T12:00:00Z")

    @AfterTest fun cleanup() {
        ulRoot.toFile().deleteRecursively()
    }

    private fun touch(
        rel: String,
        contents: String = rel,
    ) {
        val p = ulRoot.resolve(rel)
        p.parent.createDirectories()
        p.writeText(contents)
    }

    private fun scanner(
        catalog: com.sketchbook.catalog.db.Catalog,
        plugins: Map<String, List<PluginRef>>,
    ): UserLibraryPluginScanner {
        // Test parser: returns the plugin list for the rel filename. This avoids materializing
        // a real gzipped XML fixture for every assertion — the wiring under test is the
        // catalog write path, not the AlsParser internals (covered by AlsParserTest).
        val testParser =
            UserLibraryPluginScanner.PluginRefParser { path ->
                val rel = ulRoot.relativize(path).toString().replace('\\', '/')
                plugins[rel].orEmpty()
            }
        return UserLibraryPluginScanner(catalog, testParser, FixedClock(now))
    }

    /**
     * Seed [treeId] into `tree_registry_cache` so the FK on `user_library_plugins.tree_id`
     * (added by 11.sqm) is satisfied when the scanner inserts plugin rows. Mirrors what the
     * migrator + TreeRegistry would do in production before any UL scan runs.
     */
    private fun seedTree(catalog: com.sketchbook.catalog.db.Catalog) {
        catalog.catalogQueries.upsertTreeRegistryEntry(
            tree_id = treeId.value,
            tree_kind = "user_library",
            scope_key = "default",
            display_name = "User Library",
            owner_user_id = "DEFAULT",
            collaborators_json = "[]",
            created_at = now.toEpochMilliseconds(),
            created_by_host = "test-host",
            updated_at = now.toEpochMilliseconds(),
        )
    }

    @Test
    fun walksAlsAndAdgFilesUnderRoot() {
        touch("Templates/Live Set.als")
        touch("Defaults/Audio Effect Rack.adg")
        touch("Presets/foo.adv") // skipped — not .als / .adg
        touch("Samples/k.wav") // skipped — not .als / .adg

        val handle = CatalogDb.openInMemory()
        seedTree(handle.catalog)
        val plugins =
            mapOf(
                "Templates/Live Set.als" to
                    listOf(PluginRef("Serum", PluginFormat.Vst3, trackName = "Lead")),
                "Defaults/Audio Effect Rack.adg" to
                    listOf(PluginRef("FabFilter Pro-Q 3", PluginFormat.Au, trackName = null)),
            )
        val s = scanner(handle.catalog, plugins)

        val result = s.scan(treeId, ulRoot)

        assertEquals(setOf("Templates/Live Set.als", "Defaults/Audio Effect Rack.adg"), result.scannedRelpaths.toSet())
        assertEquals(2, result.pluginCount)

        val rows =
            handle.catalog.catalogQueries
                .selectUserLibraryPluginsForTree(tree_id = treeId.value)
                .executeAsList()
        assertEquals(2, rows.size)
        val byKey = rows.associateBy { it.rel_path to it.plugin_name }
        assertEquals("vst3", byKey["Templates/Live Set.als" to "Serum"]!!.plugin_type)
        assertEquals("au", byKey["Defaults/Audio Effect Rack.adg" to "FabFilter Pro-Q 3"]!!.plugin_type)
    }

    @Test
    fun reScanReplacesStalePlugins() {
        touch("Templates/Live Set.als")
        val handle = CatalogDb.openInMemory()
        seedTree(handle.catalog)

        scanner(handle.catalog, mapOf("Templates/Live Set.als" to listOf(PluginRef("Serum", PluginFormat.Vst3, null))))
            .scan(treeId, ulRoot)

        // Resave the template — Serum removed, Diva added.
        scanner(handle.catalog, mapOf("Templates/Live Set.als" to listOf(PluginRef("Diva", PluginFormat.Vst3, null))))
            .scan(treeId, ulRoot)

        val names =
            handle.catalog.catalogQueries
                .selectUserLibraryPluginsForTree(tree_id = treeId.value)
                .executeAsList()
                .map { it.plugin_name }
                .toSet()
        assertEquals(setOf("Diva"), names)
    }

    @Test
    fun rowsForRemovedFilesAreCleanedByPrune() {
        touch("Templates/A.als")
        touch("Templates/B.als")
        val handle = CatalogDb.openInMemory()
        seedTree(handle.catalog)
        val s =
            scanner(
                handle.catalog,
                mapOf(
                    "Templates/A.als" to listOf(PluginRef("Serum", PluginFormat.Vst3, null)),
                    "Templates/B.als" to listOf(PluginRef("Diva", PluginFormat.Vst3, null)),
                ),
            )
        s.scan(treeId, ulRoot)

        // User deletes B.als from the UL — caller invokes pruneRemoved with the surviving set.
        s.pruneRemoved(treeId, keepRelpaths = setOf("Templates/A.als"))

        val relpaths =
            handle.catalog.catalogQueries
                .selectUserLibraryPluginsForTree(tree_id = treeId.value)
                .executeAsList()
                .map { it.rel_path }
                .toSet()
        assertEquals(setOf("Templates/A.als"), relpaths)
    }

    @Test
    fun missingRootIsNoOp() {
        val handle = CatalogDb.openInMemory()
        val s = scanner(handle.catalog, emptyMap())
        val result = s.scan(treeId, ulRoot.resolve("does-not-exist"))
        assertTrue(result.scannedRelpaths.isEmpty())
        assertEquals(0, result.pluginCount)
    }

    @Test
    fun parseFailureForOneFileDoesNotAbortOthers() {
        touch("Templates/Good.als")
        touch("Templates/Bad.als")
        val handle = CatalogDb.openInMemory()
        seedTree(handle.catalog)
        val parser =
            UserLibraryPluginScanner.PluginRefParser { path ->
                val rel = ulRoot.relativize(path).toString().replace('\\', '/')
                if (rel == "Templates/Bad.als") throw IllegalStateException("simulated")
                listOf(PluginRef("Serum", PluginFormat.Vst3, null))
            }
        val s = UserLibraryPluginScanner(handle.catalog, parser, FixedClock(now))

        val result = s.scan(treeId, ulRoot)

        // Both relpaths attempted; only the good one produced a plugin row.
        assertEquals(setOf("Templates/Good.als", "Templates/Bad.als"), result.scannedRelpaths.toSet())
        assertEquals(1, result.pluginCount)
    }
}

private class FixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}
