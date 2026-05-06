package com.sketchbook.featureproposals.format

import com.sketchbook.repo.ProposalAction
import com.sketchbook.uishared.components.VerbTint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ProposalLabelTest {

    private fun args(vararg pairs: Pair<String, JsonPrimitive>): JsonObject = JsonObject(pairs.toMap())

    @Test fun archiveResolvesProjectName() {
        val a = ProposalAction(
            "ArchiveProject",
            args("project_id" to JsonPrimitive(42L)),
        )
        val l = proposalLabel(a, mapOf(42L to "Old Sketch"))
        assertEquals("Archive", l.verb)
        assertEquals("Old Sketch", l.target)
        assertEquals(VerbTint.Remove, l.tintHint)
    }

    @Test fun archiveFallsBackToIdLabel() {
        val a = ProposalAction("ArchiveProject", args("project_id" to JsonPrimitive(42L)))
        val l = proposalLabel(a)
        assertEquals("project #42", l.target)
    }

    @Test fun renameSplitsFromAndTo() {
        val a = ProposalAction(
            "RenameProject",
            JsonObject(
                mapOf(
                    "project_id" to JsonPrimitive(7L),
                    "from_" to JsonPrimitive("/lib/foo/foo.als"),
                    "to" to JsonPrimitive("/lib/foo/bar.als"),
                ),
            ),
        )
        val l = proposalLabel(a)
        assertEquals("Rename", l.verb)
        assertEquals("foo.als", l.target)
        assertEquals("→ bar.als", l.detail)
    }

    @Test fun setTagsWithAfterArrayShowsTagList() {
        val a = ProposalAction(
            "SetTags",
            JsonObject(
                mapOf(
                    "project_id" to JsonPrimitive(1L),
                    "after" to JsonArray(listOf(JsonPrimitive("techno"), JsonPrimitive("wip"))),
                ),
            ),
        )
        val l = proposalLabel(a, mapOf(1L to "Foo"))
        assertEquals("Tag", l.verb)
        assertEquals("Foo", l.target)
        assertEquals("techno, wip", l.detail)
        assertEquals(VerbTint.Add, l.tintHint)
    }

    @Test fun setTagsWithEmptyAfterShowsClear() {
        val a = ProposalAction(
            "SetTags",
            JsonObject(
                mapOf(
                    "project_id" to JsonPrimitive(1L),
                    "after" to JsonArray(emptyList()),
                ),
            ),
        )
        val l = proposalLabel(a, mapOf(1L to "Foo"))
        assertEquals("Clear tags", l.verb)
        assertEquals(VerbTint.Remove, l.tintHint)
    }

    @Test fun unknownTypeUsesNeutralAndKeepsType() {
        val a = ProposalAction("Custom", args("x" to JsonPrimitive("y")))
        val l = proposalLabel(a)
        assertEquals("Custom", l.verb)
        assertEquals("", l.target)
        assertEquals(VerbTint.Neutral, l.tintHint)
    }
}
