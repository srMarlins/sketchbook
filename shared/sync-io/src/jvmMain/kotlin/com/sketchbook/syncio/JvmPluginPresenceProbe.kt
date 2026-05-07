package com.sketchbook.syncio

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.AppScope
import com.sketchbook.repo.PluginPresenceProbe
import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Filesystem-walking [PluginPresenceProbe]. Resolves a list of plugin directories (from settings,
 * with OS-default fallback), shallow-walks each one for `vst3 / vst / dll / component` files,
 * normalizes filenames into a comparable token set, and flips `project_plugins.is_installed` for
 * every (name, type) pair in the catalog by either-direction prefix match against that set.
 *
 * **Why prefix-match either way.** "FabFilter Pro-Q 3" written by Live vs `FabFilter Pro-Q 3.vst3`
 * on disk both normalize to `fabfilterproq` (trailing-digit strip), but `Serum 1.35` (Live) →
 * `serum` while `Serum (x64).vst3` (file) → `serumx64`. Either token starting with the other is
 * a positive match. False-positive (a row gets flagged installed when the file is actually a
 * different plugin with a coincidentally-shared prefix) is preferred over false-negative — the
 * Home chip exists to *find* missing plugins; under-flagging is worse than over-flagging.
 *
 * **Why `@SingleIn(AppScope::class)`.** The probe walks the filesystem and writes to the catalog
 * inside a transaction; we don't want two of them stomping each other on a settings emit. Single
 * instance, called once per scan completion from [com.sketchbook.desktop.LibraryScanCoordinator].
 *
 * **Plugin directories.** Resolved per-`probe()` call from `SettingsRepository.observe().first()`.
 * If the user has configured custom plugin folders (onboarding step or Settings screen), those are
 * used verbatim — *no* OS-default fallback when the configured list is non-empty, because a
 * configured-but-all-malformed list silently falling back would hide a real misconfiguration. An
 * intentionally empty `pluginFolders` falls back to [defaultInstalledDirs] so first-run users with
 * standard installs still get accurate presence flags.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class JvmPluginPresenceProbe(
    private val catalog: Catalog,
    private val settings: SettingsRepository,
) : PluginPresenceProbe {
    // Tests construct via [forTest] to swap the dispatcher.
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    override suspend fun probe(): PluginPresenceProbe.ProbeResult =
        withContext(ioDispatcher) {
            val configuredRaw = settings.observe().first().pluginFolders
            val configured = configuredRaw.mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
            // Only an *intentionally* empty configured list falls back to OS defaults. A non-empty
            // list with every entry malformed produces an empty installedDirs (plugins flagged
            // missing) — which is the right signal for the user to fix their settings, not a quiet
            // re-route to defaults that masks the misconfiguration.
            val installedDirs = if (configuredRaw.isEmpty()) defaultInstalledDirs() else configured
            val installedTokens = collectInstalledTokens(installedDirs)
            val pairs = catalog.catalogQueries.selectAllDistinctPlugins().executeAsList()
            if (pairs.isEmpty()) return@withContext PluginPresenceProbe.ProbeResult.EMPTY

            var installed = 0
            var missing = 0
            catalog.transaction {
                for (pair in pairs) {
                    val n = normalize(pair.plugin_name)
                    val present = isInstalledFor(n, installedTokens)
                    catalog.catalogQueries.markPluginsInstalledByNameAndType(
                        is_installed = if (present) 1L else 0L,
                        plugin_name = pair.plugin_name,
                        plugin_type = pair.plugin_type,
                    )
                    if (present) installed++ else missing++
                }
            }
            PluginPresenceProbe.ProbeResult(installedCount = installed, missingCount = missing)
        }

    private fun collectInstalledTokens(dirs: List<Path>): Set<String> {
        val tokens = mutableSetOf<String>()
        for (dir in dirs) {
            runCatching {
                if (!Files.isDirectory(dir)) return@runCatching
                // Depth 2 covers both flat layouts (`Common Files/VST3/Serum.vst3`) and the
                // bundle-as-folder pattern (`/Library/Audio/Plug-Ins/Components/Massive.component`)
                // without descending into bundle internals. Walking the whole tree would be wasted
                // I/O and a recipe for permission denials inside individual bundles.
                Files.walk(dir, 2).use { stream ->
                    stream
                        .asSequence()
                        .filter { it != dir }
                        .filter { p ->
                            val ext = p.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                            ext in PLUGIN_EXTS && (p.isRegularFile() || isPluginBundle(p))
                        }.forEach { p ->
                            val base = p.name.substringBeforeLast('.')
                            val n = normalize(base)
                            if (n.isNotEmpty()) tokens += n
                        }
                }
            }
        }
        return tokens
    }

    private fun isPluginBundle(path: Path): Boolean {
        // macOS .component / .vst3 / .vst are directories with a bundle layout, not files. They
        // still belong in the installed-set so flag them in by ext even though they aren't regular
        // files.
        val ext = path.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return Files.isDirectory(path) && ext in BUNDLE_EXTS
    }

    companion object {
        private val PLUGIN_EXTS = setOf("vst3", "vst", "dll", "component")
        private val BUNDLE_EXTS = setOf("vst3", "vst", "component")

        // Pre-compiled — `normalize` runs once per catalog plugin row, hot path during a probe.
        private val PARENS_REGEX = Regex("\\s*\\([^)]*\\)\\s*")
        private val NON_ALPHANUM_REGEX = Regex("[^a-z0-9]")
        private val TRAILING_DIGITS_REGEX = Regex("\\d+$")

        /**
         * Test seam taking an explicit [SettingsRepository]. Lets tests assert the probe's read of
         * `pluginFolders` and the empty-list fallback path through the production `probe()` flow.
         */
        internal fun forTest(
            catalog: Catalog,
            settings: SettingsRepository,
            ioDispatcher: CoroutineDispatcher,
        ): JvmPluginPresenceProbe =
            JvmPluginPresenceProbe(catalog, settings).also {
                it.ioDispatcher = ioDispatcher
            }

        // Visible for tests so [JvmPluginPresenceProbeTest] can pin the normalizer's behavior
        // without exercising the full probe pipeline.
        internal fun normalizeForTest(name: String): String = normalize(name)

        private fun normalize(name: String): String =
            name
                .lowercase()
                .replace(PARENS_REGEX, "") // strip "(3.4.0)" etc.
                .replace(NON_ALPHANUM_REGEX, "")
                .replace(TRAILING_DIGITS_REGEX, "") // trailing version digits

        private fun isInstalledFor(
            catalogToken: String,
            installedSet: Set<String>,
        ): Boolean {
            if (catalogToken.isEmpty()) return true // unknown — assume installed
            return installedSet.any { it.startsWith(catalogToken) || catalogToken.startsWith(it) }
        }

        private fun defaultInstalledDirs(): List<Path> {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.contains("win") -> {
                    // %CommonProgramFiles% defaults to C:\Program Files\Common Files but honor
                    // the env var so machines with relocated installs Just Work.
                    val cpf =
                        System.getenv("CommonProgramFiles")
                            ?: "C:\\Program Files\\Common Files"
                    listOf(
                        Paths.get(cpf, "VST3"),
                        Paths.get(cpf, "VST2"),
                        Paths.get(cpf, "Steinberg", "VstPlugins"),
                    )
                }

                os.contains("mac") -> {
                    listOf(
                        Paths.get("/Library/Audio/Plug-Ins/VST3"),
                        Paths.get("/Library/Audio/Plug-Ins/VST"),
                        Paths.get("/Library/Audio/Plug-Ins/Components"),
                    )
                }

                // Linux producers overwhelmingly use Wine + Windows VSTs; we'd need to walk the
                // wineprefix to find plugin DLLs and that's a separate feature. Skip for V1 — the
                // probe still runs but with an empty installed-set, marking everything missing.
                // TODO: surface a "this isn't a supported platform yet" signal in the chip.
                else -> {
                    emptyList()
                }
            }
        }
    }
}
