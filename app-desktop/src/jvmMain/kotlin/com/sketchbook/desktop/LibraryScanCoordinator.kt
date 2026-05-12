package com.sketchbook.desktop

import com.sketchbook.catalog.JvmSampleScanner
import com.sketchbook.catalog.JvmScanner
import com.sketchbook.core.AppScope
import com.sketchbook.core.runCatchingCancellable
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.PluginPresenceProbe
import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates library scans for the desktop shell. Observes [SettingsRepository] for newly-added
 * roots, walks each one through [JvmScanner] (projects) or [JvmSampleScanner] (samples), and
 * exposes a UI-facing [progress] [StateFlow] that the scan ribbon and sidebar caption render.
 *
 * Singleton on [AppScope]: lives for the app's lifetime, started once from `Main` (mirroring
 * `startBackgroundPull`). Kicking it from a composable would re-spawn the observe loop on
 * every root recomposition — kept here so `RootContent` stays rendering-only.
 *
 * **Idempotent.** Internal sets track which roots have already been scanned so settings
 * re-emissions (cache-toggle, cloud-credential changes) don't trigger redundant walks; the
 * scanner is `INSERT OR REPLACE` but a re-scan over 1900+ files is wasteful.
 */
@SingleIn(AppScope::class)
@Inject
class LibraryScanCoordinator(
    private val scanner: JvmScanner,
    private val sampleScanner: JvmSampleScanner,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val pluginPresenceProbe: PluginPresenceProbe,
) {
    private val _progress = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val progress: StateFlow<ScanUiState> = _progress.asStateFlow()

    private val scannedProjects = mutableSetOf<String>()
    private val scannedSamples = mutableSetOf<String>()

    private var started = false

    /**
     * Begin observing settings and kicking scans. Idempotent — repeat calls are no-ops, so it's
     * safe to invoke from both Main (app startup) and from the [ScanTrigger] hook fired when
     * onboarding's Finish writes roots. The settings observer auto-picks-up new roots either way.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.observe().collect { settingsState ->
                kickProjectScanIfNeeded(settingsState.libraryRoots)
                kickSampleScansIfNeeded(settingsState.libraryRoots)
            }
        }
    }

    private fun kickProjectScanIfNeeded(roots: List<LibraryRoot>) {
        // One project root at a time at v1.
        val newProjectRoot = roots.firstOrNull { it is LibraryRoot.Projects && it.path !in scannedProjects } ?: return
        val path = (newProjectRoot as LibraryRoot.Projects).path
        scannedProjects += path
        val sampleRoots = roots.filterIsInstance<LibraryRoot.UserSamples>().map { it.path }
        scope.launch {
            runScan(scanner, path, _progress)
            // Sample-corpus walk follows the project scan: fast, no progress UI, no parsing.
            for (sampleRoot in sampleRoots) {
                if (scannedSamples.add(sampleRoot)) {
                    runCatchingCancellable { sampleScanner.scan(sampleRoot) }
                }
            }
            // PR-T: once the parser has populated `project_plugins`, walk the platform-default
            // plugin install directories and flip `is_installed` per (name, type). Best-effort —
            // a probe failure must not invalidate the (succeeded) project scan, so wrap and
            // swallow. The probe's own internal try/catch already swallows missing-dir errors;
            // this outer layer guards against a SQL hiccup (extremely unlikely) so the user still
            // sees their indexed projects.
            runCatchingCancellable { pluginPresenceProbe.probe() }
        }
    }

    private fun kickSampleScansIfNeeded(roots: List<LibraryRoot>) {
        // Pick up sample roots added without a corresponding project root (or that arrive after
        // the project scan already kicked off on a previous emission).
        for (root in roots) {
            if (root is LibraryRoot.UserSamples && root.path !in scannedSamples) {
                scannedSamples += root.path
                scope.launch {
                    runCatchingCancellable { sampleScanner.scan(root.path) }
                }
            }
        }
    }
}
