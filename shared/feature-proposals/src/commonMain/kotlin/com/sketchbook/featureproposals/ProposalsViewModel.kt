package com.sketchbook.featureproposals

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.actions.ProposalActionExecutor
import com.sketchbook.core.AppScope
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.Proposal
import com.sketchbook.repo.ProposalAction
import com.sketchbook.repo.ProposalStatus
import com.sketchbook.repo.ProposalsRepository
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Proposals queue. Reads through `ProposalsRepository.observe()` plus a `ProjectRepository` flow
 * for friendly project names, combined with two filter flows (source actor + free-text search).
 *
 * State exposes:
 *  - `groups`: pending proposals partitioned by [ProposalCategory], in the order users naturally
 *    work through them (Archive → Move → Tag → Color → Other).
 *  - `resolved`: a flat list shown in a "Resolved this session" card so users can see what they
 *    just acted on without losing the queue.
 *  - `projectNamesById`: cached lookup the screen passes to `proposalLabel(...)` so rows render
 *    "Old Sketch" instead of "project #263".
 *
 * Bulk approve/reject pipe through the same single-item code path for consistency, emitting one
 * aggregated Bulk* effect at the end so the screen can show a single "Approved 7 / failed 1"
 * snackbar instead of seven individual ones.
 */
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class ProposalsViewModel(
    private val repository: ProposalsRepository,
    private val executor: ProposalActionExecutor? = null,
    private val projects: ProjectRepository? = null,
) : ViewModel() {
    private val _effects =
        MutableSharedFlow<Effect>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val filters = MutableStateFlow(Filters())

    val state: StateFlow<State> =
        combine(
            repository.observe(),
            projects?.observeProjects("") ?: kotlinx.coroutines.flow.flowOf(emptyList()),
            filters,
        ) { proposals, projectRows, f ->
            val nameById = projectRows.associate { it.id.value to it.name }
            val pendingAll = proposals.filter { it.status == ProposalStatus.Pending }
            val resolvedAll = proposals.filter { it.status != ProposalStatus.Pending }
            val visiblePending =
                pendingAll
                    .filter { matchesSource(it.actor, f.sourceFilter) }
                    .filter { matchesSearch(it, nameById, f.search) }
            val groups =
                visiblePending
                    .groupBy { categoryOf(it) }
                    .toSortedMap(categoryOrder)
                    .map { (category, items) -> ProposalGroup(category, items) }
            State(
                pending = visiblePending,
                resolved = resolvedAll,
                groups = groups,
                projectNamesById = nameById,
                search = f.search,
                sourceFilter = f.sourceFilter,
                loading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.SetSourceFilter -> {
                filters.update { it.copy(sourceFilter = intent.filter) }
            }

            is Intent.SetSearch -> {
                filters.update { it.copy(search = intent.query) }
            }

            is Intent.Approve -> {
                viewModelScope.launch {
                    applyAndApprove(intent.proposalId, single = true)
                }
            }

            is Intent.Reject -> {
                viewModelScope.launch { rejectOne(intent.proposalId, single = true) }
            }

            is Intent.BulkApprove -> {
                viewModelScope.launch {
                    val approvedIds = mutableListOf<String>()
                    val failed = mutableListOf<Pair<String, String>>()
                    for (id in intent.proposalIds) {
                        when (val r = applyAndApprove(id, single = false)) {
                            is ApplyResult.Approved -> approvedIds += id
                            is ApplyResult.Failed -> failed += id to r.reason
                        }
                    }
                    _effects.tryEmit(Effect.BulkApproved(approvedIds, failed))
                }
            }

            is Intent.BulkReject -> {
                viewModelScope.launch {
                    val rejected = mutableListOf<String>()
                    val failed = mutableListOf<Pair<String, String>>()
                    for (id in intent.proposalIds) {
                        when (val r = rejectOne(id, single = false)) {
                            is RejectResult.Rejected -> rejected += id
                            is RejectResult.Failed -> failed += id to r.reason
                        }
                    }
                    _effects.tryEmit(Effect.BulkRejected(rejected, failed))
                }
            }
        }
    }

    private suspend fun applyAndApprove(
        proposalId: String,
        single: Boolean,
    ): ApplyResult {
        // Look up directly through the repo so we don't depend on `state` having warmed up — under
        // a TestDispatcher the eager combine may not have collected by the time tests dispatch.
        val proposals = repository.observe().first()
        val proposal = proposals.firstOrNull { it.proposalId == proposalId }
        if (proposal == null) {
            if (single) _effects.tryEmit(Effect.Failed(proposalId, "proposal not found"))
            return ApplyResult.Failed("proposal not found")
        }
        val applied = executor?.apply(proposal.actions) ?: Result.success(Unit)
        if (applied.isFailure) {
            val reason = applied.exceptionOrNull()?.message ?: "apply failed"
            if (single) _effects.tryEmit(Effect.Failed(proposalId, reason))
            return ApplyResult.Failed(reason)
        }
        val r = repository.approve(proposalId)
        return if (r.isSuccess) {
            if (single) _effects.tryEmit(Effect.Approved(proposalId))
            ApplyResult.Approved
        } else {
            val reason = r.exceptionOrNull()?.message ?: "approve failed"
            if (single) _effects.tryEmit(Effect.Failed(proposalId, reason))
            ApplyResult.Failed(reason)
        }
    }

    private suspend fun rejectOne(
        proposalId: String,
        single: Boolean,
    ): RejectResult {
        val r = repository.reject(proposalId)
        return if (r.isSuccess) {
            if (single) _effects.tryEmit(Effect.Rejected(proposalId))
            RejectResult.Rejected
        } else {
            val reason = r.exceptionOrNull()?.message ?: "reject failed"
            if (single) _effects.tryEmit(Effect.Failed(proposalId, reason))
            RejectResult.Failed(reason)
        }
    }

    private fun matchesSource(
        actor: String,
        filter: SourceFilter,
    ): Boolean =
        when (filter) {
            SourceFilter.All -> {
                true
            }

            // Best-effort actor → source mapping. The MCP subprocess writes "sketchbook" today;
            // hand-rolled CLI / future user-driven proposals will use other actor names. Anything
            // that doesn't match a known prefix lands under "User" so it's never invisible.
            SourceFilter.Mcp -> {
                actor.equals("sketchbook", ignoreCase = true) ||
                    actor.startsWith("mcp", ignoreCase = true)
            }

            SourceFilter.Code -> {
                actor.startsWith("code", ignoreCase = true) ||
                    actor.startsWith("agent", ignoreCase = true)
            }

            SourceFilter.User -> {
                !actor.equals("sketchbook", ignoreCase = true) &&
                    !actor.startsWith("mcp", ignoreCase = true) &&
                    !actor.startsWith("code", ignoreCase = true) &&
                    !actor.startsWith("agent", ignoreCase = true)
            }
        }

    private fun matchesSearch(
        p: Proposal,
        nameById: Map<Long, String>,
        query: String,
    ): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase()
        if (p.rationale?.lowercase()?.contains(q) == true) return true
        if (p.actor.lowercase().contains(q)) return true
        for (a in p.actions) {
            if (a.type.lowercase().contains(q)) return true
            val pid = (a.args["project_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            val name = pid?.let { nameById[it] }
            if (name?.lowercase()?.contains(q) == true) return true
            for ((_, value) in a.args) {
                if ((value as? JsonPrimitive)?.contentOrNull?.lowercase()?.contains(q) == true) return true
            }
        }
        return false
    }

    private fun categoryOf(p: Proposal): ProposalCategory {
        val first = p.actions.firstOrNull()?.type ?: return ProposalCategory.Other
        return when (first) {
            "ArchiveProject" -> ProposalCategory.Archive
            "MoveProject", "RenameProject" -> ProposalCategory.Move
            "SetTags" -> ProposalCategory.Tag
            "SetColorTag" -> ProposalCategory.Color
            else -> ProposalCategory.Other
        }
    }

    enum class SourceFilter { All, Mcp, Code, User }

    enum class ProposalCategory(
        val label: String,
    ) {
        Archive("Archive"),
        Move("Move / Rename"),
        Tag("Tag"),
        Color("Color"),
        Other("Other"),
    }

    private val categoryOrder: Comparator<ProposalCategory> = compareBy { it.ordinal }

    @Immutable
    data class ProposalGroup(
        val category: ProposalCategory,
        val proposals: List<Proposal>,
    ) {
        val label: String get() = category.label
    }

    @Immutable
    data class State(
        val pending: List<Proposal> = emptyList(),
        val resolved: List<Proposal> = emptyList(),
        val groups: List<ProposalGroup> = emptyList(),
        val projectNamesById: Map<Long, String> = emptyMap(),
        val search: String = "",
        val sourceFilter: SourceFilter = SourceFilter.All,
        val loading: Boolean = false,
    )

    private data class Filters(
        val sourceFilter: SourceFilter = SourceFilter.All,
        val search: String = "",
    )

    private sealed interface ApplyResult {
        object Approved : ApplyResult

        data class Failed(
            val reason: String,
        ) : ApplyResult
    }

    private sealed interface RejectResult {
        object Rejected : RejectResult

        data class Failed(
            val reason: String,
        ) : RejectResult
    }

    sealed interface Intent {
        data class Approve(
            val proposalId: String,
        ) : Intent

        data class Reject(
            val proposalId: String,
        ) : Intent

        data class BulkApprove(
            val proposalIds: List<String>,
        ) : Intent

        data class BulkReject(
            val proposalIds: List<String>,
        ) : Intent

        data class SetSourceFilter(
            val filter: SourceFilter,
        ) : Intent

        data class SetSearch(
            val query: String,
        ) : Intent
    }

    sealed interface Effect {
        data class Approved(
            val proposalId: String,
        ) : Effect

        data class Rejected(
            val proposalId: String,
        ) : Effect

        data class Failed(
            val proposalId: String,
            val reason: String,
        ) : Effect

        data class BulkApproved(
            val approvedIds: List<String>,
            val failed: List<Pair<String, String>>,
        ) : Effect

        data class BulkRejected(
            val rejectedIds: List<String>,
            val failed: List<Pair<String, String>>,
        ) : Effect
    }
}
