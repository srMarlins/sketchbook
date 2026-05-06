package com.sketchbook.desktop

import com.sketchbook.catalog.JvmScanner
import com.sketchbook.catalog.ScanProgress
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Paths

/**
 * UI-facing state model for the catalog scanner. The catalog emits a cold `Flow<ScanProgress>`;
 * the desktop pumps that into a [MutableStateFlow] of this enum so chrome elements (sidebar
 * caption, scan indicator, ActivityBar) can `collectAsState` without re-subscribing the cold
 * stream per recomposition.
 *
 * `Done` lingers in the state for ~3.5s after the scan finishes so the success message is
 * visible — see [runScan].
 */
sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(val total: Int, val done: Int) : ScanUiState
    data class Done(val indexed: Int, val failed: Int) : ScanUiState
    data class Failed(val message: String) : ScanUiState
}

/**
 * Drive a single library-root scan into [out]. Catches both cancellation and unexpected
 * throwables so a partial walk can't strand the UI in `Scanning` forever. Holds `Done` for
 * ~3.5 s before flipping back to `Idle` so the success caption registers.
 */
suspend fun runScan(
    scanner: JvmScanner,
    rootPath: String,
    out: MutableStateFlow<ScanUiState>,
) {
    val root = Paths.get(rootPath)
    var indexed = 0
    var failed = 0
    try {
        scanner.scan(root).collect { p ->
            when (p) {
                is ScanProgress.Started -> {
                    out.value = ScanUiState.Scanning(total = p.total, done = 0)
                }

                is ScanProgress.ProjectIndexed -> {
                    indexed++
                    out.value = ScanUiState.Scanning(total = p.total, done = p.done)
                }

                is ScanProgress.ProjectFailed -> {
                    failed++
                    out.value = ScanUiState.Scanning(total = p.total, done = p.done)
                }

                is ScanProgress.Finished -> {
                    out.value = ScanUiState.Done(indexed = indexed, failed = failed)
                }
            }
        }
    } catch (t: Throwable) {
        out.value = ScanUiState.Failed(t.message ?: t::class.simpleName ?: "scan failed")
        return
    }
    kotlinx.coroutines.delay(3500)
    if (out.value is ScanUiState.Done) out.value = ScanUiState.Idle
}
