package com.sketchbook.featurejournal.format

import com.sketchbook.repo.ActionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalActionTest {

    @Test fun moveShowsBeforeAndAfterDirs() {
        val a = ActionRecord.Move(
            pathBefore = "/lib/old/foo.als",
            pathAfter = "/lib/new/foo.als",
        )
        assertEquals("Moved foo.als — /lib/old/ → /lib/new/", humanReadable(a))
    }

    @Test fun renameUsesArrow() {
        assertEquals("Renamed wip → wip-v2", humanReadable(ActionRecord.Rename("wip", "wip-v2")))
    }

    @Test fun missingSampleMappedAppendsBusyOutcome() {
        val a = ActionRecord.MissingSampleMapped(
            missingPath = "/old/k.wav",
            candidatePath = "/lib/Drums/k.wav",
            alsOutcome = "SkippedBusy",
        )
        val text = humanReadable(a)
        assertTrue("text='$text'") { text.startsWith("Relink k.wav → /lib/Drums/") }
        assertTrue("text='$text'") { text.endsWith(".als open in Live — skipped)") }
    }

    @Test fun macPathRepairedShowsMappingCount() {
        val a = ActionRecord.MacPathRepaired(mappingCount = 7, alsOutcome = "Patched")
        assertEquals("Repair Mac paths (7)", humanReadable(a))
    }

    @Test fun snapshotRelabeledShowsBeforeAfter() {
        val a = ActionRecord.SnapshotRelabeled(
            rev = 12L,
            labelBefore = "wip",
            labelAfter = "ship it",
            kindBefore = "auto",
        )
        assertEquals("Relabel snapshot wip → ship it", humanReadable(a))
    }

    @Test fun snapshotRelabeledHandlesBlankLabels() {
        val a = ActionRecord.SnapshotRelabeled(
            rev = 12L,
            labelBefore = null,
            labelAfter = "",
            kindBefore = "auto",
        )
        assertEquals("Relabel snapshot (unlabeled) → (unlabeled)", humanReadable(a))
    }
}
