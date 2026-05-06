package com.sketchbook.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v0.1 Python journal parity. Each test pins a sample fixture (copied verbatim from
 * `data/journal/` in this repo's Python tree) and asserts that:
 *
 *  1. Decoding the fixture JSON into a `JournalBatch` succeeds.
 *  2. Re-encoding produces semantically equal JSON (same fields, same values).
 *
 * Re-encoding pretty-printing isn't asserted to be byte-identical because Python's `json` and
 * kotlinx.serialization differ on whitespace edge cases; the *semantic* round-trip is what the
 * parity period needs.
 */
class JournalParityTest {

    private fun parse(raw: String) = JournalJson.pretty.decodeFromString(JournalBatch.serializer(), raw)

    private fun jsonEquals(a: String, b: String): Boolean {
        val parser = Json { ignoreUnknownKeys = false }
        return parser.parseToJsonElement(a) == parser.parseToJsonElement(b)
    }

    @Test
    fun decodesPythonRenameFixture() {
        val raw = """
            {
              "batch_id": "2026-05-04T17-58-24_2a092193",
              "actor": "user",
              "actions": [
                {
                  "type": "RenameProject",
                  "project_id": 11,
                  "from_": "Projects\\air Project",
                  "to": "Projects\\air Project [renamed-test]",
                  "hash_before": "c21c3c96780ba09e6f38e0324779794f17d8ed93a1338ee80527df61964af04a"
                }
              ]
            }
        """.trimIndent()

        val batch = parse(raw)
        assertEquals("2026-05-04T17-58-24_2a092193", batch.batch_id)
        assertEquals("user", batch.actor)
        assertEquals(1, batch.actions.size)
        val r = batch.actions[0] as ActionRecord.RenameProject
        assertEquals(11, r.projectId)
        assertEquals("Projects\\air Project", r.fromPath)
        assertEquals("Projects\\air Project [renamed-test]", r.toPath)
        assertEquals("c21c3c96780ba09e6f38e0324779794f17d8ed93a1338ee80527df61964af04a", r.hashBefore)

        // Round-trip preserves the parsed structure.
        val reEncoded = JournalJson.compact.encodeToString(JournalBatch.serializer(), batch)
        val reParsed = parse(reEncoded)
        assertEquals(batch, reParsed)
    }

    @Test
    fun decodesPythonSetTagsFixture() {
        val raw = """
            {
              "batch_id": "2026-05-04T19-50-51_9e4230da",
              "actor": "user",
              "actions": [
                {
                  "type": "SetTags",
                  "project_id": 428,
                  "before": [],
                  "after": ["smoke-test-tag"]
                }
              ]
            }
        """.trimIndent()

        val batch = parse(raw)
        val s = batch.actions[0] as ActionRecord.SetTags
        assertEquals(428, s.projectId)
        assertEquals(emptyList(), s.before)
        assertEquals(listOf("smoke-test-tag"), s.after)
    }

    @Test
    fun encodesRenameWithFromUnderscore() {
        val batch = JournalBatch(
            batch_id = "2026-05-05T15-00-00_test1234",
            actor = "user",
            actions = listOf(
                ActionRecord.RenameProject(
                    projectId = 11,
                    fromPath = "Projects/foo Project",
                    toPath = "Projects/foo Project [v2]",
                    hashBefore = "abc",
                ),
            ),
        )
        val encoded = JournalJson.compact.encodeToString(JournalBatch.serializer(), batch)
        // Wire-stable field name is `from_` (Python convention), not `fromPath`.
        assertTrue("\"from_\":" in encoded)
        assertTrue("\"to\":" in encoded)
        assertTrue("\"type\":\"RenameProject\"" in encoded)
        assertTrue("\"project_id\":11" in encoded)
    }

    @Test
    fun encodesArchiveAndMoveDiscriminators() {
        for (action in listOf(
            ActionRecord.MoveProject(projectId = 1, fromPath = "a", toPath = "b"),
            ActionRecord.ArchiveProject(projectId = 2, fromPath = "c", toPath = "c"),
            ActionRecord.SetColorTag(projectId = 3, before = null, after = 5),
            ActionRecord.Undo(projectId = 4, undidBatch = "2026-05-04T17-58-24_2a092193"),
        )) {
            val batch = JournalBatch(batch_id = "x", actor = "user", actions = listOf(action))
            val encoded = JournalJson.compact.encodeToString(JournalBatch.serializer(), batch)
            val reParsed = parse(encoded)
            assertEquals(batch, reParsed)
        }
    }
}
