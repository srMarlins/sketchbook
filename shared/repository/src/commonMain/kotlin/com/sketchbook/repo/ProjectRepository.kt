package com.sketchbook.repo

import com.sketchbook.core.PluginRef
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.SampleRef
import com.sketchbook.core.SketchbookError
import kotlinx.coroutines.flow.Flow

/**
 * The single seam between catalog data and feature modules. Features and the sync engine both
 * write through here; nothing reaches past this interface into SQLDelight types.
 *
 * **Read API** — Flow-based, hot. Repository owns the dispatcher choice; callers never
 * `flowOn(Dispatchers.IO)` themselves.
 *
 * **Write API** — `suspend` + `Result<JournalEntry>`. Domain-level errors are
 * [SketchbookError] subclasses wrapped via `Result.failure`; unexpected exceptions propagate.
 */
interface ProjectRepository {

    /**
     * Live list of non-archived projects matching [query]. Empty query returns everything,
     * ordered by `last_modified DESC`. Non-empty query goes through FTS5 (`MATCH`).
     */
    fun observeProjects(query: String = ""): Flow<List<ProjectRow>>

    /** Live list of archived projects, newest first. Excluded from [observeProjects]. */
    fun observeArchivedProjects(): Flow<List<ProjectRow>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Live single-project view by local PK. Emits new state whenever the row mutates. */
    fun observeProject(id: ProjectId): Flow<ProjectRow?>

    /**
     * Distinct, non-null `key` values across the catalog, sorted alphabetically. Drives the
     * Browse toolbar's Key filter chip popup — only surfaces keys actually present in the
     * user's library. Empty list when no parsed projects yet have a key.
     */
    fun observeDistinctKeys(): Flow<List<String>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Plugins extracted by the parser for [id]. Empty when the project hasn't parsed yet. */
    fun observePlugins(id: ProjectId): Flow<List<PluginRef>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * Inverse plugin lookup: "what other projects also use this plugin?" Drives the always-on
     * pivot popover on the Plugins tab.
     *
     * - [pluginName] — exact match against `project_plugins.plugin_name`.
     * - [format] — when null, matches any format the user has the plugin loaded under (the user
     *   thinks of "Serum" as one thing even if scattered VST2/VST3/AU). When set, narrows to
     *   that exact format.
     * - [excludeProjectId] — optional. Excludes the project the user is currently viewing so the
     *   popover lists only *other* consumers (otherwise the list always has at least one row
     *   which is just noise).
     *
     * Results are non-archived projects, distinct (a project loading the same plugin on three
     * tracks is one row), ordered by `last_modified DESC`.
     */
    fun observeProjectsUsing(
        pluginName: String,
        format: com.sketchbook.core.PluginFormat? = null,
        excludeProjectId: ProjectId? = null,
    ): Flow<List<PluginUsage>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Sample refs the parser found, with their resolved missing/found status. */
    fun observeSamples(id: ProjectId): Flow<List<SampleEntry>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * PR-BB: composite library health for the sidebar chip. Flow re-emits when the underlying
     * tables (`projects`, `sync_state`, `project_samples`) change, so the chip updates without
     * a manual refresh after archive/scan/sync mutations.
     */
    fun observeLibraryHealth(): Flow<LibraryHealth> = kotlinx.coroutines.flow.flowOf(LibraryHealth.EMPTY)

    /**
     * PR-T: per-(name, type) breakdown of plugins the user's filesystem is missing. Used by the
     * Home coverage chip popup. Empty list when nothing is missing — caller hides the chip in
     * that case.
     */
    fun observeMissingPluginCoverage(): Flow<List<MissingPluginRow>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * PR-T: scalar summary that drives the chip's label ("N plugins missing affecting M projects").
     * `null` while loading — caller renders nothing for that initial frame so a flash of "0
     * missing" doesn't appear before the SQL lands.
     */
    fun observeMissingPluginSummary(): Flow<MissingPluginSummary?> =
        kotlinx.coroutines.flow.flowOf(null)

    /** Move the project's working tree to a new parent directory. Path-rename only; no FS I/O. */
    suspend fun move(id: ProjectId, newParentDir: String): Result<JournalEntry>

    /** Rename the project (catalog-level — folder rename is the caller's job). */
    suspend fun rename(id: ProjectId, newName: String): Result<JournalEntry>

    /** Toggle archived. Same call archives or unarchives based on current state. */
    suspend fun archive(id: ProjectId, archived: Boolean = true): Result<JournalEntry>

    /** Replace the project's tag set (creates missing tags as a side effect). */
    suspend fun setTags(id: ProjectId, tags: List<String>): Result<JournalEntry>

