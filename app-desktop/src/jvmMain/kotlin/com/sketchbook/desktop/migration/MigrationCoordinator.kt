package com.sketchbook.desktop.migration

import com.sketchbook.migration.CloudMigrator
import com.sketchbook.migration.MigrationProgress
import com.sketchbook.migration.MigrationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the mandatory cloud-storage migration on first launch. Construction is post-auth:
 * the desktop graph hands a [CloudMigrator] (built around the live [com.sketchbook.cloud.CloudBackend]
 * once OAuth has resolved) into this coordinator, which is responsible for:
 *
 * 1. Calling [CloudMigrator.status] to decide whether the dialog needs to appear.
 * 2. Surfacing [MigrationStatus.Pending] to the UI as a [State.Pending] so the dialog can render.
 * 3. Running [CloudMigrator.migrate] when the user clicks "Run migration", forwarding
 *    progress events into [State.Running].
 * 4. Emitting [State.Quit] when the user closes the dialog without confirming — the desktop
 *    main loop reads this and shuts the app down (no degraded-mode path per the design doc).
 *
 * Tests construct the coordinator with a fake migrator and drive the state machine directly.
 */
class MigrationCoordinator(
    private val migrator: CloudMigrator,
) {
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Probe the bucket. Resolves to `false` (no-op) when the migration has already run or no
     * legacy paths exist; resolves to `true` (dialog must show) when work is pending.
     */
    suspend fun probe(): Boolean {
        val s = migrator.status()
        return when (s) {
            MigrationStatus.UpToDate -> {
                _state.value = State.UpToDate
                false
            }

            is MigrationStatus.Pending -> {
                _state.value = State.Pending(s)
                true
            }
        }
    }

    /** User confirmed in the dialog. Streams progress into [state]. */
    fun events(): Flow<MigrationProgress> = migrator.migrate()

    /** Caller (the dialog viewmodel) updates the state machine as events arrive. */
    fun onEvent(event: MigrationProgress) {
        val current = _state.value
        _state.value =
            when (event) {
                is MigrationProgress.Done -> State.Done
                is MigrationProgress.Failed -> State.Failed(event.reason)
                else -> if (current is State.Running) current.copy(latest = event) else State.Running(latest = event)
            }
    }

    /**
     * User closed the dialog without running. Per the design doc this means the app must
     * quit — no degraded-mode path. Tests assert this transition; production reads
     * [State.Quit] in the main loop and calls `exitApplication()`.
     */
    fun onUserDismissed() {
        _state.value = State.Quit
    }

    sealed interface State {
        data object Idle : State

        data object UpToDate : State

        data class Pending(
            val status: MigrationStatus.Pending,
        ) : State

        data class Running(
            val latest: MigrationProgress,
        ) : State

        data object Done : State

        data class Failed(
            val reason: String,
        ) : State

        /** User closed the dialog. The main loop should exit the app. */
        data object Quit : State
    }
}
