package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.AppScope
import com.sketchbook.repo.Proposal
import com.sketchbook.repo.ProposalAction
import com.sketchbook.repo.ProposalStatus
import com.sketchbook.repo.ProposalsRepository
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Catalog-backed [ProposalsRepository] that *derives* proposals from the live catalog rather
 * than persisting a `proposals` table. The user-facing decision (approve/reject) is what's
 * persistent — stored in `proposal_acks` keyed by a stable [Proposal.proposalId] so a
 * dismissed candidate never re-surfaces, even after a re-scan that re-creates the underlying
 * row.
 *
 * v1 generates one kind: **archive-candidate** — projects untouched > 18 months with no
 * color tag, sourced from `selectArchiveCandidates`. Tag-from-folder and
 * missing-sample-repair require infrastructure that isn't online yet (a tags-by-project view
 * and a populated `samples` corpus respectively); they'll plug into the same generator
 * pipeline once those land.
 *
 * The `approve` contract per [ProposalsRepository] is: caller is responsible for actually
 * mutating the catalog. Here we just record the user's decision and return the (now
 * `Approved`) Proposal so the holder can dispatch the side effect.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlProposalsRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Instant = { Clock.System.now() },
) : ProposalsRepository {

    /** Bumped on ack write so the derived flow re-emits with the new dismissal applied. */
    private val ackTick = MutableStateFlow(0L)

    override fun observe(): Flow<List<Proposal>> {
        val cutoffSecs = (now() - ARCHIVE_AGE).epochSeconds.toDouble()
        val candidatesFlow = catalog.catalogQueries.selectArchiveCandidates(cutoffSecs)
            .asFlow()
            .mapToList(ioDispatcher)
        return combine(candidatesFlow, ackTick.onStart { emit(0L) }) { rows, _ ->
            val acks = withContext(ioDispatcher) {
                rows.associate { row ->
                    val key = archiveKey(row.id)
                    key to catalog.catalogQueries.selectProposalAck(key).executeAsOneOrNull()?.status
                }
            }
            rows.map { row ->
                val key = archiveKey(row.id)
                val status = when (acks[key]) {
                    "approved" -> ProposalStatus.Approved
                    "rejected" -> ProposalStatus.Rejected
                    else -> ProposalStatus.Pending
                }
                Proposal(
                    proposalId = key,
                    actor = "sketchbook",
                    rationale = "Untouched for over 18 months and uncolored — likely safe to archive.",
                    actions = listOf(
                        ProposalAction(
                            type = "ArchiveProject",
                            args = buildJsonObject {
                                put("project_id", JsonPrimitive(row.id))
                                put("path", JsonPrimitive(row.path))
                                put("name", JsonPrimitive(row.name))
                            },
                        ),
                    ),
                    // Use the project's last-modified time so the displayed "Nh ago" stays stable
                    // across flow re-emits (an ack write triggers ackTick → re-derivation; if we
                    // used `now()` here every other proposal would suddenly read "0s ago").
                    submittedAt = Instant.fromEpochSeconds(row.last_modified.toLong()),
                    status = status,
                )
            }
        }
    }

    override suspend fun approve(proposalId: String): Result<Proposal> = recordDecision(proposalId, "approved")

    override suspend fun reject(proposalId: String): Result<Unit> = recordDecision(proposalId, "rejected").map { }

    private suspend fun recordDecision(proposalId: String, status: String): Result<Proposal> = withContext(ioDispatcher) {
        runCatching {
            catalog.transaction {
                catalog.catalogQueries.insertProposalAck(
                    proposal_key = proposalId,
                    status = status,
                    decided_at = now().toString(),
                )
            }
            ackTick.value = ackTick.value + 1
            // Fabricate a minimal echoed Proposal so the caller's effect dispatch has
            // *something* to render; the live-derived flow is the source of truth for the
            // displayed list, so the exact contents here don't matter beyond proposalId
            // and status.
            Proposal(
                proposalId = proposalId,
                actor = "sketchbook",
                actions = emptyList(),
                submittedAt = now(),
                status = if (status == "approved") ProposalStatus.Approved else ProposalStatus.Rejected,
            )
        }
    }

    private companion object {
        /** 18 months in days — matches the legacy Python heuristic. */
        val ARCHIVE_AGE = (30L * 18).days

        fun archiveKey(projectId: Long): String = "archive:$projectId"
    }
}
