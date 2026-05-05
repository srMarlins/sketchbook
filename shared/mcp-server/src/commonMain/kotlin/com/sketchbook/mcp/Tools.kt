package com.sketchbook.mcp

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool surface exposed over MCP. Mirrors the v0.1 Python tool set so AI prompts written against
 * the previous server keep working. Each tool has a stable `name`, a JSON Schema for arguments,
 * and a coroutine that produces a JSON-serializable result.
 *
 * Side effects are split:
 *  - Reads go through [ProjectRepository] (Flow .first() at call time).
 *  - Writes go through [ProposalsWriter] which lands a JSON payload under `data/proposals/`
 *    matching the v0.1 wire format byte-for-byte (`proposal_id`, `actor`, `actions`,
 *    `rationale`).
 */
class Tools(
    private val repository: ProjectRepository,
    private val proposalsWriter: ProposalsWriter,
) {

    suspend fun searchProjects(args: SearchProjectsArgs): SearchResult {
        val rows = repository.observeProjects(args.query.orEmpty()).first()
        val limited = rows.take(args.limit ?: DEFAULT_LIMIT)
        return SearchResult(matches = limited.map { it.toRow() })
    }

    suspend fun getProject(args: GetProjectArgs): ProjectDetail? {
        val row = repository.observeProject(ProjectId(args.projectId)).first() ?: return null
        return ProjectDetail(row = row.toRow())
    }

    suspend fun listRecent(args: ListRecentArgs): SearchResult {
        val rows = repository.observeProjects("").first()
        val limited = rows.take(args.limit ?: DEFAULT_LIMIT)
        return SearchResult(matches = limited.map { it.toRow() })
    }

    suspend fun proposeBatch(args: ProposeBatchArgs): ProposeBatchResult {
        val proposalId = proposalsWriter.write(actions = args.actions, rationale = args.rationale)
        return ProposeBatchResult(proposalId = proposalId)
    }

    private fun ProjectRow.toRow(): ProjectRowDto = ProjectRowDto(
        projectId = id.value,
        name = name,
        path = path.value,
        tempo = tempo,
        trackCount = trackCount,
        liveVersion = lastSavedLiveVersion,
        updatedAt = updatedAt.toString(),
        tags = tags,
        colorTag = colorTag,
    )

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}

@Serializable
data class SearchProjectsArgs(
    val query: String? = null,
    val limit: Int? = null,
)

@Serializable
data class GetProjectArgs(
    @SerialName("project_id") val projectId: Long,
)

@Serializable
data class ListRecentArgs(
    val limit: Int? = null,
)

@Serializable
data class ProposeBatchArgs(
    val actions: List<ProposedAction>,
    val rationale: String? = null,
)

/**
 * Serialized exactly like the v0.1 web/CLI proposal: `{"type": "...", "args": {...}}`. Args is
 * left as a free-form JSON object so the validator (lives elsewhere) can enforce the per-action
 * schema in one place.
 */
@Serializable
data class ProposedAction(
    val type: String,
    val args: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class SearchResult(val matches: List<ProjectRowDto>)

@Serializable
data class ProjectDetail(val row: ProjectRowDto)

@Serializable
data class ProjectRowDto(
    @SerialName("project_id") val projectId: Long,
    val name: String,
    val path: String,
    val tempo: Double? = null,
    @SerialName("track_count") val trackCount: Int,
    @SerialName("live_version") val liveVersion: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    val tags: List<String>,
    @SerialName("color_tag") val colorTag: Int? = null,
)

@Serializable
data class ProposeBatchResult(
    @SerialName("proposal_id") val proposalId: String,
)

/**
 * Implementations write the proposal JSON under `data/proposals/<id>.json` and return the id.
 * Format mirrors v0.1 exactly: pretty-printed, two-space indent, fields ordered
 * `proposal_id, actor, actions, rationale`.
 */
interface ProposalsWriter {
    suspend fun write(actions: List<ProposedAction>, rationale: String?): String
}
