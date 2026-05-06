package com.sketchbook.featureproposals.format

import com.sketchbook.repo.ProposalAction
import com.sketchbook.uishared.components.VerbTint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Structured label for a proposal action — splits the verb from the target identifier so the
 * row UI can render the verb as a colored [com.sketchbook.uishared.components.VerbPill] and the
 * target as bold body. Falls back to the unknown-type case with `verb = action.type` and a
 * blank target.
 */
data class ProposalLabel(
    val verb: String,
    val target: String,
    val detail: String? = null,
    val tintHint: VerbTint = VerbTint.Neutral,
)

/**
 * Wire-format types match `ProposalActionExecutor`'s switch — PascalCase `@SerialName` values
 * (`ArchiveProject`, `SetTags`, etc.). Unknown types fall through to `Neutral` so we never lose
 * the action in the UI even if the executor adds a new type before the formatter does.
 *
 * `projectNameById` resolves `args["project_id"]` to a friendly name; pass an empty map and we
 * fall back to "project #ID" so the formatter is safe to call without the catalog.
 */
fun proposalLabel(
    action: ProposalAction,
    projectNameById: Map<Long, String> = emptyMap(),
): ProposalLabel {
    fun s(key: String): String? =
        (action.args[key] as? JsonPrimitive)?.contentOrNull
    val pidLong = s("project_id")?.toLongOrNull()
    val resolvedName = pidLong?.let { projectNameById[it] }
    // Fallback chain: catalog lookup → JSON `name` → JSON `path` basename → "project #ID" →
    // "project". Proposals from `SqlProposalsRepository` carry `name` and `path` in args, so the
    // row reads as the project name even before the projects flow has populated the names map.
    val nameFromArgs = s("name")?.takeIf { it.isNotBlank() }
    val basenameFromArgs = s("path")?.let { filenameOf(it) }?.takeIf { it.isNotBlank() }
    val projectLabel = resolvedName
        ?: nameFromArgs
        ?: basenameFromArgs
        ?: pidLong?.let { "project #$it" }
        ?: "project"
    return when (action.type) {
        "MoveProject" -> {
            val to = s("to").orEmpty()
            ProposalLabel(
                verb = "Move",
                target = projectLabel,
                detail = "→ ${parentDirOf(to)}/",
                tintHint = VerbTint.Action,
            )
        }
        "RenameProject" -> {
            val from = s("from_") ?: s("from").orEmpty()
            val to = s("to").orEmpty()
            ProposalLabel(
                verb = "Rename",
                target = filenameOf(from),
                detail = "→ ${filenameOf(to)}",
                tintHint = VerbTint.Action,
            )
        }
        "ArchiveProject" -> ProposalLabel(
            verb = "Archive",
            target = projectLabel,
            tintHint = VerbTint.Remove,
        )
        "SetTags" -> {
            val tags = (action.args["after"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?: tagsList(action)
            ProposalLabel(
                verb = if (tags.isEmpty()) "Clear tags" else "Tag",
                target = projectLabel,
                detail = if (tags.isEmpty()) null else tags.joinToString(", "),
                tintHint = if (tags.isEmpty()) VerbTint.Remove else VerbTint.Add,
            )
        }
        "SetColorTag" -> {
            val after = (action.args["after"] as? JsonPrimitive)?.contentOrNull
            ProposalLabel(
                verb = "Color",
                target = projectLabel,
                detail = after?.let { "color $it" },
                tintHint = VerbTint.Add,
            )
        }
        "Undo" -> ProposalLabel(
            verb = "Undo",
            target = "previous batch",
            tintHint = VerbTint.Action,
        )
        else -> ProposalLabel(verb = action.type, target = "", tintHint = VerbTint.Neutral)
    }
}

private fun tagsList(action: ProposalAction): List<String> {
    val raw = action.args["tags"]
    return when (raw) {
        is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
        is JsonPrimitive -> raw.contentOrNull
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        else -> emptyList()
    }
}

private fun filenameOf(path: String): String =
    path.substringAfterLast('/').ifEmpty { path }

private fun parentDirOf(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx <= 0) "" else path.substring(0, idx)
}
