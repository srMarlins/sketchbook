package com.sketchbook.featureprojects

import com.sketchbook.core.ParseStatus
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ToSongStripDataTest {

    private val now = Instant.parse("2026-05-05T12:00:00Z")
    private fun row(
        id: Long,
        name: String,
        stageInferred: com.sketchbook.core.Stage? = null,
        stageOverride: com.sketchbook.core.Stage? = null,
    ) = ProjectRow(
        id = ProjectId(id),
        name = name,
        path = ProjectPath("Projects/2026/Song/$name"),
        tempo = 120.0,
        trackCount = 4,
        lastSavedLiveVersion = "11.3.20",
        updatedAt = now,
        tags = emptyList(),
        colorTag = null,
        stageInferred = stageInferred,
        stageOverride = stageOverride,
    )

    private fun group(rep: ProjectRow, variants: List<ProjectRow>) = ProjectGroup(
        id = "Projects/2026/Song",
        representative = rep,
        variants = variants,
        effortScore = null,
        updatedAtMs = now.toEpochMilliseconds(),
        parseStatusBest = ParseStatus.Ok,
        missingSampleCount = 0,
    )

    @Test
    fun singletonHasBareNameAndVariantCountOne() {
        val r = row(1, "kick.als")
        val data = group(r, listOf(r)).toSongStripDataForTest(sync = null)
        assertEquals("kick.als", data.name)
        assertEquals(1, data.variantCount)
    }

    @Test
    fun chipDisplaysOverrideOverInferredStage() {
        // PR-R: the strip's `stage` field follows ProjectRow.stage, which is override > inferred.
        val r = row(
            1,
            "kick.als",
            stageInferred = com.sketchbook.core.Stage.Sketch,
            stageOverride = com.sketchbook.core.Stage.Mixing,
        )
        val data = group(r, listOf(r)).toSongStripDataForTest(sync = null)
        assertEquals(com.sketchbook.uishared.components.SongStripStage.Mixing, data.stage)
    }

    @Test
    fun chipFallsBackToInferredStageWhenOverrideIsNull() {
        val r = row(1, "kick.als", stageInferred = com.sketchbook.core.Stage.Stuck)
        val data = group(r, listOf(r)).toSongStripDataForTest(sync = null)
        assertEquals(com.sketchbook.uishared.components.SongStripStage.Stuck, data.stage)
    }

    @Test
    fun chipIsNullWhenNeitherInferredNorOverrideSet() {
        val r = row(1, "kick.als")
        val data = group(r, listOf(r)).toSongStripDataForTest(sync = null)
        assertEquals(null, data.stage)
    }

    @Test
    fun multiVariantGroupKeepsBareRepresentativeNameAndExposesCount() {
        val rep = row(1, "Track v3.als")
        val v2 = row(2, "Track v2.als")
        val v1 = row(3, "Track v1.als")
        val data = group(rep, listOf(rep, v2, v1)).toSongStripDataForTest(sync = null)
        assertEquals("Track v3.als", data.name)
        assertEquals(3, data.variantCount)
    }
}
