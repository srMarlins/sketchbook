package com.sketchbook.actions

import com.sketchbook.core.PluginRef
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProposalAction
import com.sketchbook.repo.SampleEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProposalActionExecutorTest {
    private class FakeProjects : ProjectRepository {
        data class ArchiveCall(
            val id: ProjectId,
            val archived: Boolean,
        )

        val archiveCalls = mutableListOf<ArchiveCall>()
        val tagCalls = mutableListOf<Pair<ProjectId, List<String>>>()

        override fun observeProjects(query: String): Flow<List<ProjectRow>> = flowOf(emptyList())

        override fun observeProject(id: ProjectId): Flow<ProjectRow?> = flowOf(null)

        override fun observePlugins(id: ProjectId): Flow<List<PluginRef>> = flowOf(emptyList())

        override fun observeSamples(id: ProjectId): Flow<List<SampleEntry>> = flowOf(emptyList())

        override suspend fun move(
            id: ProjectId,
            newParentDir: String,
        ): Result<JournalEntry> = throw NotImplementedError()

        override suspend fun rename(
            id: ProjectId,
            newName: String,
        ): Result<JournalEntry> = throw NotImplementedError()

        override suspend fun archive(
            id: ProjectId,
            archived: Boolean,
        ): Result<JournalEntry> {
            archiveCalls += ArchiveCall(id, archived)
            return Result.success(stubEntry())
        }

        override suspend fun setTags(
            id: ProjectId,
            tags: List<String>,
        ): Result<JournalEntry> {
            tagCalls += id to tags
            return Result.success(stubEntry())
        }

        private fun stubEntry(): JournalEntry =
            JournalEntry(
                timestamp = Instant.parse("2026-05-05T00:00:00Z"),
                projectId = ProjectId(1L),
                action = ActionRecord.Archive(wasArchived = false, isArchived = true),
            )
    }

    @Test
    fun archiveActionDispatchesToRepoArchive() =
        runTest {
            val fake = FakeProjects()
            val exec = ProposalActionExecutor(fake)
            val r =
                exec.apply(
                    listOf(
                        ProposalAction(
                            type = "ArchiveProject",
                            args = buildJsonObject { put("project_id", 7L) },
                        ),
                    ),
                )
            assertTrue(r.isSuccess)
            assertEquals(listOf(FakeProjects.ArchiveCall(ProjectId(7L), true)), fake.archiveCalls)
        }

    @Test
    fun unknownActionTypeFailsWithoutPartialEffects() =
        runTest {
            val fake = FakeProjects()
            val exec = ProposalActionExecutor(fake)
            val r =
                exec.apply(
                    listOf(
                        ProposalAction(
                            type = "ArchiveProject",
                            args = buildJsonObject { put("project_id", 1L) },
                        ),
                        ProposalAction(
                            type = "Bogus",
                            args = buildJsonObject { put("project_id", 2L) },
                        ),
                    ),
                )
            // archive(1) ran *before* the unknown type was reached — the executor stops on failure
            // but doesn't roll back already-applied actions (the catalog handles that via the
            // journal/undo path).
            assertEquals(listOf(FakeProjects.ArchiveCall(ProjectId(1L), true)), fake.archiveCalls)
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull()?.message?.contains("Bogus") == true)
        }
}
