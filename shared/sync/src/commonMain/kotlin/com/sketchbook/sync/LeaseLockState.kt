package com.sketchbook.sync

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
import com.sketchbook.cloud.LeaseRefreshResult
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.core.UserId
import kotlinx.coroutines.CancellationException
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
    private val ownerUserId: UserId = UserId.DEFAULT,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = 15.minutes,
    private val heartbeatInterval: Duration = 5.minutes,
) {
    private val treeId: TrackedTreeId = TrackedTreeId(uuid.value)
    private val kind: TrackedTreeKind = TrackedTreeKind.Project

    private val _state = MutableStateFlow<LockState>(LockState.Idle)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var heartbeatJob: Job? = null

    private companion object {
        /**
         * Clock-skew tolerance for [LeaseLock.isExpired] checks. Two hosts disagreeing by
         * up to this much on wall-clock time will not flap the held/expired UI state. NTP
         * drift on consumer machines can be tens of seconds; 60s is a comfortable bound
         * without leaving a held lease appearing expired prematurely.
         */
        val LEASE_SKEW_TOLERANCE: Duration = kotlin.time.Duration.parse("60s")
    }

    suspend fun acquire(scope: CoroutineScope): LockState {
        val now = clock.now()
        val lock =
            LeaseLock(
                ownerUserId = ownerUserId,
                ownerHostId = hostId,
                ownerHostName = hostName,
                acquiredAt = now,
                expiresAt = now + ttl,
            )
        val outcome =
            when (val result = cloud.acquireLock(treeId, kind, lock)) {
                is LeaseAcquireResult.Acquired -> {
                    heartbeatJob?.cancel()
                    heartbeatJob = scope.launch { heartbeatLoop(result.generation) }
                    LockState.Owned(lock, result.generation)
                }

                is LeaseAcquireResult.Held -> {
                    // Auto-takeover when the held lease has TTL-expired (with skew tolerance).
                    // Without this, an unattended host that died mid-snapshot wedges the
                    // project until a human force-takes; the original holder's lease can't
                    // refresh (they're dead) but the cloud keeps treating it as held until a
                    // peer overwrites via CAS.
                    if (result.held.isExpired(now, LEASE_SKEW_TOLERANCE)) {
                        when (val r = cloud.refreshLock(treeId, kind, lock, result.generation)) {
                            is LeaseRefreshResult.Refreshed -> {
                                heartbeatJob?.cancel()
                                heartbeatJob = scope.launch { heartbeatLoop(r.generation) }
                                LockState.Owned(lock, r.generation)
                            }

                            LeaseRefreshResult.Stale -> {
                                // Race: someone else took the lease between our acquire and
                                // refresh. Surface as held; UI offers force-take.
                                LockState.HeldByOther(result.held, result.generation)
                            }
                        }
                    } else {
                        LockState.HeldByOther(result.held, result.generation)
                    }
                }
            }
        _state.value = outcome
        return outcome
    }

    private suspend fun heartbeatLoop(initialGeneration: Generation) {
        var generation = initialGeneration
        while (true) {
            delay(heartbeatInterval)
            val now = clock.now()
            val refreshed =
                LeaseLock(
                    ownerUserId = ownerUserId,
                    ownerHostId = hostId,
                    ownerHostName = hostName,
                    acquiredAt = (state.value as? LockState.Owned)?.lock?.acquiredAt ?: now,
                    expiresAt = now + ttl,
                )
            when (val r = cloud.refreshLock(treeId, kind, refreshed, generation)) {
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
            // Best-effort release. try/catch (CancellationException) instead of runCatching:
            // releaseLock is suspend, so runCatching would swallow coroutine cancellation.
            try {
                cloud.releaseLock(treeId, kind, current.generation)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // Best-effort: ignore release failures.
            }
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

/**
 * Helper used by the UI: "did this lock expire?". [skew] adds tolerance so two hosts with NTP
 * drift don't flap the UI state — the lease is only considered expired once the local clock
 * is past `expiresAt + skew`.
 */
fun LeaseLock.isExpired(
    now: Instant,
    skew: Duration = Duration.ZERO,
): Boolean = expiresAt + skew <= now
