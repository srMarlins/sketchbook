package com.sketchbook.sync

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class CoalesceJobTest {

    private val uuid = ProjectUuid("01H-coalesce")
    private val t0 = Instant.parse("2026-05-05T12:00:00Z")

    private fun snap(rev: Long, kind: SnapshotKind, ts: Instant): Snapshot = Snapshot(
        projectUuid = uuid,
        rev = SnapshotRev(rev),
        parentRev = if (rev > 1) SnapshotRev(rev - 1) else null,
        timestamp = ts,
        hostId = "host-a",
        hostName = "DesktopA",
        kind = kind,
        label = if (kind == SnapshotKind.Named) "checkpoint" else null,
        selfContained = false,
        fileCount = 0,
        totalBytes = 0,
    )

    @Test
    fun promotesAutoOnIdleProjectWithNoNamedYet() {
        val job = CoalesceJob()
        val history = listOf(
            snap(1, SnapshotKind.Auto, t0),
            snap(2, SnapshotKind.Auto, t0 + 5.minutes),
        )
        // Now is 20 min after the last save → idle threshold (10m) passed.
        val promotion = job.evaluate(uuid, history, now = t0 + 25.minutes)
        assertEquals(SnapshotRev(2), promotion?.rev)
        assertEquals(uuid, promotion?.uuid)
    }

    @Test
    fun skipsWhenNotIdleEnough() {
        val job = CoalesceJob()
        val history = listOf(snap(1, SnapshotKind.Auto, t0))
        val promotion = job.evaluate(uuid, history, now = t0 + 2.minutes)
        assertNull(promotion)
    }

    @Test
    fun skipsWhenLastNamedIsRecent() {
        val job = CoalesceJob()
        val history = listOf(
            snap(1, SnapshotKind.Auto, t0),
            snap(2, SnapshotKind.Named, t0 + 5.minutes),
            snap(3, SnapshotKind.Auto, t0 + 10.minutes),
        )
        // Newest snapshot (rev 3) was 15 min ago; idle. But last named was 20 min ago — also
        // less than the 30-min named-gap → no promotion.
        val promotion = job.evaluate(uuid, history, now = t0 + 25.minutes)
        assertNull(promotion)
    }

    @Test
    fun skipsWhenAllNewAutosArePriorToNewestNamed() {
        val job = CoalesceJob()
        val history = listOf(
            snap(1, SnapshotKind.Auto, t0),
            snap(2, SnapshotKind.Named, t0 + 5.minutes),
        )
        val promotion = job.evaluate(uuid, history, now = t0 + 60.minutes)
        assertNull(promotion)
    }
}
