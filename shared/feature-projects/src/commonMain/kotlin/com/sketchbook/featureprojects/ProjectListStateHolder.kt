package com.sketchbook.featureprojects

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Project list state holder. Plain Kotlin: a `StateFlow<State>` for UI to render, a
 * `SharedFlow<Effect>` for one-shot navigation/notifications, and a `dispatch(Intent)` entry
 * point. No MVI library — sealed-class intents handled by a `when`.
 *
 * State is refreshed reactively from the [ProjectRepository.observeProjects] flow whenever the
 * query changes. The collector restarts on `Search` so we don't accumulate stale subscribers.
 */
class ProjectListStateHolder(
    private val repository: ProjectRepository,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private var currentObserveJob: Job? = null

    init {
        observe(query = "")
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Search -> {
                _state.value = _state.value.copy(query = intent.query)
                observe(intent.query)
            }
            is Intent.Open -> {
                _effects.tryEmit(Effect.Navigate(intent.id))
            }
        }
    }

    private fun observe(query: String) {
        currentObserveJob?.cancel()
        currentObserveJob = scope.launch {
            repository.observeProjects(query).collectLatest { rows ->
                _state.value = _state.value.copy(rows = rows, loading = false)
            }
        }
    }

    data class State(
        val query: String = "",
        val rows: List<ProjectRow> = emptyList(),
        val loading: Boolean = true,
    )

    sealed interface Intent {
        data class Search(val query: String) : Intent
        data class Open(val id: ProjectId) : Intent
    }

    sealed interface Effect {
        data class Navigate(val id: ProjectId) : Effect
    }
}
