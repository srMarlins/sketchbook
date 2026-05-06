package com.sketchbook.featuretimeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.MaterializationProgress
import com.sketchbook.repo.SnapshotRepository
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Snapshot history viewer per design §A2. Default view shows `Named` + `Branch` snapshots only,
 * grouped by local-day. Toggling [Intent.ToggleShowAll] reveals every `Auto` save too.
 *
 * `viewModelScope` cancels on `NavEntry` pop — leaving the timeline stops the history
 * subscription. Long-running rewinds also cancel on pop, which matches the user's intent (they
 * navigated away while a rewind was in progress).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class TimelineViewModel(
    private val snapshots: SnapshotRepository,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val selectedUuid = MutableStateFlow<ProjectUuid?>(null)
    private val showAll = MutableStateFlow(false)
    private val pendingRewind = MutableStateFlow<SnapshotRev?>(null)
    private val rewindProgress = MutableStateFlow<MaterializationProgress?>(null)

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val historyFlow: Flow<List<Snapshot>> = selectedUuid.flatMapLatest { uuid ->
        if (uuid == null) flowOf(emptyList()) else snapshots.observeHistory(uuid)
    }

    val state: StateFlow<State> = combine(
        historyFlow,
        showAll,
        pendingRewind,
        rewindProgress,
    ) { history, all, pending, progress ->
        State(
            uuid = selectedUuid.value,
            history = history,
            showAll = all,
            pendingRewind = pending,
            loading = selectedUuid.value != null && history.isEmpty(),
            rewindProgress = progress,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    fun load(uuid: ProjectUuid) {
        selectedUuid.update { uuid }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.ToggleShowAll -> showAll.update { !it }
            is Intent.RequestRewind -> pendingRewind.update { intent.rev }
            Intent.CancelRewind -> pendingRewind.update { null }
            is Intent.ConfirmRewind -> rewind(intent.rev)
            is Intent.RelabelSnapshot -> relabel(intent.rev, intent.newLabel)
        }
    }

    private fun relabel(rev: SnapshotRev, newLabel: String?) {
        val uuid = selectedUuid.value ?: return
        viewModelScope.launch {
            // Trim before passing through; treat blank-after-trim as a clear gesture so
            // accidental whitespace doesn't masquerade as a label. We intentionally do NOT
            // round-trip the result back into local state — the SQL update flows through
            // observeHistory so the row re-renders with the new label automatically.
            val cleaned = newLabel?.trim().takeUnless { it.isNullOrEmpty() }
            val r = snapshots.setSnapshotLabel(uuid, rev, cleaned)
            if (r.isFailure) {
                _effects.tryEmit(
                    Effect.RelabelFailed(
                        rev = rev,
                        reason = r.exceptionOrNull()?.message ?: "label update failed",
                    ),
                )
            }
        }
    }

    private fun rewind(rev: SnapshotRev) {
        val uuid = selectedUuid.value ?: return
        viewModelScope.launch {
            _effects.tryEmit(Effect.RewindStarted(rev))
            snapshots.materializeAtWithProgress(uuid, rev).collect { progress ->
                rewindProgress.update { progress }
                when (progress) {
                    is MaterializationProgress.Done -> {
                        _effects.tryEmit(Effect.RewindCompleted(rev))
                        pendingRewind.update { null }
                        rewindProgress.update { null }
                    }

                    is MaterializationProgress.Failed -> {
                        _effects.tryEmit(Effect.RewindFailed(rev, progress.reason))
                        pendingRewind.update { null }
                        rewindProgress.update { null }
                    }

                    else -> Unit
                }
            }
        }
    }

    /** Default-filtered, day-grouped snapshots, newest day first; within a day newest rev first. */
    fun visibleGroups(state: State = this.state.value): List<DayGroup> {
        val visible = state.history
            .filter { state.showAll || it.kind != SnapshotKind.Auto }
            .sortedByDescending { it.rev.value }
        return visible.groupBy { it.timestamp.toLocalDateTime(zone).date }
            .toSortedMap(compareByDescending { it })
            .map { (date, snaps) -> DayGroup(date, snaps) }
    }

    @Immutable
    data class State(
        val uuid: ProjectUuid? = null,
        val history: List<Snapshot> = emptyList(),
        val showAll: Boolean = false,
        val loading: Boolean = false,
        val pendingRewind: SnapshotRev? = null,
        val rewindProgress: MaterializationProgress? = null,
    )

    data class DayGroup(val date: LocalDate, val snapshots: List<Snapshot>)

    sealed interface Intent {
        data object ToggleShowAll : Intent
        data class RequestRewind(val rev: SnapshotRev) : Intent
        data object CancelRewind : Intent
        data class ConfirmRewind(val rev: SnapshotRev) : Intent

        /** PR-Z Z2: edit a snapshot's label inline. `null` (or blank-after-trim) clears it. */
        data class RelabelSnapshot(val rev: SnapshotRev, val newLabel: String?) : Intent
    }

    sealed interface Effect {
        data class RewindStarted(val rev: SnapshotRev) : Effect
        data class RewindCompleted(val rev: SnapshotRev) : Effect
        data class RewindFailed(val rev: SnapshotRev, val reason: String) : Effect
        data class RelabelFailed(val rev: SnapshotRev, val reason: String) : Effect
    }
}
