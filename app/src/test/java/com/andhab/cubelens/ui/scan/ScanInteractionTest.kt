package com.andhab.cubelens.ui.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.camera.GuideGeometry
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.theme.CubeLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScanInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val live = List(9) { if (it == 4) CubeColor.RED else CubeColor.BLUE }

    @Test
    fun controlsReportTheirActions() {
        val events = mutableListOf<String>()
        var guide: GuideGeometry? = null
        val state = ScanUiState(
            currentStep = ScanStep.Right,
            captures = ScanStep.entries.map { if (it == ScanStep.Front) List(9) { CubeColor.GREEN } else null },
            liveColors = live,
            torchAvailable = true,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CubeLensTheme {
                ScanContent(
                    state = state,
                    preview = { Box(Modifier.fillMaxSize()) },
                    onBack = { events += "back" },
                    onCapture = { events += "capture" },
                    onAutoCaptureChange = { events += "auto=$it" },
                    onTorchChange = { events += "torch=$it" },
                    onSelectStep = { events += "select=$it" },
                    onManualEntry = { events += "manual" },
                    onGuideChange = { guide = it },
                )
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }

        compose.onNodeWithContentDescription("Shutter").performClick()
        compose.onNodeWithContentDescription("Auto-capture").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Turn flashlight on").performClick()
        compose.onNodeWithContentDescription("Green face, scanned").performClick()
        compose.onNodeWithContentDescription("Enter colors manually").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Face 2 of 6").assertExists()
        assertEquals(listOf("capture", "auto=false", "torch=true", "select=Front", "manual", "back"), events)

        // The guide is reported in preview pixels: a centered square, about three quarters wide.
        val reported = checkNotNull(guide) { "The guide position was never reported" }
        assertTrue(reported.size in reported.viewWidth * 0.6f..reported.viewWidth * 0.8f)
        assertEquals(reported.viewWidth / 2f, reported.centerX, 2f)
    }

    @Test
    fun cubesWithoutFixedCentersAreGuidedByPosition() {
        val selected = mutableListOf<ScanStep>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CubeLensTheme {
                ScanContent(
                    state = ScanUiState(
                        size = 4,
                        currentStep = ScanStep.Right,
                        captures = ScanStep.entries.map { if (it == ScanStep.Front) List(16) { CubeColor.BLUE } else null },
                        captureCount = 1,
                        lastCaptured = ScanStep.Front,
                        liveColors = List(16) { CubeColor.RED },
                    ),
                    preview = { Box(Modifier.fillMaxSize()) },
                    onBack = {},
                    onCapture = {},
                    onAutoCaptureChange = {},
                    onTorchChange = {},
                    onSelectStep = { selected += it },
                    onManualEntry = {},
                )
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText("Face 2 of 6").assertExists()
        compose.onNodeWithText("Turn the cube left").assertExists()
        compose.onNodeWithText("Same side on top").assertExists()
        compose.onNodeWithContentDescription("Face 2, scanning now").assertExists()
        compose.onNodeWithContentDescription("Face 6, not scanned yet").assertExists()
        compose.onNodeWithContentDescription("Face 1, scanned").performClick()
        assertEquals(listOf(ScanStep.Front), selected)
    }

    @Test
    fun autoCaptureSwitchShowsItsState() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CubeLensTheme {
                ScanContent(
                    state = ScanUiState(autoCapture = false),
                    preview = {},
                    onBack = {},
                    onCapture = {},
                    onAutoCaptureChange = {},
                    onTorchChange = {},
                    onSelectStep = {},
                    onManualEntry = {},
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Auto-capture").assertIsOff()
        compose.onNodeWithText("Auto off").assertExists()
    }

    @Test
    fun withoutCameraPermissionTheScreenExplainsAndOffersManualEntry() {
        var manual = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CubeLensTheme {
                ScanScreen(onScanned = {}, onBack = {}, onManualEntry = { manual++ })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("CubeLens reads your cube's colors with the camera. Nothing is saved or uploaded.").assertExists()
        compose.onNodeWithText("Allow camera").assertExists()
        compose.onNodeWithText("Enter colors manually instead").performClick()
        assertEquals(1, manual)
    }
}
