package com.sketchbook.featureneedsattention

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.RepairRepository
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
 * Repair surface. Reads `RepairRepository.observeFindings()` and exposes a single state with the
 * mac-import + missing-sample lists plus truncation metadata.
 */
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class NeedsAttentionViewModel(
    private val repository: RepairRepository,
) : ViewModel() {

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    val state: StateFlow<State> = repository.observeFindings()
        .map { findings ->
            State(
                macImports = findings.macImports,
                missingSamples = findings.missingSamples,
                missingSamplesTotal = findings.missingSamplesTotal,
                missingSamplesTruncated = findings.missingSamplesTruncated,
                loading = false,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.AckMacImport -> viewModelScope.launch {
                val r = repository.acknowledgeMacImport(intent.projectId)
                emitEffect(r.isSuccess, intent.projectId.value.toString(), "ack")
            }

            is Intent.RepairMacPaths -> viewModelScope.launch {
                val r = repository.applyMacPathRepair(intent.projectId)
                if (r.isSuccess) {
                    _effects.tryEmit(Effect.MatchApplied(intent.projectId.value.toString()))
                } else {
                    _effects.tryEmit(Effect.Failed(intent.projectId.value.toString(), "repair failed"))
                }
            }

            is Intent.DismissMissingSample -> viewModelScope.launch {
                val r = repository.dismissMissingSample(intent.projectId, intent.missingPath)
                emitEffect(r.isSuccess, intent.projectId.value.toString(), "dismiss")
            }

            is Intent.ApplyMatch -> viewModelScope.launch {
                val r = repository.applyMissingSampleMatch(
                    projectId = intent.projectId,
                    missingPath = intent.missingPath,
                    candidatePath = intent.candidatePath,
                )
                if (r.isSuccess) {
                    _effects.tryEmit(Effect.MatchApplied(intent.projectId.value.toString()))
                } else {
                    _effects.tryEmit(Effect.Failed(intent.projectId.value.toString(), "apply failed"))
                }
            }
        }
    }

    private fun emitEffect(success: Boolean, key: String, kind: String) {
        if (success) {
            _effects.tryEmit(Effect.Acknowledged(key, kind))
        } else {
            _effects.tryEmit(Effect.Failed(key, "$kind failed"))
        }
    }

    @Immutable
    data class State(
        val macImports: List<MacImportFinding> = emptyList(),
        val missingSamples: List<MissingSampleFinding> = emptyList(),
        val missingSamplesTotal: Int = 0,
        val missingSamplesTruncated: Boolean = false,
        val loading: Boolean = false,
    )

    sealed interface Intent {
        data class AckMacImport(val projectId: ProjectId) : Intent
        data class RepairMacPaths(val projectId: ProjectId) : Intent
        data class DismissMissingSample(val projectId: ProjectId, val missingPath: String) : Intent
        data class ApplyMatch(
            val projectId: ProjectId,
            val missingPath: String,
            val candidatePath: String,
        ) : Intent
    }

    sealed interface Effect {
        data class Acknowledged(val key: String, val kind: String) : Effect
        data class MatchApplied(val key: String) : Effect
        data class Failed(val key: String, val reason: String) : Effect
    }
}
