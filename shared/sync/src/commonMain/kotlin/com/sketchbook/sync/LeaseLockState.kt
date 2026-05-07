package com.sketchbook.sync

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
import com.sketchbook.cloud.LeaseRefreshResult
import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * State machine for the lease lock per design §5. Wraps [CloudBackend.acquireLock] /
 * [refreshLock] / [releaseLock] in a [StateFlow] the UI subscribes to.
 *
 * Heartbeats on a coroutine scope provided by the caller; cancels cleanly on [release].
 */
class LeaseLockState(
    private val cloud: CloudBackend,
    private val uuid: ProjectUuid,
    private val hostId: String,
    private val hostName: String,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = 15.minutes,
    private val heartbeatInterval: Duration = 5.minutes,
) {
    private val _state = MutableStateFlow<LockState>(LockState.Idle)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var heartbeatJob: Job? = null

    suspend fun acquire(scope: CoroutineScope): LockState {
        val now = clock.now()
        val lock =
            LeaseLock(
                ownerHostId = hostId,
                ownerHostName = hostName,
                acquiredAt = now,
                expiresAt = now + ttl,
            )
        val outcome =
            when (val result = cloud.acquireLock(uuid, lock)) {
                is LeaseAcquireResult.Acquired -> {
                    heartbeatJob?.cancel()
                    heartbeatJob = scope.launch { heartbeatLoop(result.generation, lock.heartbeatSeq) }
                    LockState.Owned(lock, result.generation)
                }

                is LeaseAcquireResult.Held -> {
                    LockState.HeldByOther(result.held, result.generation)
                }
            }
        _state.value = outcome
        return outcome
    }

    private suspend fun heartbeatLoop(
        initialGeneration: Generation,
        initialSeq: Long,
    ) {
        var generation = initialGeneration
        var seq = initialSeq
        while (true) {
            delay(heartbeatInterval)
            val now = clock.now()
            seq += 1
            val refreshed =
                LeaseLock(
                    ownerHostId = hostId,
                    ownerHostName = hostName,
                    acquiredAt = (state.value as? LockState.Owned)?.lock?.acquiredAt ?: now,
                    expiresAt = now + ttl,
                    heartbeatSeq = seq,
                )
            when (val r = cloud.refreshLock(uuid, refreshed, generation)) {
                is LeaseRefreshResult.Refreshed -> {
                    generation = r.generation
                    _state.value = LockState.Owned(refreshed, generation)
                }

                LeaseRefreshResult.Stale -> {
                    _state.value = LockState.Lost
                    return
                }
            }
        }
    }

    suspend fun release() {
        val current = state.value
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (current is LockState.Owned) {
            runCatching { cloud.releaseLock(uuid, current.generation) }
        }
        _state.value = LockState.Idle
    }
}

sealed interface LockState {
    data object Idle : LockState

    data class Owned(
        val lock: LeaseLock,
        val generation: Generation,
    ) : LockState

    data class HeldByOther(
        val held: LeaseLock,
        val generation: Generation,
    ) : LockState

    /** Heartbeat detected someone took the lock from us. */
    data object Lost : LockState
}

/** Helper used by the UI: "did this lock expire?" */
fun LeaseLock.isExpired(now: Instant): Boolean = expiresAt <= now
