package com.sketchbook.repo

/**
 * Walks the user's installed-plugin directories and flips `project_plugins.is_installed` for every
 * distinct (plugin_name, plugin_type) pair present in the catalog. Drives the Home coverage chip
 * ("N plugins missing affecting M projects") and — once PR-CC plugs in — the `pluginInstalled`
 * library-health signal.
 *
 * Best-effort. Missing directories, permission errors, and I/O failures are swallowed per dir;
 * a single bad plugin folder must not abort the whole probe (a user might have only the VST3
 * folder populated; the VST2 / Components paths legitimately not exist on their machine).
 *
 * Idempotent. Re-running the probe over the same disk produces no net change — each pair is
 * UPDATE'd to the same flag value rather than INSERT'd, so there's nothing to double-count.
 *
 * Common interface so [com.sketchbook.repo.impl.SqlProjectRepository] / coordinators can depend
 * on the probe without dragging the JVM `Files`/`Path` types across the commonMain seam. The JVM
 * implementation lives in `:shared:sync-io/jvmMain` and is bound via
 * `@Inject @ContributesBinding(AppScope::class)`.
 */
interface PluginPresenceProbe {
    /**
     * Walk all configured plugin directories, normalize installed plugin filenames, and update
     * `project_plugins.is_installed` for every distinct (plugin_name, plugin_type) in the catalog.
     * Returns the count of pairs marked installed vs missing for telemetry / log lines. Never
     * throws; on systemic failure both counts come back zero.
     */
    suspend fun probe(): ProbeResult

    data class ProbeResult(
        val installedCount: Int,
        val missingCount: Int,
    ) {
        companion object {
            val EMPTY = ProbeResult(installedCount = 0, missingCount = 0)
        }
    }
}
