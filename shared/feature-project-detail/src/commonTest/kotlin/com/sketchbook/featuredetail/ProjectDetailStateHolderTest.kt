package com.sketchbook.featuredetail

import app.cash.turbine.test
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectDetailStateHolderTest {

    private val now = Instant.parse("2026-05-05T12:00:00Z")
    private val sampleRow = ProjectRow(
        id = ProjectId(7),
        name = "kick",
        path = ProjectPath("Projects/2026/kick/Project.als"),
        tempo = 124.0,
        trackCount = 8,
        lastSavedLiveVersion = "11.3.20",
        updatedAt = now,
        tags = emptyList(),
        colorTag = null,
    )
    private val uuid = ProjectUuid("01H-test")

    private fun snapshot(rev: Long, kind: SnapshotKind = SnapshotKind.Auto) = Snapshot(
        projectUuid = uuid,
        rev = SnapshotRev(rev),
        parentRev = null,
        timestamp = now,
        hostId = "host-a",
        hostName = "DesktopA",
        kind = kind,
        label = null,
        selfContained = false,
        fileCount = 1,
        totalBytes = 100,
    )

    private class FakeProjects(private val row: ProjectRow?) : ProjectRepository {
        override fun observeProjects(query: String): Flow<List<ProjectRow>> = flowOf(emptyList())
        override fun observeProject(id: ProjectId): Flow<ProjectRow?> = flowOf(row)
        override suspend fun move(id: ProjectId, newParentDir: String) = Result.success(stub())
        override suspend fun rename(id: ProjectId, newName: String) = Result.success(stub())
        override suspend fun archive(id: ProjectId, archived: Boolean) = Result.success(stub())
        override suspend fun setTags(id: ProjectId, tags: List<String>) = Result.success(stub())
        private fun stub() = JournalEntry(Instant.parse("2026-05-05T12:00:00Z"), ProjectId(1), ActionRecord.Archive(false, true))
    }

    private class FakeSnapshots(private val flow: MutableStateFlow<List<Snapshot>>) : SnapshotRepository {
        override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> = flow
        override suspend fun recordSnapshot(snapshot: Snapshot, manifestPath: String, manifestHash: String): Result<Unit> = Result.success(Unit)
        override suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun loadPopulatesRowAndHistory() = runTest {
        val projects = FakeProjects(sampleRow)
        val history = MutableStateFlow(listOf(snapshot(1), snapshot(2, SnapshotKind.Named)))
        val snaps = FakeSnapshots(history)
        val holder = ProjectDetailStateHolder(projects, snaps, backgroundScope, projectUuidLookup = { uuid })

        holder.load(ProjectId(7))

        holder.state.test {
            // Drain emissions until both row and history populated.
            var saw = awaitItem()
            while (saw.row == null || saw.history.isEmpty()) saw = awaitItem()
            assertEquals("kick", saw.row?.name)
            assertEquals(2, saw.history.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun selectTabUpdatesState() = runTest {
        val projects = FakeProjects(sampleRow)
        val snaps = FakeSnapshots(MutableStateFlow(emptyList()))
        val holder = ProjectDetailStateHolder(projects, snaps, backgroundScope, projectUuidLookup = { uuid })
        holder.load(ProjectId(7))
        holder.dispatch(ProjectDetailStateHolder.Intent.SelectTab(ProjectDetailStateHolder.Tab.History))
        holder.state.test {
            // Drain until combine forwards the new tab selection.
            while (awaitItem().tab != ProjectDetailStateHolder.Tab.History) { /* keep draining */ }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun openInLiveEmitsLaunchEffectWithProjectPath() = runTest {
        val projects = FakeProjects(sampleRow)
        val snaps = FakeSnapshots(MutableStateFlow(emptyList()))
        val holder = ProjectDetailStateHolder(projects, snaps, backgroundScope, projectUuidLookup = { uuid })
        holder.load(ProjectId(7))

        // Wait for row to populate, then dispatch.
        holder.state.test {
            while (awaitItem().row == null) { /* keep draining */ }
            cancelAndIgnoreRemainingEvents()
        }
        holder.effects.test {
            holder.dispatch(ProjectDetailStateHolder.Intent.OpenInLive)
            val effect = awaitItem()
            assertTrue(effect is ProjectDetailStateHolder.Effect.LaunchLive)
            assertEquals("Projects/2026/kick/Project.als", effect.projectPath)
        }
    }
}
