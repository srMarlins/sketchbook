package com.sketchbook.featureprojects.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SmokeTest {
    @Test
    fun captures_a_hello_box() =
        runDesktopComposeUiTest(width = 400, height = 200) {
            setContent {
                Box(Modifier.fillMaxSize().size(200.dp)) {
                    BasicText("hello roborazzi")
                }
            }
            onRoot().captureRoboImage("build/roborazzi/smoke_hello.png")
        }
}