    /**
     * PR-R: set or clear the per-project stage override. `null` clears it (chip falls back to the
     * inferred stage). Writes a `StageOverridden` journal entry; the entry carries the inferred
     * stage at the time of the override so the audit log can reconstruct user intent (e.g. "user
     * promoted Mixing → Done" vs "user re-tagged null → Sketch").
     */
    suspend fun setStageOverride(
        id: ProjectId,
        stage: com.sketchbook.core.Stage?,
    ): Result<JournalEntry> = Result.failure(
        com.sketchbook.core.SketchbookError.NotFound("setStageOverride not implemented"),
    )
}

/**
 * Per-project sample entry as the UI sees it. The parser writes raw paths plus a missing flag
 * to `project_samples`; this is a typed view over those rows. Distinct from [SampleRef] so the
 * domain `SampleRef` (which lives on `ProjectMetadata`) doesn't have to carry resolution state.
 */
data class SampleEntry(
    val rawPath: String,
    val isMissing: Boolean,
    val sizeBytes: Long?,
) {
    /** Last path component — what the user sees in the Samples tab. */
    val displayName: String
        get() = rawPath.substringAfterLast('/').substringAfterLast('\\').ifEmpty { rawPath }
}

/**
 * Lightweight projection of a project that consumes a particular plugin. Carries just enough to
 * render a row in the pivot popover (name + path + last-modified for the relative-time label) and
 * navigate on click. Distinct from [com.sketchbook.core.ProjectRow] because the popover doesn't
 * need tags / sample counts / sync state — keeping the row narrow keeps the inverse query fast.
 */
data class PluginUsage(
    val id: ProjectId,
    val name: String,
    val path: String,
    /** Epoch seconds (matches the `projects.last_modified` REAL column). */
    val lastModified: Double,
)

/**
 * PR-BB: composite library-health snapshot used by the sidebar chip + popup.
 *
 * Today only `synced` and `sampleClean` are computed. `pluginInstalled` and `stageNotStuck` are
 * reserved for PR-T (plugin `is_installed` column) and PR-R (`stage_inferred` column) respectively;
 * those PRs add the matching `SUM(CASE…)` clauses to `selectLibraryHealth` and flip these fields
 * from `null` to a real count. The popup renders one row per non-null signal — `null` means
 * "data not available yet" and the row is suppressed, not shown as 0%.
 */
data class LibraryHealth(
    /** Active (non-archived) projects in the catalog. The denominator for every percentage. */
    val total: Int,
    /** Projects whose sync_state row is non-dirty AND `local_rev == cloud_head_rev`. */
    val synced: Int,
    /** Projects with zero `is_missing=1` rows in `project_samples`. */
    val sampleClean: Int,
    /** PR-T placeholder. */
    val pluginInstalled: Int? = null,
    /** PR-R placeholder. */
    val stageNotStuck: Int? = null,
) {
    /** Composite score: average of every non-null signal, in [0..1]. Zero when no projects. */
    val compositePercent: Float
        get() {
            if (total <= 0) return 0f
            val ratios = buildList<Float> {
                add(synced.toFloat() / total)
                add(sampleClean.toFloat() / total)
                pluginInstalled?.let { add(it.toFloat() / total) }
                stageNotStuck?.let { add(it.toFloat() / total) }
            }
            return ratios.average().toFloat()
        }

    companion object {
        val EMPTY = LibraryHealth(total = 0, synced = 0, sampleClean = 0)
    }
}

/**
 * PR-T: one row in the missing-plugin coverage list. The chip's popup renders one [MissingPluginRow]
 * per row. `name` + `format` together identify the plugin slot; the user thinks of "Serum (VST3)"
 * differently from "Serum (VST2)" because Live preserves the format pin and won't auto-substitute.
 * `affectedProjects` is the count of distinct non-archived projects that load this plugin.
 */
data class MissingPluginRow(
    val name: String,
    val format: com.sketchbook.core.PluginFormat,
    val affectedProjects: Int,
)

/**
 * PR-T: scalar summary for the Home chip. The chip hides when both counts are zero (no missing
 * plugins, no need to nag the user). Counted as compound (name|type) so the same plugin missing
 * in two formats counts as two — mirrors the popup list.
 */
data class MissingPluginSummary(
    val missingPluginCount: Int,
    val affectedProjects: Int,
) {
    val isEmpty: Boolean get() = missingPluginCount == 0 && affectedProjects == 0
}

/** Convenience: report a [SketchbookError] as a `Result.failure`. */
internal fun <T> failed(error: SketchbookError): Result<T> = Result.failure(error)
