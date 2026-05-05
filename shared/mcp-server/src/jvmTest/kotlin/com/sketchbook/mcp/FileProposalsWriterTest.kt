package com.sketchbook.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileProposalsWriterTest {

    private val dir = createTempDirectory("proposals-test-")
    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    private val now = Instant.parse("2026-05-05T17:23:45Z")
    private val fixedClock = object : Clock { override fun now(): Instant = now }

    @Test
    fun writesProposalWithV01Layout() = runTest {
        val writer = FileProposalsWriter(dir, clock = fixedClock, randomHex = { "abcd1234" })
        val args = listOf(
            ProposedAction(
                type = "SetTags",
                args = buildJsonObject {
                    put("project_id", 7)
                    put("tags", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("drum-loop")) })
                },
            ),
        )

        val id = writer.write(actions = args, rationale = "smoke test")

        assertEquals("2026-05-05T17-23-45_abcd1234", id)
        val file = dir.resolve("$id.json")
        assertTrue(Files.exists(file), "proposal file should exist")

        val parsed = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("2026-05-05T17-23-45_abcd1234", parsed["proposal_id"]!!.jsonPrimitive.content)
        assertEquals("claude", parsed["actor"]!!.jsonPrimitive.content)
        assertEquals("smoke test", parsed["rationale"]!!.jsonPrimitive.content)
        val firstAction = parsed["actions"]!!.jsonArray.single().jsonObject
        assertEquals("SetTags", firstAction["type"]!!.jsonPrimitive.content)
        assertEquals(7, firstAction["args"]!!.jsonObject["project_id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun nullRationaleSerializesAsJsonNull() = runTest {
        val writer = FileProposalsWriter(dir, clock = fixedClock, randomHex = { "deadbeef" })
        val id = writer.write(actions = emptyList(), rationale = null)
        val text = dir.resolve("$id.json").readText()
        assertTrue(text.contains("\"rationale\": null"), "rationale should serialize as JSON null, got: $text")
    }
}
