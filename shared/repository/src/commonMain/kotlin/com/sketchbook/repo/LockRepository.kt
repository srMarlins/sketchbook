package com.sketchbook.repo

import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Per-project lease-lock status. Sources from `LeaseLockState` in the sync module + the latest
 * `sync_state.lock_owner` / `sync_state.lock_expires` columns. The UI uses this to render the
 * lock badge on the detail pane and decide whether to enable the "force-take" action.
 */
interface LockRepository {

    /**
     * Observe the lock status for a single project. Emits the current snapshot immediately and
     * a new value whenever the underlying lock-state machine or `sync_state` row changes.
     */
    fun observe(uuid: ProjectUuid): Flow<LockStatus>

    /**
     * Force-take a held lock. Only valid when the current status is [LockStatus.Stale] or — by
     * user override — [LockStatus.HeldByOther]. The impl breaks the existing lock by writing a
     * new lock object with `expectedHead = currentGeneration` and our host as owner.
     */
    suspend fun forceTake(uuid: ProjectUuid): Result<Unit>
}

/**
 * What the UI needs to render the lock badge. Mirrors `LockState` in the sync module but with
 * the added [Stale] case the UI cares about: an "Other" lock whose `expiresAt` is in the past
 * is recoverable without a force-take confirmation dialog (just a regular acquire).
 */
sealed interface LockStatus {
    data object Free : LockStatus
    data class Ours(val acquiredAt: Instant, val expiresAt: Instant) : LockStatus
    data class HeldByOther(val ownerHostName: String, val acquiredAt: Instant, val expiresAt: Instant) : LockStatus
    data class Stale(val ownerHostName: String, val expiresAt: Instant) : LockStatus
}
