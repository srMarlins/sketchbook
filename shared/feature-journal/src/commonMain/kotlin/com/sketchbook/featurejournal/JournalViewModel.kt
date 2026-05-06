package com.sketchbook.featurejournal

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
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

/**
 * Read-only journal viewer. Reads `JournalRepository.observeRecent` and exposes a single state.
 */
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class JournalViewModel(
    private val repository: JournalRepository,
) : ViewModel() {

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    val state: StateFlow<State> = repository.observeRecent(LIMIT)
        .map { entries -> State(entries = entries, loading = false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.OpenProject -> _effects.tryEmit(Effect.NavigateToProject(intent.projectId))
        }
    }

    @Immutable
    data class State(
        val entries: List<JournalEntry> = emptyList(),
        val loading: Boolean = false,
    )

    sealed interface Intent {
        data class OpenProject(val projectId: ProjectId) : Intent
    }

    sealed interface Effect {
        data class NavigateToProject(val projectId: ProjectId) : Effect
    }

    private companion object {
        const val LIMIT = 200
    }
}
