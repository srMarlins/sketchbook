package com.sketchbook.desktop.repo

import com.sketchbook.repo.Proposal
import com.sketchbook.repo.ProposalStatus
import com.sketchbook.repo.ProposalsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryProposalsRepository : ProposalsRepository {
    private val proposals = MutableStateFlow<List<Proposal>>(emptyList())

    override fun observe(): Flow<List<Proposal>> = proposals

    override suspend fun approve(proposalId: String): Result<Proposal> {
        val updated = mutateStatus(proposalId, ProposalStatus.Approved)
        return updated?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("no proposal $proposalId"))
    }

    override suspend fun reject(proposalId: String): Result<Unit> {
        mutateStatus(proposalId, ProposalStatus.Rejected)
            ?: return Result.failure(NoSuchElementException("no proposal $proposalId"))
        return Result.success(Unit)
    }

    private fun mutateStatus(proposalId: String, status: ProposalStatus): Proposal? {
        var hit: Proposal? = null
        proposals.update { list ->
            list.map { p ->
                if (p.proposalId == proposalId) p.copy(status = status).also { hit = it } else p
            }
        }
        return hit
    }
}
