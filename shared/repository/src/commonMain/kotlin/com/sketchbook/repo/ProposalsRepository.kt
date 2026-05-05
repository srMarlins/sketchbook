package com.sketchbook.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * AI-proposed batch of actions queued for the user to approve or reject. Backed by JSON files in
 * `data/proposals/` during the parity period. Status flows: `Pending` → `Approved` (or
 * `Rejected`); approval triggers the journal write described by [actions].
 */
interface ProposalsRepository {

    /** Live list of all proposals, newest-submitted first. */
    fun observe(): Flow<List<Proposal>>

    /** Approve a proposal. Caller is responsible for invoking the catalog mutations. */
    suspend fun approve(proposalId: String): Result<Proposal>

    /** Reject a proposal. Removes it from the queue (or marks it rejected, depending on impl). */
    suspend fun reject(proposalId: String): Result<Unit>
}

data class Proposal(
    val proposalId: String,
    val actor: String,
    val rationale: String? = null,
    val actions: List<ProposalAction>,
    val submittedAt: Instant,
    val status: ProposalStatus = ProposalStatus.Pending,
)

data class ProposalAction(
    val type: String,
    val args: JsonObject,
)

enum class ProposalStatus { Pending, Approved, Rejected }
