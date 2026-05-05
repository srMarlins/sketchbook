package com.sketchbook.featuretimeline

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.CoroutineScope
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
import kotlin.time.Duration.Companion.minutes

/**
 * Snapshot history viewer per design §A2. Default view shows `Named` + `Branch` snapshots only,
 * grouped by local-day. Toggling [Intent.ToggleShowAll] reveals every `Auto` save too.
 *
 * Inputs (selected uuid, showAll, pendingRewind) live in `MutableStateFlow`s; the published
 * `state` is a pure `combine(historyFlow, showAll, pendingRewind)`. `historyFlow` itself is a
 * `flatMapLatest` over the selected uuid so the upstream subscription auto-cancels when the
 * caller swaps projects.
 *
 * Rewind confirms via UI then dispatches `ConfirmRewind`, which calls
 * `SnapshotRepository.materializeAt`. Progress is reported via [Effect].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineStateHolder(
    private val snapshots: SnapshotRepository,
    private val scope: CoroutineScope,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {

    private val selectedUuid = MutableStateFlow<ProjectUuid?>(null)
    private val showAll = MutableStateFlow(false)
    private val pendingRewind = MutableStateFlow<SnapshotRev?>(null)

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
    ) { history, all, pending ->
        State(
            uuid = selectedUuid.value,
            history = history,
            showAll = all,
            pendingRewind = pending,
            loading = selectedUuid.value != null && history.isEmpty(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, State())

    fun load(uuid: ProjectUuid) {
        selectedUuid.update { uuid }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.ToggleShowAll -> showAll.update { !it }
            is Intent.RequestRewind -> pendingRewind.update { intent.rev }
            Intent.CancelRewind -> pendingRewind.update { null }
            is Intent.ConfirmRewind -> rewind(intent.rev)
        }
    }

    private fun rewind(rev: SnapshotRev) {
        val uuid = selectedUuid.value ?: return
        scope.launch {
            _effects.tryEmit(Effect.RewindStarted(rev))
            val result = snapshots.materializeAt(uuid, rev)
            pendingRewind.update { null }
            if (result.isSuccess) {
                _effects.tryEmit(Effect.RewindCompleted(rev))
            } else {
                _effects.tryEmit(Effect.RewindFailed(rev, result.exceptionOrNull()?.message ?: "rewind failed"))
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

    data class State(
        val uuid: ProjectUuid? = null,
        val history: List<Snapshot> = emptyList(),
        val showAll: Boolean = false,
        val loading: Boolean = false,
        val pendingRewind: SnapshotRev? = null,
    )

    data class DayGroup(val date: LocalDate, val snapshots: List<Snapshot>)

    sealed interface Intent {
        data object ToggleShowAll : Intent
        data class RequestRewind(val rev: SnapshotRev) : Intent
        data object CancelRewind : Intent
        data class ConfirmRewind(val rev: SnapshotRev) : Intent
    }

    sealed interface Effect {
        data class RewindStarted(val rev: SnapshotRev) : Effect
        data class RewindCompleted(val rev: SnapshotRev) : Effect
        data class RewindFailed(val rev: SnapshotRev, val reason: String) : Effect
    }
}
