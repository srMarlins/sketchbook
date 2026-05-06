package com.sketchbook.featureneedsattention

import app.cash.turbine.test
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.RepairFindings
import com.sketchbook.repo.RepairRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeedsAttentionStateHolderTest {

    private val macFinding = MacImportFinding(
        projectId = ProjectId(1),
        path = "Projects/2026/old-mac-import/Project.als",
        name = "old-mac-import",
        parentDir = "Projects/2026",
        macPathsCount = 12,
        projectInfoMissing = true,
    )
    private val missingFinding = MissingSampleFinding(
        projectId = ProjectId(2),
        projectPath = "Projects/2026/needs-relink/Project.als",
        projectName = "needs-relink",
        missingPath = "/Volumes/Audio/loops/k.wav",
        autoMatch = null,
        candidates = emptyList(),
    )

    private class FakeRepo(initial: RepairFindings) : RepairRepository {
        val flow = MutableStateFlow(initial)
        var ackedProjectId: ProjectId? = null
        var repairedProjectId: ProjectId? = null
        var dismissedKey: Pair<ProjectId, String>? = null
        var appliedMatch: Triple<ProjectId, String, String>? = null
        override fun observeFindings(projectId: ProjectId?, limit: Int): Flow<RepairFindings> = flow
        override suspend fun acknowledgeMacImport(projectId: ProjectId): Result<Unit> {
            ackedProjectId = projectId
            return Result.success(Unit)
        }
        override suspend fun applyMacPathRepair(projectId: ProjectId): Result<Unit> {
            repairedProjectId = projectId
            return Result.success(Unit)
        }
        override suspend fun dismissMissingSample(projectId: ProjectId, missingPath: String): Result<Unit> {
            dismissedKey = projectId to missingPath
            return Result.success(Unit)
        }
        override suspend fun applyMissingSampleMatch(
            projectId: ProjectId,
            missingPath: String,
            candidatePath: String,
        ): Result<Unit> {
            appliedMatch = Triple(projectId, missingPath, candidatePath)
            return Result.success(Unit)
        }
        override suspend fun restoreMissingSampleMatch(
            projectId: ProjectId,
            missingPath: String,
            candidatePath: String,
        ): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun stateMirrorsRepoFindings() = runTest {
        val findings = RepairFindings(
            macImports = listOf(macFinding),
            missingSamples = listOf(missingFinding),
            missingSamplesTotal = 4200,
            missingSamplesTruncated = true,
        )
        val holder = NeedsAttentionStateHolder(FakeRepo(findings), backgroundScope)
        holder.state.test {
            var s = awaitItem()
            while (s.macImports.isEmpty()) s = awaitItem()
            assertEquals(1, s.macImports.size)
            assertEquals(1, s.missingSamples.size)
            assertEquals(4200, s.missingSamplesTotal)
            assertTrue(s.missingSamplesTruncated)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun ackIntentRoutesToRepoAndEmitsEffect() = runTest {
        val repo = FakeRepo(RepairFindings(listOf(macFinding), emptyList(), 0, false))
        val holder = NeedsAttentionStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(NeedsAttentionStateHolder.Intent.AckMacImport(macFinding.projectId))
            val effect = awaitItem()
            assertTrue(effect is NeedsAttentionStateHolder.Effect.Acknowledged)
            assertEquals("ack", effect.kind)
            assertEquals(macFinding.projectId, repo.ackedProjectId)
        }
    }

    @Test
    fun repairMacPathsIntentRoutesToRepoAndEmitsEffect() = runTest {
        // PR-W W5 — the "Repair paths" button on a mac-import card dispatches RepairMacPaths,
        // which fans out through `applyMacPathRepair` and emits a MatchApplied effect. The legacy
        // AckMacImport intent stays around for callers that want the dismiss-without-rewriting
        // path (MCP tooling, future "ignore" flow).
        val repo = FakeRepo(RepairFindings(listOf(macFinding), emptyList(), 0, false))
        val holder = NeedsAttentionStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(NeedsAttentionStateHolder.Intent.RepairMacPaths(macFinding.projectId))
            val effect = awaitItem()
            assertTrue(effect is NeedsAttentionStateHolder.Effect.MatchApplied)
            assertEquals(macFinding.projectId, repo.repairedProjectId)
        }
    }

    @Test
    fun applyMatchIntentRoutesToRepoAndEmitsEffect() = runTest {
        val repo = FakeRepo(RepairFindings(emptyList(), listOf(missingFinding), 1, false))
        val holder = NeedsAttentionStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(NeedsAttentionStateHolder.Intent.ApplyMatch(
                projectId = missingFinding.projectId,
                missingPath = missingFinding.missingPath,
                candidatePath = "/Volumes/Library/Samples/k.wav",
            ))
            val effect = awaitItem()
            assertTrue(effect is NeedsAttentionStateHolder.Effect.MatchApplied)
            assertEquals(
                Triple(missingFinding.projectId, missingFinding.missingPath, "/Volumes/Library/Samples/k.wav"),
                repo.appliedMatch,
            )
        }
    }

    @Test
    fun dismissIntentRoutesToRepoAndEmitsEffect() = runTest {
        val repo = FakeRepo(RepairFindings(emptyList(), listOf(missingFinding), 1, false))
        val holder = NeedsAttentionStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(NeedsAttentionStateHolder.Intent.DismissMissingSample(
                missingFinding.projectId, missingFinding.missingPath,
            ))
            val effect = awaitItem()
            assertTrue(effect is NeedsAttentionStateHolder.Effect.Acknowledged)
            assertEquals("dismiss", effect.kind)
            assertEquals(missingFinding.projectId to missingFinding.missingPath, repo.dismissedKey)
        }
    }
}
