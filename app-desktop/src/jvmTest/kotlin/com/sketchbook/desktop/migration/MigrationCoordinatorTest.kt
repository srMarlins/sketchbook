package com.sketchbook.desktop.migration

import com.sketchbook.migration.CloudMigrator
import com.sketchbook.migration.MigrationProgress
import com.sketchbook.migration.MigrationReport
import com.sketchbook.migration.MigrationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationCoordinatorTest {
    @Test
    fun probeReturnsFalseAndMovesToUpToDateWhenStatusUpToDate() =
        runTest {
            val coordinator = MigrationCoordinator(StubMigrator(MigrationStatus.UpToDate))
            assertEquals(false, coordinator.probe())
            assertEquals(MigrationCoordinator.State.UpToDate, coordinator.state.value)
        }

    @Test
    fun probeReturnsTrueAndMovesToPendingWhenStatusPending() =
        runTest {
            val pending = MigrationStatus.Pending(MigrationReport(2, 5, true))
            val coordinator = MigrationCoordinator(StubMigrator(pending))
            assertEquals(true, coordinator.probe())
            val state = coordinator.state.value
            assertTrue(state is MigrationCoordinator.State.Pending)
            assertEquals(pending, state.status)
        }

    @Test
    fun migrateProgressEventsForwardToRunningStateThenDone() =
        runTest {
            val migrator =
                StubMigrator(
                    MigrationStatus.UpToDate,
                    events =
                        listOf(
                            MigrationProgress.Probing,
                            MigrationProgress.Relocating(0, 1),
                            MigrationProgress.Relocating(1, 1),
                            MigrationProgress.BuildingRegistry,
                            MigrationProgress.Done(MigrationReport(1, 1, false), emptyList()),
                        ),
                )
            val coordinator = MigrationCoordinator(migrator)

            val events = coordinator.events().toList()
            for (e in events) coordinator.onEvent(e)

            assertEquals(MigrationCoordinator.State.Done, coordinator.state.value)
        }

    @Test
    fun failedEventTransitionsToFailedState() =
        runTest {
            val coordinator =
                MigrationCoordinator(
                    StubMigrator(
                        MigrationStatus.UpToDate,
                        events = listOf(MigrationProgress.Failed("boom")),
                    ),
                )
            for (e in coordinator.events().toList()) coordinator.onEvent(e)
            assertEquals(MigrationCoordinator.State.Failed("boom"), coordinator.state.value)
        }

    @Test
    fun userDismissTransitionsToQuit() {
        val coordinator =
            MigrationCoordinator(StubMigrator(MigrationStatus.Pending(MigrationReport(1, 1, true))))
        coordinator.onUserDismissed()
        assertEquals(MigrationCoordinator.State.Quit, coordinator.state.value)
    }
}

/**
 * Hand-rolled fake — both [com.sketchbook.cloud.CloudBackend] and the live migrator are
 * heavyweight to spin up in a Compose-only test, and the coordinator's contract is purely
 * about the state machine the test drives directly.
 */
private class StubMigrator(
    private val status: MigrationStatus,
    private val events: List<MigrationProgress> = emptyList(),
) : CloudMigrator {
    override suspend fun status(): MigrationStatus = status

    override fun migrate(): Flow<MigrationProgress> = flowOf(*events.toTypedArray())
}
