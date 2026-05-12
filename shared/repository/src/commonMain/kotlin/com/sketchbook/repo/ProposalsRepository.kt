package com.sketchbook.repo

import com.sketchbook.core.SketchbookError
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * AI-proposed batch of actions queued for the user to approve or reject. Backed by JSON files in
 * `data/proposals/` during the parity period. Status flows: `Pending` → `Approved` (or
 * `Rejected`); approval triggers the journal write described by [actions].
 *
 * `approve` / `reject` return sealed outcome types so callers can distinguish a clean decision
 * from "we never had that proposal" or "someone already decided it" without inspecting an
 * exception's class. Infrastructure failures (catalog write error) throw [SketchbookError].
 * Today's impl always returns the `Approved` / `Rejected` variant; the `NotFound` and
 * `AlreadyDecided` branches are reserved for future tightening of the ack semantics (see
 * `docs/plans/2026-05-12-result-refactor-design.md`).
 */
interface ProposalsRepository {
    /** Live list of all proposals, newest-submitted first. */
    fun observe(): Flow<List<Proposal>>

    /** Approve a proposal. Caller is responsible for invoking the catalog mutations. */
    @Throws(SketchbookError::class)
    suspend fun approve(proposalId: String): ApproveOutcome

    /** Reject a proposal. Removes it from the queue (or marks it rejected, depending on impl). */
    @Throws(SketchbookError::class)
    suspend fun reject(proposalId: String): RejectOutcome
}

/** Outcome of [ProposalsRepository.approve]. */
sealed interface ApproveOutcome {
    data class Approved(val proposal: Proposal) : ApproveOutcome

    /** No proposal matches the supplied id. */
    data object NotFound : ApproveOutcome

    /** The proposal was already approved or rejected by an earlier call. */
    data object AlreadyDecided : ApproveOutcome
}

/** Outcome of [ProposalsRepository.reject]. */
sealed interface RejectOutcome {
    data object Rejected : RejectOutcome

    /** No proposal matches the supplied id. */
    data object NotFound : RejectOutcome

    /** The proposal was already approved or rejected by an earlier call. */
    data object AlreadyDecided : RejectOutcome
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
