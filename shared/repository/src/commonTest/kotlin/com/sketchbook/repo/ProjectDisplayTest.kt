package com.sketchbook.repo

import com.sketchbook.core.ProjectId
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectDisplayTest {
    @Test fun denormNameWinsOverEverything() {
        val resolved =
            resolveProjectDisplay(
                id = ProjectId(7),
                hints =
                    ProjectDisplayHints(
                        denormName = "Old Sketch",
                        pathHint = "/lib/foo/Other Name.als",
                    ),
                nameById = mapOf(ProjectId(7) to "Catalog Name"),
            )
        assertEquals("Old Sketch", resolved)
    }

    @Test fun nameByIdPicksUpWhenDenormNameIsBlank() {
        val resolved =
            resolveProjectDisplay(
                id = ProjectId(7),
                hints = ProjectDisplayHints(denormName = "   ", pathHint = "/lib/foo/path.als"),
                nameById = mapOf(ProjectId(7) to "Catalog Name"),
            )
        assertEquals("Catalog Name", resolved)
    }

    @Test fun pathHintBasenameStripsAlsWhenOtherHintsEmpty() {
        val resolved =
            resolveProjectDisplay(
                id = ProjectId(7),
                hints = ProjectDisplayHints(denormName = null, pathHint = "/lib/foo/My Track.als"),
                nameById = emptyMap(),
            )
        assertEquals("My Track", resolved)
    }

    @Test fun sentinelWhenAllHintsNullAndIdNotInMap() {
        val resolved =
            resolveProjectDisplay(
                id = ProjectId(254),
                hints = ProjectDisplayHints(),
                nameById = emptyMap(),
            )
        assertEquals("project #254", resolved)
    }
}
