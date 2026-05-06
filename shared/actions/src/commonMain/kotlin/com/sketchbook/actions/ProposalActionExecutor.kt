package com.sketchbook.actions

import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProposalAction
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Apply a list of approved [ProposalAction]s to the catalog. Today we route two action types
 * — `ArchiveProject` and `SetTags` — directly to [ProjectRepository]; richer actions land
 * alongside their repository counterparts as MCP grows. Unknown action types fail the whole
 * batch (no partial commits) so the journal stays consistent with what the user approved.
 *
 * Stops on the first failure; the caller (ProposalsStateHolder) reports the message back as a
 * `Failed` effect rather than recording an Approved status.
 */
@SingleIn(AppScope::class)
@Inject
class ProposalActionExecutor(private val projects: ProjectRepository) {

    suspend fun apply(actions: List<ProposalAction>): Result<Unit> {
        for (a in actions) {
            val r: Result<*> = when (a.type) {
                "ArchiveProject" -> projects.archive(
                    id = ProjectId(a.args.projectIdLong()),
                    archived = true,
                )

                "SetTags" -> {
                    val id = ProjectId(a.args.projectIdLong())
                    val tags = (a.args["tags"] as? JsonArray)
                        ?.map { it.jsonPrimitive.content }
                        ?: emptyList()
                    projects.setTags(id, tags)
                }

                else -> Result.failure<Unit>(
                    IllegalArgumentException("unknown proposal action ${a.type}"),
                )
            }
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        }
        return Result.success(Unit)
    }

    private fun JsonObject.projectIdLong(): Long = this["project_id"]?.jsonPrimitive?.long
        ?: error("ProposalAction.args missing project_id")
}
