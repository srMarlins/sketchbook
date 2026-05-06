package com.sketchbook.featureproposals

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.actions.ProposalActionExecutor
import com.sketchbook.repo.Proposal
import com.sketchbook.repo.ProposalStatus
import com.sketchbook.repo.ProposalsRepository
import com.sketchbook.core.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Proposals queue. Reads through `ProposalsRepository.observe()` and pipes Approve/Reject intents
 * back through the repo. The publish path is a single `stateIn` over the repo flow — no manual
 * `Job` cancel / re-launch.
 */
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class ProposalsViewModel(
    private val repository: ProposalsRepository,
    private val executor: ProposalActionExecutor? = null,
) : ViewModel() {

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    val state: StateFlow<State> = repository.observe()
        .map { proposals ->
            State(
                pending = proposals.filter { it.status == ProposalStatus.Pending },
                resolved = proposals.filter { it.status != ProposalStatus.Pending },
                loading = false,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Approve -> viewModelScope.launch {
                val proposal = state.value.pending.firstOrNull { it.proposalId == intent.proposalId }
                    ?: state.value.resolved.firstOrNull { it.proposalId == intent.proposalId }
                if (proposal == null) {
                    _effects.tryEmit(Effect.Failed(intent.proposalId, "proposal not found"))
                    return@launch
                }
                val applied = executor?.apply(proposal.actions) ?: Result.success(Unit)
                if (applied.isFailure) {
                    _effects.tryEmit(
                        Effect.Failed(
                            intent.proposalId,
                            applied.exceptionOrNull()?.message ?: "apply failed",
                        ),
                    )
                    return@launch
                }
                val r = repository.approve(intent.proposalId)
                if (r.isSuccess) _effects.tryEmit(Effect.Approved(intent.proposalId))
                else _effects.tryEmit(Effect.Failed(intent.proposalId, r.exceptionOrNull()?.message ?: "approve failed"))
            }
            is Intent.Reject -> viewModelScope.launch {
                val r = repository.reject(intent.proposalId)
                if (r.isSuccess) _effects.tryEmit(Effect.Rejected(intent.proposalId))
                else _effects.tryEmit(Effect.Failed(intent.proposalId, r.exceptionOrNull()?.message ?: "reject failed"))
            }
        }
    }

    @Immutable
    data class State(
        val pending: List<Proposal> = emptyList(),
        val resolved: List<Proposal> = emptyList(),
        val loading: Boolean = false,
    )

    sealed interface Intent {
        data class Approve(val proposalId: String) : Intent
        data class Reject(val proposalId: String) : Intent
    }

    sealed interface Effect {
        data class Approved(val proposalId: String) : Effect
        data class Rejected(val proposalId: String) : Effect
        data class Failed(val proposalId: String, val reason: String) : Effect
    }
}
