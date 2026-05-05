package com.sketchbook.featureproposals

import app.cash.turbine.test
import com.sketchbook.repo.Proposal
import com.sketchbook.repo.ProposalStatus
import com.sketchbook.repo.ProposalsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProposalsStateHolderTest {

    private val now = Instant.parse("2026-05-05T12:00:00Z")
    private fun proposal(id: String, status: ProposalStatus = ProposalStatus.Pending) = Proposal(
        proposalId = id,
        actor = "claude",
        rationale = "tidy",
        actions = listOf(
            com.sketchbook.repo.ProposalAction(
                type = "SetTags",
                args = buildJsonObject { put("project_id", 7) },
            ),
        ),
        submittedAt = now,
        status = status,
    )

    private class FakeRepo(val flow: MutableStateFlow<List<Proposal>>) : ProposalsRepository {
        var approved: String? = null
        var rejected: String? = null
        var failNext: Boolean = false
        override fun observe(): Flow<List<Proposal>> = flow
        override suspend fun approve(proposalId: String): Result<Proposal> {
            if (failNext) { failNext = false; return Result.failure<Proposal>(IllegalStateException("approve boom")) }
            approved = proposalId
            val updated = flow.value.map { if (it.proposalId == proposalId) it.copy(status = ProposalStatus.Approved) else it }
            flow.value = updated
            return Result.success(updated.first { it.proposalId == proposalId })
        }
        override suspend fun reject(proposalId: String): Result<Unit> {
            rejected = proposalId
            val updated = flow.value.map { if (it.proposalId == proposalId) it.copy(status = ProposalStatus.Rejected) else it }
            flow.value = updated
            return Result.success(Unit)
        }
    }

    @Test
    fun statePartitionsPendingFromResolved() = runTest {
        val flow = MutableStateFlow(listOf(
            proposal("p1"),
            proposal("p2", ProposalStatus.Approved),
            proposal("p3", ProposalStatus.Rejected),
        ))
        val holder = ProposalsStateHolder(FakeRepo(flow), backgroundScope)
        holder.state.test {
            var s = awaitItem()
            while (s.pending.isEmpty() && s.resolved.isEmpty()) s = awaitItem()
            assertEquals(listOf("p1"), s.pending.map { it.proposalId })
            assertEquals(listOf("p2", "p3"), s.resolved.map { it.proposalId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun approveDispatchesToRepoAndEmitsApprovedEffect() = runTest {
        val flow = MutableStateFlow(listOf(proposal("p1")))
        val repo = FakeRepo(flow)
        val holder = ProposalsStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(ProposalsStateHolder.Intent.Approve("p1"))
            val effect = awaitItem()
            assertTrue(effect is ProposalsStateHolder.Effect.Approved)
            assertEquals("p1", effect.proposalId)
            assertEquals("p1", repo.approved)
        }
    }

    @Test
    fun rejectDispatchesToRepoAndEmitsRejectedEffect() = runTest {
        val flow = MutableStateFlow(listOf(proposal("p1")))
        val repo = FakeRepo(flow)
        val holder = ProposalsStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(ProposalsStateHolder.Intent.Reject("p1"))
            val effect = awaitItem()
            assertTrue(effect is ProposalsStateHolder.Effect.Rejected)
            assertEquals("p1", repo.rejected)
        }
    }

    @Test
    fun approveFailureEmitsFailedEffect() = runTest {
        val flow = MutableStateFlow(listOf(proposal("p1")))
        val repo = FakeRepo(flow).also { it.failNext = true }
        val holder = ProposalsStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(ProposalsStateHolder.Intent.Approve("p1"))
            val effect = awaitItem()
            assertTrue(effect is ProposalsStateHolder.Effect.Failed)
            assertEquals("approve boom", effect.reason)
        }
    }
}
