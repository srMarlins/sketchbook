package com.sketchbook.featuredetail.screenshot

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.sketchbook.core.PluginFormat
import com.sketchbook.core.PluginRef
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.Stage
import com.sketchbook.featuredetail.ProjectDetailContent
import com.sketchbook.featuredetail.ProjectDetailViewModel
import com.sketchbook.repo.LockStatus
import com.sketchbook.repo.SampleEntry
import com.sketchbook.uishared.theme.AppTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ProjectDetailScreenshots {
    @Test
    fun loaded_state() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                AppTheme {
                    ProjectDetailContent(
                        state = sampleLoadedState(),
                        dispatch = {},
                    )
                }
            }
            onRoot().captureRoboImage("build/roborazzi/project_detail_loaded.png")
        }
}

private fun sampleLoadedState(): ProjectDetailViewModel.State =
    ProjectDetailViewModel.State(
        row =
            ProjectRow(
                id = ProjectId(42),
                name = "midnight bloom",
                path = ProjectPath("Users/srm/Music/Ableton/Active/midnight bloom/midnight bloom.als"),
                tempo = 124.0,
                trackCount = 18,
                lastSavedLiveVersion = "11.3.20",
                updatedAt = Instant.parse("2026-05-05T12:00:00Z"),
                tags = listOf("synthwave", "draft", "vocal-needed"),
                colorTag = 10,
                effortScore = 72,
                missingSampleCount = 0,
                fileSizeBytes = 18_400_000L,
                archived = false,
                key = "F# Minor",
                stageInferred = Stage.InProgress,
            ),
        history = emptyList(),
        tab = ProjectDetailViewModel.Tab.Overview,
        loading = false,
        lockStatus = LockStatus.Free,
        plugins =
            listOf(
                PluginRef("Serum", PluginFormat.Vst3, "Lead"),
                PluginRef("OTT", PluginFormat.Vst2, "Bus"),
                PluginRef("Operator", PluginFormat.AbletonNative, "Pad"),
            ),
        samples =
            listOf(
                SampleEntry("Samples/Imported/kick.wav", isMissing = false, sizeBytes = 102_400),
                SampleEntry("Samples/Imported/snare.wav", isMissing = false, sizeBytes = 88_200),
                SampleEntry("Samples/Imported/hat.wav", isMissing = true, sizeBytes = null),
            ),
    )
