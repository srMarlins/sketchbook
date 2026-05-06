package com.sketchbook.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Writes proposal JSON to `<root>/<proposalId>.json` with the v0.1 wire layout:
 * `{ "proposal_id": ..., "actor": "claude", "actions": [...], "rationale": ... }`.
 *
 * Uses pretty-printed JSON with 2-space indent so the file diffs cleanly with the Python writer's
 * output during the parity period.
 */
class FileProposalsWriter(
    private val root: Path,
    private val clock: Clock = Clock.System,
    private val randomHex: () -> String = { Random.nextLong().toULong().toString(16).padStart(16, '0').take(8) },
) : ProposalsWriter {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    override suspend fun write(actions: List<ProposedAction>, rationale: String?): String {
        Files.createDirectories(root)
        val id = newProposalId(clock.now())
        val payload = buildJsonObject {
            put("proposal_id", id)
            put("actor", "claude")
            put(
                "actions",
                buildJsonArray {
                    actions.forEach { a ->
                        add(
                            buildJsonObject {
                                put("type", a.type)
                                put("args", a.args)
                            },
                        )
                    }
                },
            )
            put("rationale", rationale?.let(::JsonPrimitive) ?: JsonNull)
        }
        val file = root.resolve("$id.json")
        file.writeText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload))
        return id
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun newProposalId(now: Instant): String {
        val ts = now.toString().replace(':', '-').substringBefore('.').removeSuffix("Z")
        return "${ts}_${randomHex()}"
    }
}
