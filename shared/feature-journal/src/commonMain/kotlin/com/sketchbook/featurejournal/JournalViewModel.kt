package com.sketchbook.featurejournal

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.ProjectDisplayHints
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.resolveProjectDisplay
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Journal viewer with filters, search, day grouping, and single + bulk undo.
 *
 * Combines `JournalRepository.observeRecent` with `ProjectRepository.observeProjects("")` so each
 * row carries a resolved project name (or `"project #ID"` fallback). Filters live in their own
 * [MutableStateFlow] and re-derive the visible row list when any axis changes — filtering happens
 * after name resolution so search can hit the resolved name.
 *
 * Day buckets and bulk-undo metadata are pre-computed here; the screen renders [State.days],
 * [State.invertibleEntries], and [State.isNarrowed] directly without any arithmetic.
 */
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class JournalViewModel(
    private val repository: JournalRepository,
    private val projects: ProjectRepository,
    private val repair: RepairRepository,
) : ViewModel() {

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val filters = MutableStateFlow(Filters())

    val state: StateFlow<State> = combine(
        repository.observeRecent(LIMIT),
        projects.observeAllProjectNames(),
        filters,
    ) { entries, nameById, f ->
        val now = Clock.System.now()
        val rows = entries.asSequence()
            .filter { isVisibleAction(it.action) }
            .filter { matchesActionType(it.action, f.actionType) }
            .filter { matchesDateRange(it.timestamp, f.dateRange, now) }
            .filter { matchesSearch(it, resolveName(it, nameById), f.search) }
            .map { e -> JournalRow(e, resolveName(e, nameById)) }
            .toList()
        val days = if (rows.isEmpty()) emptyList() else buildDayGroups(rows, now)
        val invertibleEntries = rows.filter { it.isInvertible }.map { it.entry }
        val narrowed = f.actionType != ActionTypeFilter.All ||
            f.search.isNotBlank() ||
            f.dateRange != DateRange.AllTime
        State(
            rows = rows,
            days = days,
            invertibleEntries = invertibleEntries,
            isNarrowed = narrowed,
            actionTypeFilter = f.actionType,
            search = f.search,
            dateRange = f.dateRange,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.OpenProject -> _effects.tryEmit(Effect.NavigateToProject(intent.projectId))

            is Intent.SetActionTypeFilter -> filters.update { it.copy(actionType = intent.filter) }

            is Intent.SetSearch -> filters.update { it.copy(search = intent.query) }

            is Intent.SetDateRange -> filters.update { it.copy(dateRange = intent.range) }

            is Intent.UndoOne -> viewModelScope.launch {
                val r = undoOne(intent.entry)
                if (r.isSuccess) {
                    _effects.tryEmit(Effect.Undone(intent.entry))
                } else {
                    _effects.tryEmit(
                        Effect.UndoFailed(intent.entry, r.exceptionOrNull()?.message ?: "unknown"),
                    )
                }
            }

            is Intent.BulkUndo -> viewModelScope.launch {
                val successes = mutableListOf<JournalEntry>()
                val failures = mutableListOf<Pair<JournalEntry, String>>()
                for (e in intent.entries) {
                    val r = undoOne(e)
                    if (r.isSuccess) {
                        successes += e
                    } else {
                        failures += e to (r.exceptionOrNull()?.message ?: "unknown")
                    }
                }
                _effects.tryEmit(Effect.BulkUndone(successes, failures))
            }
        }
    }

    /**
     * Inverts a single journal entry by calling the corresponding [ProjectRepository] mutator.
     * Move/Rename do an on-disk safety check first: if the project's current path/name no longer
     * matches the entry's `pathAfter`/`nameAfter`, the undo skips — the user has done something
     * else since (or external state has drifted) and we don't want to clobber that.
     *
     * `ForceTakeLock`, `PushConflict`, and the .als-related entries (`MissingSampleMapped/Unmapped`,
     * `MacPathRepaired`, `SnapshotRelabeled`) are not invertible from this generic path. Repair-
     * surface entries have their own undo flow in Needs Attention; the others are informational.
     */
    private suspend fun undoOne(entry: JournalEntry): Result<Unit> = when (val a = entry.action) {
        is ActionRecord.Move -> {
            val current = projects.observeProject(entry.projectId).first()
            when {
                current == null -> Result.failure(IllegalStateException("project not found"))

                current.path.value != a.pathAfter -> Result.failure(
                    IllegalStateException("file is no longer at the recorded location — undo skipped"),
                )

                else -> projects.move(entry.projectId, parentDirOf(a.pathBefore)).map {}
            }
        }

        is ActionRecord.Rename -> {
            val current = projects.observeProject(entry.projectId).first()
            when {
                current == null -> Result.failure(IllegalStateException("project not found"))

                current.name != a.nameAfter -> Result.failure(
                    IllegalStateException("project no longer named ${a.nameAfter} — undo skipped"),
                )

                else -> projects.rename(entry.projectId, a.nameBefore).map {}
            }
        }

        is ActionRecord.Archive -> projects.archive(entry.projectId, a.wasArchived).map {}

        is ActionRecord.SetTags -> projects.setTags(entry.projectId, a.before).map {}

        is ActionRecord.MacPathRepaired -> repair.restoreMacPathRepair(entry.projectId)

        else -> Result.failure(IllegalArgumentException("not invertible"))
    }

    private fun parentDirOf(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "" else path.substring(0, idx)
    }

    private fun buildDayGroups(rows: List<JournalRow>, now: Instant): List<DayGroup> {
        val byLabel = LinkedHashMap<String, MutableList<JournalRow>>()
        for (row in rows) {
            byLabel.getOrPut(dayLabel(row.entry.timestamp, now)) { mutableListOf() }.add(row)
        }
        return byLabel.map { (label, rs) -> DayGroup(label, rs) }
    }

    /**
     * "Today" is a 24-hour rolling window, then "Yesterday", then "N days ago" up to 6, then
     * ISO yyyy-MM-dd. The screen renders the label string verbatim — no view-side date math.
     */
    private fun dayLabel(ts: Instant, now: Instant): String {
        val deltaDays = (now - ts).inWholeDays
        return when {
            deltaDays <= 0 -> "Today"
            deltaDays == 1L -> "Yesterday"
            deltaDays <= 6 -> "$deltaDays days ago"
            else -> ts.toString().substring(0, 10)
        }
    }

    /**
     * Resolve a project name via the shared [resolveProjectDisplay] resolver. The fallback chain
     * — denormalized [JournalEntry.projectName] → catalog map → action-derived path basename →
     * `"project #N"` sentinel — lives in `shared/repository/.../ProjectDisplay.kt`; this method
     * only assembles the hints.
     *
     * The denormalization on `entry.projectName` is what saves us when the catalog rescan
     * reassigns ids and the old `project_id` no longer points at the row that originally produced
     * the entry — `journal_entries.project_id` deliberately has no FK so stale ids stay readable.
     */
    @Suppress("FunctionExpressionBody")
    private fun resolveName(entry: JournalEntry, nameById: Map<ProjectId, String>): String = resolveProjectDisplay(
        id = entry.projectId,
        hints = ProjectDisplayHints(
            denormName = entry.projectName,
            pathHint = pathHintFromAction(entry.action),
        ),
        nameById = nameById,
    )

    /**
     * Extract a path/name hint from the action variants that carry one. The resolver basenames the
     * result and strips `.als`, so for sample paths we pre-walk to the parent dir so the project
     * folder leaf surfaces (not the sample filename).
     */
    private fun pathHintFromAction(action: ActionRecord): String? = when (action) {
        is ActionRecord.Move -> action.pathAfter.ifBlank { null } ?: action.pathBefore.ifBlank { null }
        is ActionRecord.Rename -> action.nameAfter.takeUnless { it.isBlank() }
        is ActionRecord.MissingSampleMapped -> parentDirOfSample(action.missingPath)
        is ActionRecord.MissingSampleUnmapped -> parentDirOfSample(action.missingPath)
        else -> null
    }

    /** For sample paths, the project name is the *parent* dir's leaf, not the sample filename. */
    private fun parentDirOfSample(samplePath: String): String? {
        val normalized = samplePath.replace('\\', '/')
        val parent = normalized.substringBeforeLast('/', "")
        return parent.takeUnless { it.isBlank() }
    }

    /**
     * Hide noise from the journal feed: a `MacPathRepaired` with `mappingCount == 0` means the
     * repair pass ran but found no Mac-style paths to rewrite — nothing actually changed for the
     * user, so the row is just clutter in the History column.
     */
    private fun isVisibleAction(action: ActionRecord): Boolean = when (action) {
        is ActionRecord.MacPathRepaired -> action.mappingCount > 0
        else -> true
    }

    private fun matchesActionType(action: ActionRecord, filter: ActionTypeFilter): Boolean = when (filter) {
        ActionTypeFilter.All -> true
        ActionTypeFilter.Move -> action is ActionRecord.Move
        ActionTypeFilter.Rename -> action is ActionRecord.Rename
        ActionTypeFilter.Archive -> action is ActionRecord.Archive
        ActionTypeFilter.Tag -> action is ActionRecord.SetTags
        ActionTypeFilter.Lock -> action is ActionRecord.ForceTakeLock
        ActionTypeFilter.Conflict -> action is ActionRecord.PushConflict
    }

    private fun matchesDateRange(ts: Instant, range: DateRange, now: Instant): Boolean = when (range) {
        DateRange.AllTime -> true
        DateRange.Today -> (now - ts) < 1.days
        DateRange.Last7Days -> (now - ts) < 7.days
        DateRange.Last30Days -> (now - ts) < 30.days
    }

    private fun matchesSearch(entry: JournalEntry, name: String?, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase()
        if (name?.lowercase()?.contains(q) == true) return true
        val haystack = when (val a = entry.action) {
            is ActionRecord.Move -> "${a.pathBefore} ${a.pathAfter}"

            is ActionRecord.Rename -> "${a.nameBefore} ${a.nameAfter}"

            is ActionRecord.SetTags -> "${a.before.joinToString(" ")} ${a.after.joinToString(" ")}"

            is ActionRecord.ForceTakeLock -> a.priorOwnerHostName.orEmpty()

            is ActionRecord.MissingSampleMapped -> "${a.missingPath} ${a.candidatePath}"

            is ActionRecord.MissingSampleUnmapped -> "${a.missingPath} ${a.candidatePath}"

            is ActionRecord.SnapshotRelabeled -> "${a.labelBefore.orEmpty()} ${a.labelAfter.orEmpty()}"

            is ActionRecord.StageOverridden -> "${a.stageBefore.orEmpty()} ${a.stageAfter.orEmpty()}"

            is ActionRecord.Archive,
            is ActionRecord.PushConflict,
            is ActionRecord.MacPathRepaired,
            is ActionRecord.MacPathRestored,
            -> ""
        }
        return haystack.lowercase().contains(q)
    }

    @Immutable
    data class JournalRow(
        val entry: JournalEntry,
        val projectName: String,
    )

    @Immutable
    data class DayGroup(
        val label: String,
        val rows: List<JournalRow>,
    )

    @Immutable
    data class State(
        val rows: List<JournalRow> = emptyList(),
        val days: List<DayGroup> = emptyList(),
        val invertibleEntries: List<JournalEntry> = emptyList(),
        val isNarrowed: Boolean = false,
        val actionTypeFilter: ActionTypeFilter = ActionTypeFilter.All,
        val search: String = "",
        val dateRange: DateRange = DateRange.AllTime,
        val loading: Boolean = false,
    )

    enum class ActionTypeFilter { All, Move, Rename, Archive, Tag, Lock, Conflict }

    enum class DateRange { Today, Last7Days, Last30Days, AllTime }

    private data class Filters(
        val actionType: ActionTypeFilter = ActionTypeFilter.All,
        val search: String = "",
        val dateRange: DateRange = DateRange.AllTime,
    )

    sealed interface Intent {
        data class OpenProject(val projectId: ProjectId) : Intent
        data class SetActionTypeFilter(val filter: ActionTypeFilter) : Intent
        data class SetSearch(val query: String) : Intent
        data class SetDateRange(val range: DateRange) : Intent
        data class UndoOne(val entry: JournalEntry) : Intent
        data class BulkUndo(val entries: List<JournalEntry>) : Intent
    }

    sealed interface Effect {
        data class NavigateToProject(val projectId: ProjectId) : Effect
        data class Undone(val entry: JournalEntry) : Effect
        data class UndoFailed(val entry: JournalEntry, val reason: String) : Effect
        data class BulkUndone(
            val successes: List<JournalEntry>,
            val failures: List<Pair<JournalEntry, String>>,
        ) : Effect
    }

    private companion object {
        const val LIMIT = 200
    }
}

/**
 * Convenience for the screen: which action variants have an inverse mutator. `ForceTakeLock`,
 * `PushConflict`, and the .als-related entries are informational / handled elsewhere — there's
 * no sensible generic undo, so the UI hides the bulk-undo button for those rows.
 */
val JournalViewModel.JournalRow.isInvertible: Boolean
    get() = when (entry.action) {
        is ActionRecord.Move,
        is ActionRecord.Rename,
        is ActionRecord.Archive,
        is ActionRecord.SetTags,
        is ActionRecord.MacPathRepaired,
        -> true

        else -> false
    }
