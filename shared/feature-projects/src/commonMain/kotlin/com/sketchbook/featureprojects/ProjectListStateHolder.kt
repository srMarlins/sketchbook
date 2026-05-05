package com.sketchbook.featureprojects

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Project list state holder. Plain Kotlin: a `StateFlow<State>` for UI to render, a
 * `SharedFlow<Effect>` for one-shot navigation/notifications, and a `dispatch(Intent)` entry
 * point. No MVI library — sealed-class intents handled by a `when`.
 *
 * Query changes ride a `MutableStateFlow` and `flatMapLatest` swaps the upstream subscription
 * for us — no manual `Job.cancel()` / re-launch dance when the user types. The published
 * `state` is built with `stateIn` so subscribers see the latest cached value and the upstream
 * is shared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectListStateHolder(
    private val repository: ProjectRepository,
    scope: CoroutineScope,
) {

    private val query = MutableStateFlow("")

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    val state: StateFlow<State> = query
        .flatMapLatest { q ->
            repository.observeProjects(q).map { rows -> State(query = q, rows = rows, loading = false) }
        }
        .stateIn(scope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Search -> query.update { intent.query }
            is Intent.Open -> _effects.tryEmit(Effect.Navigate(intent.id))
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
