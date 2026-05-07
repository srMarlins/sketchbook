package com.sketchbook.featuresettings.screenshot

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.sketchbook.auth.AuthState
import com.sketchbook.featuresettings.SettingsContent
import com.sketchbook.featuresettings.SettingsViewModel
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.ExternalKind
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.uishared.theme.AppTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SettingsScreenshots {
    @Test
    fun default_state() = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent {
            AppTheme {
                SettingsContent(
                    state = sampleConfiguredState(),
                    dispatch = {},
                    onAddRootClicked = {},
                )
            }
        }
        onRoot().captureRoboImage("build/roborazzi/settings_default.png")
    }
}

private fun sampleConfiguredState(): SettingsViewModel.State = SettingsViewModel.State(
    libraryRoots = listOf(
        LibraryRoot.Projects("/Users/srm/Music/Ableton/User Library"),
        LibraryRoot.UserSamples("/Users/srm/Music/Ableton/Samples"),
        LibraryRoot.External(
            path = "/Users/srm/Splice/Sounds",
            alias = "splice",
            kind = ExternalKind.Splice,
        ),
    ),
    cloudBucket = null,
    auth = AuthState.SignedOut,
    selfContainedProjects = emptySet(),
    cacheSettings = BlobCacheSettings(maxSizeBytes = 20L * 1024 * 1024 * 1024, lruEnabled = true),
    loading = false,
)
