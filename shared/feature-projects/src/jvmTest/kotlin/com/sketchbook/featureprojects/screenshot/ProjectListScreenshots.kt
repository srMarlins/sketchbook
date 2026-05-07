package com.sketchbook.featureprojects.screenshot

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.sketchbook.core.ParseStatus
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.Stage
import com.sketchbook.featureprojects.ProjectListContent
import com.sketchbook.featureprojects.ProjectListViewModel
import com.sketchbook.featureprojects.bucketize
import com.sketchbook.featureprojects.deriveProjectGroups
import com.sketchbook.uishared.theme.AppTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ProjectListScreenshots {
    @Test
    fun loaded_state() = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent {
            AppTheme {
                ProjectListContent(
                    state = sampleLoadedState(),
                    dispatch = {},
                )
            }
        }
        onRoot().captureRoboImage("build/roborazzi/project_list_loaded.png")
    }
}

private fun sampleLoadedState(): ProjectListViewModel.State {
    val rows = sampleRows()
    val groups = deriveProjectGroups(rows)
    val buckets = bucketize(groups)
    return ProjectListViewModel.State(
        query = "",
        rows = rows,
        archivedRows = emptyList(),
        groups = groups,
        buckets = buckets,
        gemsView = buckets.forgottenGems,
        searchResults = emptyList(),
        zoomShelf = null,
        openDetailId = null,
        distinctKeys = listOf("C Major", "D Minor", "F# Major", "A Minor", "G Major"),
        loading = false,
    )
}

private fun row(
    id: Long,
    name: String,
    folder: String,
    tempo: Double?,
    key: String?,
    colorTag: Int?,
    stageInferred: Stage?,
    effortScore: Int?,
    daysAgo: Long,
    trackCount: Int = 8,
    tags: List<String> = emptyList(),
    missingSampleCount: Int = 0,
    parseStatus: ParseStatus = ParseStatus.Ok,
): ProjectRow = ProjectRow(
    id = ProjectId(id),
    name = name,
    path = ProjectPath("Users/srm/Music/Ableton/$folder/$name.als"),
    tempo = tempo,
    trackCount = trackCount,
    lastSavedLiveVersion = "11.3.20",
    updatedAt = Instant.fromEpochMilliseconds(NOW_MS - daysAgo * DAY_MS),
    tags = tags,
    colorTag = colorTag,
    effortScore = effortScore,
    parseStatus = parseStatus,
    missingSampleCount = missingSampleCount,
    fileSizeBytes = 12_000_000L,
    archived = false,
    key = key,
    stageInferred = stageInferred,
)

// Frozen "now" so the screenshot has stable relative timestamps each run.
private val NOW_MS = Instant.parse("2026-05-07T12:00:00Z").toEpochMilliseconds()
private const val DAY_MS = 24L * 60 * 60 * 1000

// Six-ish projects covering the visible shelves: blue (currently working),
// warm orange (almost done), purple (has potential), no-tag (untriaged),
// plus one high-effort old project to populate the Forgotten gems shelf.
private fun sampleRows(): List<ProjectRow> = listOf(
    row(
        id = 1,
        name = "midnight bloom",
        folder = "Active/midnight bloom",
        tempo = 124.0,
        key = "F# Minor",
        colorTag = 10, // blue → currently working
        stageInferred = Stage.InProgress,
        effortScore = 72,
        daysAgo = 2,
        tags = listOf("synthwave", "draft"),
    ),
    row(
        id = 2,
        name = "paper lanterns",
        folder = "Active/paper lanterns",
        tempo = 96.5,
        key = "C Major",
        colorTag = 2, // warm → almost done
        stageInferred = Stage.Mixing,
        effortScore = 84,
        daysAgo = 5,
        tags = listOf("ambient"),
    ),
    row(
        id = 3,
        name = "harbour",
        folder = "Active/harbour",
        tempo = 140.0,
        key = "A Minor",
        colorTag = 12, // purple → has potential
        stageInferred = Stage.Sketch,
        effortScore = 48,
        daysAgo = 11,
    ),
    row(
        id = 4,
        name = "untitled jam 03",
        folder = "Sketches/untitled jam 03",
        tempo = 110.0,
        key = null,
        colorTag = null, // untriaged
        stageInferred = null,
        effortScore = 18,
        daysAgo = 30,
    ),
    row(
        id = 5,
        name = "saudade",
        folder = "Forgotten/saudade",
        tempo = 88.0,
        key = "D Minor",
        colorTag = 12,
        stageInferred = Stage.Stuck,
        effortScore = 78, // ≥65 + old → forgotten gem
        daysAgo = 900,
        tags = listOf("vocal", "ballad"),
    ),
    row(
        id = 6,
        name = "static garden",
        folder = "Active/static garden",
        tempo = 128.0,
        key = "G Major",
        colorTag = 10,
        stageInferred = Stage.InProgress,
        effortScore = 65,
        daysAgo = 1,
        trackCount = 14,
    ),
    row(
        id = 7,
        name = "old porch",
        folder = "Forgotten/old porch",
        tempo = 72.0,
        key = "C Major",
        colorTag = null,
        stageInferred = Stage.Sketch,
        effortScore = 70,
        daysAgo = 1200,
        missingSampleCount = 2,
    ),
)
