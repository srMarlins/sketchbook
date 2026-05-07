package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.repo.SyncQueue
import com.sketchbook.repo.SyncQueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Stub `SyncQueue` for the desktop shell. Until the real cloud orchestrator wires up, the
 * queue uses a deterministic per-project sync state derived from `id mod 7` so the UI can
 * actually visualize the cloud-sync surfaces (per-row pip, sidebar caption, ActivityBar
 * Syncing state, Settings counters). When the real queue ships, only the impl swaps.
 *
 * **Service-layer seeding.** The queue collects `ProjectRepository.observeProjects("")` from a
 * background coroutine on the app scope and assigns a state to every new id it sees. The UI
 * only consumes — never feeds — the queue, so a Composable's `LaunchedEffect` doesn't end up
 * running data-layer work.
 *
 * Mapping (id mod 7):
 *   0,1,2,3 -> Synced       (most rows: hands the user a calm "everything's fine" baseline)
 *   4       -> Pending      (one in seven needs an upload — visible without overwhelming)
 *   5       -> Conflict     (rare red flag so the conflict glyph appears at all)
 *   6       -> LocalOnly    (legacy / never-uploaded rows, distinct from Pending)
 *
 * The aggregate [SyncQueueState] mirrors the per-project counts so the chrome stays consistent
 * with the row pips. `pushNowById` flips a row Pending→Uploading→Synced over a small delay
 * so the "Sync now" button has visible feedback.
 */
class InMemorySyncQueue(
    private val projects: ProjectRepository,
    private val scope: CoroutineScope,
) : SyncQueue {
    private val perProject = MutableStateFlow<Map<ProjectId, ProjectSyncState>>(emptyMap())
    private val online = MutableStateFlow(true)

    init {
        start()
    }

    /** Subscribe to the project list and assign a default state to any newly-seen id. */
    private fun start() {
        scope.launch {
            projects.observeProjects("").collect { rows ->
                val current = perProject.value
                if (rows.isEmpty()) return@collect
                val needsSeed = rows.any { it.id !in current }
                if (!needsSeed) return@collect
                val next = current.toMutableMap()
                for (row in rows) {
                    if (row.id !in next) next[row.id] = defaultStateFor(row.id)
                }
                perProject.value = next
            }
        }
    }

    private val queue: Flow<SyncQueueState> =
        combine(perProject, online) { states, isOnline ->
            SyncQueueState(
                pending = states.count { it.value == ProjectSyncState.Pending },
                uploading = states.count { it.value == ProjectSyncState.Uploading },
                downloading = 0,
                online = isOnline,
            )
        }

    override fun observe(): Flow<SyncQueueState> = queue

    override fun observeProject(id: ProjectId): Flow<ProjectSyncState> = perProject.map { it[id] ?: defaultStateFor(id) }

    override suspend fun pushNow(uuid: ProjectUuid): Result<Unit> {
        // Without a uuid->id map at v1, treat pushNow as a no-op on aggregates. Per-row
        // "Sync now" goes through [pushNowById].
        return Result.success(Unit)
    }

    /**
     * Flip a specific project's state Uploading → Synced over ~450 ms so the Sync-now button
     * has visible feedback. Suspends through the transition.
     */
    suspend fun pushNowById(id: ProjectId) {
        perProject.value = perProject.value + (id to ProjectSyncState.Uploading)
        delay(450)
        perProject.value = perProject.value + (id to ProjectSyncState.Synced)
    }

    /** Test helper. */
    fun seedStates(states: Map<ProjectId, ProjectSyncState>) {
        perProject.value = states
    }

    /** Read-through synchronous accessor for UI lookups inside non-suspending composables. */
    fun snapshotFor(id: ProjectId): ProjectSyncState = perProject.value[id] ?: defaultStateFor(id)

    private fun defaultStateFor(id: ProjectId): ProjectSyncState =
        when (id.value.mod(7)) {
            4 -> ProjectSyncState.Pending
            5 -> ProjectSyncState.Conflict
            6 -> ProjectSyncState.LocalOnly
            else -> ProjectSyncState.Synced
        }
}
