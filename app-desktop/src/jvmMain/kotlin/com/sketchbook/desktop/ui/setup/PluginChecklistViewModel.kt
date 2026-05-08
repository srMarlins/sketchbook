package com.sketchbook.desktop.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.repo.HostPluginEntry
import com.sketchbook.repo.MachineProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for [PluginChecklistScreen]. Reads from [MachineProfileStore.composeUnion],
 * filters by OS via [SetupNav.filterPending], and exposes three buckets:
 *
 * - [PluginChecklistUiState.pending] — not installed on this OS at first load.
 * - [PluginChecklistUiState.recentlyInstalled] — moved out of pending after a re-probe in
 *   this session. Surfaced as a "just installed" callout so the user sees their action
 *   stick.
 * - [PluginChecklistUiState.alreadyInstalled] — installed on this OS at first load,
 *   collapsed by default at the bottom of the screen.
 *
 * The reprobe call is deliberately a placeholder: the actual `JvmPluginPresenceProbe`
 * invocation lives in app-desktop's plugin-presence wiring. We model the state transition
 * here so the VM tests pin the move-from-pending-to-recentlyInstalled rule without
 * dragging the probe machinery into the test.
 */
/**
 * Manually constructed in the desktop bootstrap wiring once a [MachineProfileStore] is
 * available — same reason as [com.sketchbook.migration.JvmCloudMigrator] / the store
 * itself: cloud handle is per-user, not AppScope.
 */
class PluginChecklistViewModel(
    private val profileStore: MachineProfileStore,
    private val osProvider: OsProvider = OsProvider.System,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PluginChecklistUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-fetch the cloud's per-host union and recompute the buckets. */
    fun refresh() {
        viewModelScope.launch {
            val union = profileStore.composeUnion()
            val os = osProvider.os()
            val pending = SetupNav.filterPending(union.union, os)
            val pendingRows = pending.map { it.toRow() }
            val alreadyInstalledRows =
                union.union
                    .filter { it.installed }
                    .map { it.toRow() }
            _state.value =
                PluginChecklistUiState(
                    pending = pendingRows,
                    recentlyInstalled = emptyList(),
                    alreadyInstalled = alreadyInstalledRows,
                    isReprobing = false,
                )
        }
    }

    /**
     * Re-run the presence probe (caller-supplied side-effect — see [reprobeRunner]). Rows
     * that flipped to installed move from [pending] to [recentlyInstalled]; the rest stay
     * pending. Idempotent: pre-existing alreadyInstalled rows aren't double-counted.
     */
    fun reprobe(reprobeRunner: suspend () -> List<HostPluginEntry>) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isReprobing = true)
            val refreshed = reprobeRunner()
            val os = osProvider.os()
            val installedKeys =
                refreshed
                    .filter { it.installed }
                    .map { it.name to it.format }
                    .toSet()
            val current = _state.value
            val (justInstalled, stillPending) =
                current.pending.partition { (it.name to it.format) in installedKeys }
            _state.value =
                PluginChecklistUiState(
                    pending = SetupNav.filterPending(refreshed, os).map { it.toRow() }.filter { row ->
                        // A row that the union now considers installed shouldn't appear in
                        // pending even if filterPending returned it.
                        (row.name to row.format) !in installedKeys
                    },
                    recentlyInstalled = current.recentlyInstalled + justInstalled,
                    alreadyInstalled =
                        (current.alreadyInstalled + stillPending.filter { (it.name to it.format) in installedKeys })
                            .distinctBy { it.name to it.format },
                    isReprobing = false,
                )
        }
    }

    /**
     * Hides the screen on the next launch when the user clicks "Skip — show in Settings".
     * State stays in memory; the actual persisted "skipped" flag lives elsewhere (the
     * Settings entry is the off-switch per the design doc).
     */
    fun dismiss() {
        _state.value = _state.value.copy(isReprobing = false)
    }

    private fun initialState(): PluginChecklistUiState =
        PluginChecklistUiState(
            pending = emptyList(),
            recentlyInstalled = emptyList(),
            alreadyInstalled = emptyList(),
            isReprobing = false,
        )

    /** Indirection so tests can supply a deterministic OS string instead of relying on the JVM property. */
    fun interface OsProvider {
        fun os(): String

        companion object {
            val System: OsProvider =
                OsProvider {
                    val raw = java.lang.System.getProperty("os.name")?.lowercase().orEmpty()
                    when {
                        raw.contains("mac") || raw.contains("darwin") -> "darwin"
                        raw.contains("win") -> "windows"
                        else -> "linux"
                    }
                }
        }
    }
}

data class PluginChecklistUiState(
    val pending: List<PluginRow>,
    val recentlyInstalled: List<PluginRow>,
    val alreadyInstalled: List<PluginRow>,
    val isReprobing: Boolean,
)

data class PluginRow(
    val name: String,
    val format: String,
    val installed: Boolean,
)

private fun HostPluginEntry.toRow(): PluginRow = PluginRow(name = name, format = format, installed = installed)
