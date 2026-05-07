package com.sketchbook.featuretimeline.screenshot

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.featuretimeline.TimelineContent
import com.sketchbook.featuretimeline.TimelineViewModel
import com.sketchbook.uishared.theme.AppTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class TimelineScreenshots {
    @Test
    fun loaded_state() = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent {
            AppTheme {
                val state = sampleLoadedState()
                TimelineContent(
                    state = state,
                    groups = sampleGroups(state.history),
                    dispatch = {},
                )
            }
        }
        onRoot().captureRoboImage("build/roborazzi/timeline_loaded.png")
    }
}

private fun sampleLoadedState(): TimelineViewModel.State = TimelineViewModel.State(
    uuid = ProjectUuid("11111111-1111-1111-1111-111111111111"),
    history = sampleHistory(),
    showAll = false,
    loading = false,
    pendingRewind = null,
    rewindProgress = null,
)

private fun sampleGroups(history: List<Snapshot>): List<TimelineViewModel.DayGroup> {
    val zone = TimeZone.UTC
    return history
        .filter { it.kind != SnapshotKind.Auto }
        .sortedByDescending { it.rev.value }
        .groupBy { it.timestamp.toLocalDateTime(zone).date }
        .toSortedMap(compareByDescending<LocalDate> { it })
        .map { (date, snaps) -> TimelineViewModel.DayGroup(date, snaps) }
}

private fun snap(
    rev: Long,
    parentRev: Long?,
    timestamp: String,
    kind: SnapshotKind,
    label: String?,
    fileCount: Int = 24,
    totalBytes: Long = 18_400_000L,
    newBytes: Long = 1_200_000L,
    hostName: String = "studio-mac",
): Snapshot = Snapshot(
    projectUuid = ProjectUuid("11111111-1111-1111-1111-111111111111"),
    rev = SnapshotRev(rev),
    parentRev = parentRev?.let { SnapshotRev(it) },
    timestamp = Instant.parse(timestamp),
    hostId = "host-1",
    hostName = hostName,
    kind = kind,
    label = label,
    selfContained = true,
    fileCount = fileCount,
    totalBytes = totalBytes,
    newBytes = newBytes,
)

private fun sampleHistory(): List<Snapshot> = listOf(
    snap(12, 11, "2026-05-07T10:42:00Z", SnapshotKind.Named, "drum bus pass"),
    snap(11, 10, "2026-05-07T09:15:00Z", SnapshotKind.Named, "lead synth swap", newBytes = 4_800_000L),
    snap(10, 9, "2026-05-06T18:30:00Z", SnapshotKind.Branch, "alternate intro"),
    snap(9, 8, "2026-05-06T14:05:00Z", SnapshotKind.Named, null, hostName = "laptop"),
    snap(8, 7, "2026-05-05T20:55:00Z", SnapshotKind.Named, "vocal chops in"),
    snap(7, 6, "2026-05-05T16:10:00Z", SnapshotKind.Branch, "pre-mix"),
    snap(6, 5, "2026-05-04T11:00:00Z", SnapshotKind.Named, "skeleton arrangement", newBytes = 8_300_000L),
)
