package com.sketchbook.sync

import com.sketchbook.cloud.LeaseLock
import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LeaseLockStateTest {
    private val uuid = ProjectUuid("01H-test")
    private val now = Instant.parse("2026-05-05T12:00:00Z")

    @Test
    fun acquiresLockWhenFree() =
        runTest {
            val cloud = FakeCloudBackend()
            val lease = LeaseLockState(cloud, uuid, "host-a", "DesktopA", clock = FixedClock(now))
            val state = lease.acquire(backgroundScope)
            assertTrue(state is LockState.Owned, "expected Owned, got $state")
            lease.release()
        }

    @Test
    fun reportsHeldByOtherWhenForeignLock() =
        runTest {
            val cloud = FakeCloudBackend()
            cloud.forceLock(
                uuid,
                LeaseLock(ownerHostId = "host-b", ownerHostName = "MacStudio", acquiredAt = now, expiresAt = now + 15.minutes),
            )
            val lease = LeaseLockState(cloud, uuid, "host-a", "DesktopA", clock = FixedClock(now))
            val state = lease.acquire(backgroundScope)
            assertTrue(state is LockState.HeldByOther, "expected HeldByOther, got $state")
            assertEquals("MacStudio", state.held.ownerHostName)
        }

    @Test
    fun heartbeatTransitionsToLostIfStolen() =
        runTest {
            val cloud = FakeCloudBackend()
            val clock = FixedClock(now)
            val lease =
                LeaseLockState(
                    cloud = cloud,
                    uuid = uuid,
                    hostId = "host-a",
                    hostName = "DesktopA",
                    clock = clock,
                    heartbeatInterval = 1.minutes,
                    ttl = 5.minutes,
                )
            val state = lease.acquire(backgroundScope)
            assertTrue(state is LockState.Owned)

            // Steal the lock by overwriting the underlying entry; the next refresh sees a stale gen.
            cloud.forceLock(
                uuid,
                LeaseLock(ownerHostId = "host-b", ownerHostName = "MacStudio", acquiredAt = now, expiresAt = now + 10.minutes),
            )

            // Advance virtual time so the heartbeat coroutine fires inside backgroundScope.
            testScheduler.advanceTimeBy(70_000)
            testScheduler.runCurrent()

            assertEquals(LockState.Lost, lease.state.value)
        }
}
