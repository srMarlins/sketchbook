package com.sketchbook.syncio

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmPluginPresenceProbeTest {

    @Test
    fun normalizesPluginNamesCorrectly() {
        // Pure-function pin. The probe collapses plugin display names to a comparable token before
        // matching against the installed-set. Live writes the plugin name with a parenthetical
        // version suffix (`Pro-Q 3 (3.4.0)`) and a trailing track-version digit (`Serum 1.35`),
        // which we strip so the match isn't broken by version churn.
        assertEquals("serum", normalizePluginNameForTest("Serum 1.35"))
        assertEquals("proq", normalizePluginNameForTest("Pro-Q 3 (3.4.0)"))
        assertEquals("fabfilterproq", normalizePluginNameForTest("FabFilter Pro-Q 3"))
        assertEquals("", normalizePluginNameForTest(""))
        // Spaces and punctuation collapse but alphanumerics survive.
        assertEquals("oblivion", normalizePluginNameForTest("Oblivion"))
    }

    @Test
    fun marksInstalledWhenFilesystemHasMatchingPluginFile() = runTest {
        val (catalog, driver, pluginDir, cleanup) = setupCatalogWithPlugin(
            pluginName = "Serum",
            pluginType = "vst3",
        )
        try {
            // Seed a matching .vst3 file in the dir we hand to the probe.
            Files.write(pluginDir.resolve("Serum.vst3"), ByteArray(8))
            val probe = JvmPluginPresenceProbe.forTest(
                catalog = catalog,
                installedDirs = listOf(pluginDir),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val result = probe.probe()

            assertEquals(1, result.installedCount)
            assertEquals(0, result.missingCount)
            val row = catalog.catalogQueries.selectAllDistinctPlugins().executeAsList().single()
            assertTrue(isInstalled(driver, row.plugin_name, row.plugin_type))
        } finally {
            cleanup()
        }
    }

    @Test
    fun marksMissingWhenFilesystemDoesNotContainPlugin() = runTest {
        val (catalog, driver, pluginDir, cleanup) = setupCatalogWithPlugin(
            pluginName = "GhostPlugin",
            pluginType = "vst3",
        )
        try {
            // Empty dir — no matching file. Probe should flip is_installed to 0.
            val probe = JvmPluginPresenceProbe.forTest(
                catalog = catalog,
                installedDirs = listOf(pluginDir),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val result = probe.probe()

            assertEquals(0, result.installedCount)
            assertEquals(1, result.missingCount)
            assertTrue(!isInstalled(driver, "GhostPlugin", "vst3"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun gracefullySkipsMissingPluginDirectories() = runTest {
        val (catalog, _, _, cleanup) = setupCatalogWithPlugin(
            pluginName = "Serum",
            pluginType = "vst3",
        )
        try {
            // /nonexistent — must not throw, must not crash. With no installed-set built, the row
            // gets marked missing (we have no evidence of installation).
            val probe = JvmPluginPresenceProbe.forTest(
                catalog = catalog,
                installedDirs = listOf(Path.of("/this/path/does/not/exist/plugins")),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val result = probe.probe()

            // Best-effort: walk failures don't abort. Outcome here is missing because there's
            // nothing to match against.
            assertEquals(0, result.installedCount)
            assertEquals(1, result.missingCount)
        } finally {
            cleanup()
        }
    }

    @Test
    fun probeIsIdempotent() = runTest {
        val (catalog, driver, pluginDir, cleanup) = setupCatalogWithPlugin(
            pluginName = "Serum",
            pluginType = "vst3",
        )
        try {
            Files.write(pluginDir.resolve("Serum.vst3"), ByteArray(8))
            val probe = JvmPluginPresenceProbe.forTest(
                catalog = catalog,
                installedDirs = listOf(pluginDir),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val a = probe.probe()
            val b = probe.probe()

            assertEquals(a.installedCount, b.installedCount)
            assertEquals(a.missingCount, b.missingCount)
            assertTrue(isInstalled(driver, "Serum", "vst3"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun matchesByPrefixHeuristic() = runTest {
        // The catalog has "FabFilter Pro-Q 3" — installed file is "FabFilter Pro-Q 3.vst3". After
        // normalization the catalog token is "fabfilterproq" and the file token is "fabfilterproq",
        // both trimmed of trailing digits. Either-direction prefix match wins.
        val (catalog, _, pluginDir, cleanup) = setupCatalogWithPlugin(
            pluginName = "FabFilter Pro-Q 3",
            pluginType = "vst3",
        )
        try {
            Files.write(pluginDir.resolve("FabFilter Pro-Q 3.vst3"), ByteArray(8))
            val probe = JvmPluginPresenceProbe.forTest(
                catalog = catalog,
                installedDirs = listOf(pluginDir),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val result = probe.probe()

            assertEquals(1, result.installedCount)
            assertEquals(0, result.missingCount)
        } finally {
            cleanup()
        }
    }

    // -- helpers ---------------------------------------------------------------------------------

    /** Reflective hook into the probe's name normalizer so we can pin behavior without exposing
     *  it on the public surface. The companion's `normalizeForTest` JVM method is private to the
     *  module but visible to test source set via package-internal `internal`.  */
    private fun normalizePluginNameForTest(name: String): String = JvmPluginPresenceProbe.normalizeForTest(name)

    private fun setupCatalogWithPlugin(
        pluginName: String,
        pluginType: String,
    ): TestEnv {
        val handle = CatalogDb.openInMemory()
        val pluginDir = createTempDirectory("plugin-probe-test-")
        // Seed a project + a single plugin row referencing the catalog name we want to look up.
        handle.catalog.catalogQueries.insertOrReplaceProject(
            path = "/lib/song.als",
            name = "song",
            parent_dir = "/lib",
            tempo = 120.0,
            time_sig_num = 4,
            time_sig_den = 4,
            key = null,
            track_count = 1,
            audio_tracks = 1,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = "12.0.0",
            last_modified = 0.0,
            last_scanned = 0.0,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = 0L,
        )
        val projectId = handle.catalog.catalogQueries.selectProjectIdByPath("/lib/song.als").executeAsOne()
        handle.catalog.catalogQueries.insertProjectPlugin(
            project_id = projectId,
            plugin_name = pluginName,
            plugin_type = pluginType,
            track_name = "Track 1",
        )
        return TestEnv(
            catalog = handle.catalog,
            driver = handle.driver,
            pluginDir = pluginDir,
            cleanup = {
                runCatching { pluginDir.toFile().deleteRecursively() }
                runCatching { handle.driver.close() }
                Unit
            },
        )
    }

    private data class TestEnv(
        val catalog: Catalog,
        val driver: app.cash.sqldelight.db.SqlDriver,
        val pluginDir: Path,
        val cleanup: () -> Unit,
    )

    private fun isInstalled(driver: app.cash.sqldelight.db.SqlDriver, name: String, type: String): Boolean {
        // selectAllDistinctPlugins (T1) returns the (name, type) pair but not the flag; the
        // missing-coverage view that filters on `is_installed = 0` belongs to T2. To keep this
        // test in T1's world, read the column directly via the driver.
        var installedFlag = 0L
        driver.executeQuery(
            identifier = null,
            sql = "SELECT is_installed FROM project_plugins WHERE plugin_name = ? AND plugin_type = ? LIMIT 1",
            mapper = { c ->
                if (c.next().value) installedFlag = c.getLong(0) ?: 0L
                app.cash.sqldelight.db.QueryResult.Unit
            },
            parameters = 2,
        ) {
            bindString(0, name)
            bindString(1, type)
        }
        return installedFlag == 1L
    }
}
