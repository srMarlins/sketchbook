package com.sketchbook.desktop.ui.setup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.repo.HostPluginEntry
import com.sketchbook.repo.MachineProfileStore
import com.sketchbook.repo.PluginPresenceProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State holder for [PluginChecklistScreen]. Reads from [MachineProfileStore.composeUnion],
 * filters by OS via [SetupNav.formatRunsOn], and exposes three buckets:
 *
 * - [PluginChecklistUiState.pending] — not installed on this OS at first load.
 * - [PluginChecklistUiState.recentlyInstalled] — moved out of pending after a re-probe in
 *   this session. Surfaced as a "just installed" callout so the user sees their action
 *   stick.
 * - [PluginChecklistUiState.alreadyInstalled] — installed on this OS at first load,
 *   collapsed by default at the bottom of the screen. Filtered by OS too — a Mac-only AU
 *   reported installed by the Mac host should not appear in the Windows view.
 *
 * `reprobe()` runs the local presence probe, re-publishes this host's slice, and reloads
 * the union — all dependencies injected so the screen and tests can drive the VM through
 * the same shape.
 *
 * **DI status.** Constructed manually until [com.sketchbook.repo.MachineProfileStore] +
 * [com.sketchbook.cloud.CloudBackend] move into a real `UserScope` graph (tracked in
 * https://github.com/srMarlins/sketchbook/issues/130). Once that lands, this VM picks up
 * `@ContributesIntoMap(UserScope::class) @ViewModelKey @Inject` and a
 * `PluginChecklistRoute` composable does `metroViewModel<>()` per the standard pattern in
 * `docs/architecture/dependency-injection.md` §3.2.
 */
class PluginChecklistViewModel(
    private val profileStore: MachineProfileStore,
    private val presenceProbe: PluginPresenceProbe,
    private val hostInfo: HostSliceContext,
    private val osProvider: OsProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PluginChecklistUiState> = _state.asStateFlow()

    /**
     * Serializes [refresh] / [reprobe] so concurrent triggers (user mashes "Re-check"
     * mid-load) can't race on `_state` and produce a torn view. Both operations end with a
     * single transactional state copy under the lock; per-field flips like `isReprobing`
     * use `_state.update {}` instead of read-then-write so they stay atomic without the
     * mutex.
     */
    private val stateMutex = Mutex()

    init {
        refresh()
    }

    /** OS string used internally for filter + publish, exposed so the route can format the headline. */
    fun osLabelHint(): String = osProvider.os()

    /** Re-fetch the cloud's per-host union and recompute the buckets. */
    fun refresh() {
        viewModelScope.launch {
            stateMutex.withLock {
                runCompose {
                    val union = profileStore.composeUnion()
                    val os = osProvider.os()
                    val pending = SetupNav.filterPending(union.union, os).map { it.toRow() }
                    val alreadyInstalled =
                        union.union
                            .filter { it.installed && SetupNav.formatRunsOn(it.format, os) }
                            .map { it.toRow() }
                    PluginChecklistUiState(
                        pending = pending,
                        recentlyInstalled = emptyList(),
                        alreadyInstalled = alreadyInstalled,
                        isReprobing = false,
                        isInitialLoad = false,
                        loadFailed = null,
                    )
                }
            }
        }
    }

    /**
     * Re-run the local plugin-presence probe, re-publish this host's slice so the cloud view
     * is fresh, then recompute the union. Rows that flipped to installed since the last
     * compose move from [pending] to [recentlyInstalled]; entries that *fell out of*
     * `recentlyInstalled` (e.g. user uninstalled mid-session) get dropped from that bucket
     * so the view doesn't accumulate stale "just installed" rows.
     *
     * `alreadyInstalled` is session-frozen — it's the snapshot at first load, plus the OS
     * filter. New installs land in `recentlyInstalled` for visual feedback, not silently
     * into the bottom bucket.
     */
    fun reprobe() {
        // Optimistic flip *before* the lock so the spinner / disabled button shows up
        // immediately even if a sibling refresh is mid-lock. `_state.update {}` keeps it
        // atomic with respect to other field writes.
        _state.update { it.copy(isReprobing = true, loadFailed = null) }
        viewModelScope.launch {
            stateMutex.withLock {
                runCompose {
                    presenceProbe.probe()
                    val os = osProvider.os()
                    profileStore.publishHostSlice(hostInfo.hostId, hostInfo.hostName, os)
                    val refreshed = profileStore.composeUnion().union
                    val installedKeys =
                        refreshed
                            .filter { it.installed }
                            .map { it.name to it.format }
                            .toSet()
                    val current = _state.value
                    // Anything in current.pending that's now installed becomes a "just
                    // installed" row. Reconcile the existing recentlyInstalled bucket against
                    // the latest `installedKeys` so an entry the user uninstalled mid-session
                    // doesn't linger as "just installed."
                    val justInstalled = current.pending.filter { (it.name to it.format) in installedKeys }
                    val survivingRecently =
                        current.recentlyInstalled.filter { (it.name to it.format) in installedKeys }
                    PluginChecklistUiState(
                        pending = SetupNav.filterPending(refreshed, os).map { it.toRow() },
                        recentlyInstalled = survivingRecently + justInstalled,
                        alreadyInstalled = current.alreadyInstalled,
                        isReprobing = false,
                        isInitialLoad = false,
                        loadFailed = null,
                    )
                }
            }
        }
    }

    /**
     * Run [block] and apply its returned state, or capture a failure into `loadFailed`
     * without losing structured-concurrency cancellation. The function-shaped seam exists so
     * `refresh()` and `reprobe()` share identical error-surfacing without duplicating the
     * try/catch.
     */
    private suspend fun runCompose(block: suspend () -> PluginChecklistUiState) {
        try {
            val next = block()
            _state.value = next
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    isReprobing = false,
                    isInitialLoad = false,
                    loadFailed = t.message ?: "Couldn't load plugin checklist",
                )
            }
        }
    }

    private fun initialState(): PluginChecklistUiState =
        PluginChecklistUiState(
            pending = emptyList(),
            recentlyInstalled = emptyList(),
            alreadyInstalled = emptyList(),
            isReprobing = false,
            isInitialLoad = true,
            loadFailed = null,
        )
}

/**
 * Plugin-checklist UI state. `@Immutable` so Compose can skip recomposition when the
 * reference is unchanged — DI doc §3.1.
 */
@Immutable
data class PluginChecklistUiState(
    val pending: List<PluginRow>,
    val recentlyInstalled: List<PluginRow>,
    val alreadyInstalled: List<PluginRow>,
    val isReprobing: Boolean,
    val isInitialLoad: Boolean,
    val loadFailed: String?,
)

@Immutable
data class PluginRow(
    val name: String,
    val format: String,
    val installed: Boolean,
)

internal fun HostPluginEntry.toRow(): PluginRow = PluginRow(name = name, format = format, installed = installed)

/** Convert a flat union to the row shape the screen consumes. Used by the Settings entry. */
internal fun toScreenRows(entries: List<HostPluginEntry>): List<PluginRow> = entries.map { it.toRow() }
