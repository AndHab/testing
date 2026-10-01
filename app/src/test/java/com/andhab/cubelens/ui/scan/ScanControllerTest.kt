package com.andhab.cubelens.ui.scan

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeColor.BLUE
import com.andhab.cubelens.core.cube.CubeColor.GREEN
import com.andhab.cubelens.core.cube.CubeColor.ORANGE
import com.andhab.cubelens.core.cube.CubeColor.RED
import com.andhab.cubelens.core.cube.CubeColor.WHITE
import com.andhab.cubelens.core.cube.CubeColor.YELLOW
import com.andhab.cubelens.core.vision.ColorMath
import com.andhab.cubelens.core.vision.LiveClassifier
import com.andhab.cubelens.core.vision.StickerSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScanControllerTest {

    private lateinit var controller: ScanController
    private var now = 1_000L

    private val state: ScanUiState get() = controller.state.value

    @Before
    fun setUp() {
        controller = ScanController()
    }

    @Test
    fun referenceSamplesClassifyAsTheirColor() {
        for (color in CubeColor.entries) assertEquals(color, LiveClassifier.classify(sample(color)))
    }

    @Test
    fun steadyCorrectFaceIsCapturedAfterAboutSevenHundredMillis() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 600)
        assertEquals(0, state.captureCount)
        assertEquals(green, state.liveColors)
        assertTrue("progress ${state.captureProgress}", state.captureProgress in 0.75f..0.95f)
        assertEquals(StatusTone.Good, statusMessage(state).tone)

        show(green, millis = 150)
        assertEquals(1, state.captureCount)
        assertEquals(ScanStep.Green, state.lastCaptured)
        assertEquals(green, state.captures[ScanStep.Green.ordinal])
        assertEquals(ScanStep.Red, state.currentStep)
        assertEquals("steadiness restarts after a capture", 0f, state.captureProgress)
        assertNull("no warning about the face just captured", state.hint)
    }

    @Test
    fun progressGrowsWhileSteadyAndRestartsWhenTheFaceChanges() {
        val green = face(GREEN, GREEN, GREEN, GREEN, GREEN, GREEN, GREEN, GREEN, RED)
        show(green, millis = 200)
        val early = state.captureProgress
        show(green, millis = 200)
        assertTrue(state.captureProgress > early)
        // A different face (one sticker changed for good) starts over.
        show(face(GREEN, GREEN, GREEN, GREEN, GREEN, GREEN, GREEN, GREEN, ORANGE), millis = 200)
        assertTrue("progress ${state.captureProgress}", state.captureProgress < early + 0.1f)
        assertEquals(0, state.captureCount)
    }

    @Test
    fun aSingleFlickeringFrameDoesNotResetSteadiness() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        val flicker = face(GREEN, ORANGE, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 400)
        show(flicker, millis = 33)
        show(green, millis = 300)
        assertEquals(1, state.captureCount)
    }

    @Test
    fun wrongFaceIsPointedOutAndNeverAutoCaptured() {
        val red = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, ORANGE)
        show(red, millis = 300)
        assertNull("no hint while the face is still settling", state.hint)
        show(red, millis = 2_000)
        assertEquals(ScanHint.WrongFace(seen = RED, expected = GREEN), state.hint)
        assertEquals(0, state.captureCount)
        assertEquals(0f, state.captureProgress)
        assertEquals(StatusTone.Warning, statusMessage(state).tone)
        assertEquals("This looks like the red face — expected green", statusMessage(state).text)

        // The shutter still works: the user stays in control.
        assertTrue(controller.capture())
        assertEquals(red, state.captures[ScanStep.Green.ordinal])
        assertEquals(ScanStep.Red, state.currentStep)
    }

    @Test
    fun faceWithAnAlreadyCapturedCenterIsNotAutoCapturedAgain() {
        // Step 1 asked for green but the user captured the red face by hand.
        val red = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, ORANGE)
        show(red, millis = 100)
        controller.capture()
        assertEquals(ScanStep.Red, state.currentStep)

        // Step 2 asks for red: showing it again (after showing something else) is a repeat.
        show(face(WHITE, WHITE, WHITE, WHITE, BLUE, WHITE, WHITE, WHITE, WHITE), millis = 600)
        val redAgain = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, YELLOW)
        show(redAgain, millis = 2_000)
        assertEquals(ScanHint.AlreadyScanned(RED, ScanStep.Green), state.hint)
        assertEquals(1, state.captureCount)
        assertEquals(0f, state.captureProgress)
    }

    @Test
    fun theFaceJustCapturedIsNotFlaggedWhileStillInView() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 2_000)
        assertEquals(1, state.captureCount)
        assertNull(state.hint)
        // Turned away and back: now it is a repeat worth mentioning.
        show(face(RED, RED, RED, RED, RED, RED, RED, RED, BLUE), millis = 100)
        show(face(WHITE, WHITE, WHITE, WHITE, YELLOW, WHITE, WHITE, WHITE, WHITE), millis = 600)
        show(green, millis = 600)
        assertEquals(ScanHint.AlreadyScanned(GREEN, ScanStep.Green), state.hint)
        assertEquals(1, state.captureCount)
    }

    @Test
    fun retakeReplacesTheFaceAndReturnsToTheNextMissingOne() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        val red = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, ORANGE)
        show(green, millis = 800)
        show(red, millis = 800)
        assertEquals(ScanStep.Blue, state.currentStep)
        assertEquals(2, state.captureCount)

        controller.selectStep(ScanStep.Green)
        assertEquals(ScanStep.Green, state.currentStep)
        assertEquals("the old capture stays until replaced", green, state.captures[ScanStep.Green.ordinal])

        val betterGreen = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, YELLOW)
        show(betterGreen, millis = 800)
        assertEquals(3, state.captureCount)
        assertEquals(betterGreen, state.captures[ScanStep.Green.ordinal])
        assertEquals(ScanStep.Blue, state.currentStep)
    }

    @Test
    fun retakingAFaceStillInViewNeedsAFreshSteadyHold() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 800)
        controller.selectStep(ScanStep.Green)
        show(green, millis = 400)
        assertEquals(1, state.captureCount)
        show(green, millis = 400)
        assertEquals(2, state.captureCount)
    }

    @Test
    fun allSixFacesCompleteTheScanInStepOrder() {
        val faces = ScanStep.entries.associateWith { step -> face(*Array(9) { if (it == 4) step.color else step.topColor }) }
        for (step in ScanStep.entries) {
            assertFalse(state.isComplete)
            assertEquals(step, state.currentStep)
            show(faces.getValue(step), millis = 800)
        }
        assertTrue(state.isComplete)
        assertEquals(6, state.captureCount)
        assertEquals(StatusTone.Good, statusMessage(state).tone)

        val scans = controller.scans()
        assertEquals(6, scans.size)
        for (step in ScanStep.entries) {
            assertEquals(faces.getValue(step), scans[step.ordinal].map(LiveClassifier::classify))
        }

        // Done: frames, the shutter and retakes are ignored.
        show(faces.getValue(ScanStep.Green), millis = 800)
        assertFalse(controller.capture())
        controller.selectStep(ScanStep.Red)
        assertEquals(6, state.captureCount)
        assertEquals(ScanStep.Yellow, state.currentStep)
    }

    @Test
    fun scansAreNotAvailableBeforeTheEnd() {
        assertThrows(IllegalStateException::class.java) { controller.scans() }
    }

    @Test
    fun captureAveragesTheLatestSteadyFrames() {
        // Two slightly different greens (same class), alternating: the capture is their mean.
        val a = StickerSample.of(20, 200, 90)
        val b = StickerSample.of(30, 210, 100)
        var flip = false
        repeat(30) {
            val s = if (flip) b else a
            flip = !flip
            controller.onFrame(List(9) { s }, now)
            now += FRAME
        }
        assertEquals(1, state.captureCount)
        val captured = captureSamplesOf(ScanStep.Green)
        for (sticker in captured) {
            assertTrue("r ${sticker.r}", sticker.r in 22..28)
            assertTrue("g ${sticker.g}", sticker.g in 202..208)
            assertTrue("b ${sticker.b}", sticker.b in 92..98)
        }
    }

    @Test
    fun autoCaptureOffLeavesItToTheShutter() {
        controller.setAutoCapture(false)
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 2_000)
        assertEquals(0, state.captureCount)
        assertEquals(0f, state.captureProgress)
        assertEquals("Looks right. Tap the shutter.", statusMessage(state).text)
        assertTrue(controller.capture())
        assertEquals(1, state.captureCount)
    }

    @Test
    fun shutterRightAfterAMoveTakesWhatIsInViewNow() {
        controller.setAutoCapture(false)
        show(face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE), millis = 500)
        // One frame of a different face: the smoothed live colors haven't switched yet.
        val moved = face(GREEN, GREEN, GREEN, BLUE, GREEN, BLUE, YELLOW, YELLOW, YELLOW)
        show(moved, millis = FRAME)
        assertTrue(controller.capture())
        assertEquals(moved, state.captures[ScanStep.Green.ordinal])
    }

    @Test
    fun shutterDoesNothingBeforeTheFirstFrame() {
        assertFalse(controller.capture())
        assertEquals(0, state.captureCount)
        assertEquals("Starting the camera…", statusMessage(state).text)
    }

    @Test
    fun aPauseInFramesRestartsSteadiness() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 500)
        now += 2_000 // camera paused
        show(green, millis = 400)
        assertEquals(0, state.captureCount)
        show(green, millis = 400)
        assertEquals(1, state.captureCount)
    }

    @Test
    fun torchNeedsAFlashUnit() {
        controller.setTorch(true)
        assertFalse(state.torchOn)
        controller.setTorchAvailable(true)
        controller.setTorch(true)
        assertTrue(state.torchOn)
        controller.setTorchAvailable(false)
        assertFalse(state.torchOn)
    }

    // Helpers

    /** Shows the same face for [millis], one frame every [FRAME] ms. */
    private fun show(colors: List<CubeColor>, millis: Long) {
        val samples = colors.map(::sample)
        val end = now + millis
        while (now < end) {
            controller.onFrame(samples, now)
            now += FRAME
        }
    }

    private fun face(vararg colors: CubeColor): List<CubeColor> = colors.toList().also { require(it.size == 9) }

    /** A typical camera reading of a [color] sticker. */
    private fun sample(color: CubeColor): StickerSample = StickerSample.ofArgb(ColorMath.labToArgb(LiveClassifier.reference.getValue(color)))

    /** The samples captured for [step], through the public API (only available once complete). */
    private fun captureSamplesOf(step: ScanStep): List<StickerSample> {
        // Fill the remaining steps by hand, then read the scans.
        for (other in ScanStep.entries) {
            if (state.isCaptured(other)) continue
            controller.selectStep(other)
            controller.capture()
        }
        return controller.scans()[step.ordinal]
    }

    private companion object {
        /** About 30 frames per second. */
        const val FRAME = 33L
    }
}
