package com.sketchbook.featurejournal

import com.sketchbook.core.ProjectId
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Read-only journal viewer. Reads `JournalRepository.observeRecent` and exposes a single state
 * with a chronological list. Tap a row to navigate to project detail.
 */
class JournalStateHolder(
    private val repository: JournalRepository,
    private val scope: CoroutineScope,
    private val limit: Int = 200,
) {

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    val state: StateFlow<State> = repository.observeRecent(limit)
        .map { entries -> State(entries = entries, loading = false) }
        .stateIn(scope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.OpenProject -> _effects.tryEmit(Effect.NavigateToProject(intent.projectId))
        }
    }

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
}
