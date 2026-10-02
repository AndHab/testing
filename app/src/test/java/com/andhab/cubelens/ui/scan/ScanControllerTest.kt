package com.andhab.cubelens.ui.scan

import androidx.compose.runtime.saveable.SaverScope
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeColor.BLUE
import com.andhab.cubelens.core.cube.CubeColor.GREEN
import com.andhab.cubelens.core.cube.CubeColor.ORANGE
import com.andhab.cubelens.core.cube.CubeColor.RED
import com.andhab.cubelens.core.cube.CubeColor.WHITE
import com.andhab.cubelens.core.cube.CubeColor.YELLOW
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.vision.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScanControllerTest {

    private var controller = ScanController()
    private var now = 1_000L

    private val state: ScanUiState get() = controller.state.value

    @Test
    fun referenceSamplesClassifyAsTheirColor() {
        for (color in CubeColor.entries) assertEquals(color, LiveClassifier.classify(sample(color)))
    }

    // 3×3: the color-guided flow.

    @Test
    fun steadyCorrectFaceIsCapturedAfterAboutSevenHundredMillis() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 600)
        assertEquals(0, state.captureCount)
        assertEquals(green, state.liveColors)
        assertTrue("progress ${state.captureProgress}", state.captureProgress in 0.75f..0.95f)
        assertTrue(state.centerMatches)

        show(green, millis = 150)
        assertEquals(1, state.captureCount)
        assertEquals(ScanStep.Front, state.lastCaptured)
        assertEquals(green, state.captures[ScanStep.Front.ordinal])
        assertEquals(ScanStep.Right, state.currentStep)
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

        // The shutter still works: the user stays in control.
        assertTrue(controller.capture())
        assertEquals(red, state.captures[ScanStep.Front.ordinal])
        assertEquals(ScanStep.Right, state.currentStep)
    }

    @Test
    fun faceWithAnAlreadyCapturedCenterIsNotAutoCapturedAgain() {
        // Step 1 asked for green but the user captured the red face by hand.
        val red = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, ORANGE)
        show(red, millis = 100)
        controller.capture()
        assertEquals(ScanStep.Right, state.currentStep)

        // Step 2 asks for red: showing it again (after showing something else) is a repeat.
        show(face(WHITE, WHITE, WHITE, WHITE, BLUE, WHITE, WHITE, WHITE, WHITE), millis = 600)
        val redAgain = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, YELLOW)
        show(redAgain, millis = 2_000)
        assertEquals(ScanHint.AlreadyScanned(RED, ScanStep.Front), state.hint)
        assertEquals(1, state.captureCount)
        assertEquals(0f, state.captureProgress)
        // The right face for this step sits in green's place: point at that thumbnail.
        assertEquals(ScanStep.Front, state.hint.pointsAtThumbnail(state))
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
        assertEquals(ScanHint.AlreadyScanned(GREEN, ScanStep.Front), state.hint)
        assertEquals(1, state.captureCount)
        assertNull("nothing to redo", state.hint.pointsAtThumbnail(state))
    }

    @Test
    fun retakeReplacesTheFaceAndReturnsToTheNextMissingOne() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        val red = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, ORANGE)
        show(green, millis = 800)
        show(red, millis = 800)
        assertEquals(ScanStep.Back, state.currentStep)
        assertEquals(2, state.captureCount)

        controller.selectStep(ScanStep.Front)
        assertEquals(ScanStep.Front, state.currentStep)
        assertEquals("the old capture stays until replaced", green, state.captures[ScanStep.Front.ordinal])

        val betterGreen = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, YELLOW)
        show(betterGreen, millis = 800)
        assertEquals(3, state.captureCount)
        assertEquals(betterGreen, state.captures[ScanStep.Front.ordinal])
        assertEquals(ScanStep.Back, state.currentStep)
    }

    @Test
    fun retakingAFaceStillInViewNeedsAFreshSteadyHold() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 800)
        controller.selectStep(ScanStep.Front)
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

        val scans = controller.scans()
        assertEquals(6, scans.size)
        for (step in ScanStep.entries) {
            assertEquals(faces.getValue(step), scans[step.ordinal].map(LiveClassifier::classify))
        }

        // Done: frames, the shutter and retakes are ignored.
        show(faces.getValue(ScanStep.Front), millis = 800)
        assertFalse(controller.capture())
        controller.selectStep(ScanStep.Right)
        assertEquals(6, state.captureCount)
        assertEquals(ScanStep.Bottom, state.currentStep)
    }

    @Test
    fun scansAreNotAvailableBeforeTheEnd() {
        assertThrows(IllegalStateException::class.java) { controller.scans() }
    }

    @Test
    fun captureAveragesTheLatestSteadyFrames() {
        // Two slightly different greens (same class), alternating: the capture is their mean. The
        // frames go on a while after the capture, so the shutter is free again for the rest.
        val a = StickerSample.of(20, 200, 90)
        val b = StickerSample.of(30, 210, 100)
        var flip = false
        repeat(40) {
            val s = if (flip) b else a
            flip = !flip
            controller.onFrame(List(9) { s }, now)
            now += FRAME
        }
        assertEquals(1, state.captureCount)
        val captured = captureSamplesOf(ScanStep.Front)
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
        assertTrue(state.centerMatches)
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
        assertEquals(moved, state.captures[ScanStep.Front.ordinal])
    }

    @Test
    fun aTapJustAfterAnAutoCaptureIsIgnored() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 800)
        assertEquals(1, state.captureCount)
        // The tap meant for green lands a moment after it was captured: it doesn't fill red's step with green.
        assertFalse(controller.capture())
        assertEquals(1, state.captureCount)
        assertNull(state.captures[ScanStep.Right.ordinal])

        // A moment later the shutter is the user's again.
        show(green, millis = 400)
        assertTrue(controller.capture())
        assertEquals(2, state.captureCount)
    }

    @Test
    fun choosingAFaceRightAfterAnAutoCaptureFreesTheShutter() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        show(green, millis = 750)
        assertEquals(1, state.captureCount)
        controller.selectStep(ScanStep.Front)
        assertTrue("a deliberate choice: the shutter is meant for it", controller.capture())
        assertEquals(2, state.captureCount)
    }

    @Test
    fun shutterDoesNothingBeforeTheFirstFrame() {
        assertFalse(controller.capture())
        assertEquals(0, state.captureCount)
        assertNull(state.liveColors)
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
    fun relativeCuesApplyOnlyWhileFollowingTheGuidedOrder() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        val red = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, ORANGE)
        assertTrue(state.followsPreviousStep)
        show(green, millis = 800)
        assertTrue("red right after green: 'turn the cube to the left'", state.followsPreviousStep)
        show(red, millis = 800)

        // Redo green: the cube is no longer held as the previous step left it.
        controller.selectStep(ScanStep.Front)
        assertTrue(state.isRetake)
        assertFalse(state.followsPreviousStep)

        // Back to blue, but the cube is held for green now: "turn it left again" would be wrong.
        show(face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, YELLOW), millis = 800)
        assertEquals(ScanStep.Back, state.currentStep)
        assertFalse(state.followsPreviousStep)
    }

    @Test
    fun jumpingAheadIsNotFollowingTheGuidedOrder() {
        controller.selectStep(ScanStep.Left)
        assertFalse(state.followsPreviousStep)
        assertEquals(0, state.capturedCount)
    }

    @Test
    fun aScanInProgressSurvivesSavingAndRestoring() {
        val green = face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE)
        val red = face(RED, RED, BLUE, RED, RED, BLUE, RED, WHITE, ORANGE)
        controller.setAutoCapture(false)
        show(green, millis = 300)
        controller.capture()
        show(red, millis = 300)
        controller.capture()
        controller.selectStep(ScanStep.Front)
        controller.setTorchAvailable(true)
        controller.setTorch(true)
        val before = state

        val restored = saveAndRestore(controller)
        val after = restored.state.value
        assertEquals(before.currentStep, after.currentStep)
        assertEquals(before.captures, after.captures)
        assertEquals(before.captureCount, after.captureCount)
        assertEquals(before.lastCaptured, after.lastCaptured)
        assertEquals(before.stickerColors, after.stickerColors)
        assertFalse(after.autoCapture)
        assertNull("live tracking starts afresh", after.liveColors)
        assertFalse("the camera restarts with the light off", after.torchOn)

        // It carries on where it left off, and hands over the same samples.
        controller = restored
        for (step in listOf(ScanStep.Front, ScanStep.Back, ScanStep.Left, ScanStep.Top, ScanStep.Bottom)) {
            assertEquals(step, state.currentStep)
            show(face(*Array(9) { if (it == 4) step.color else step.topColor }), millis = 100)
            controller.capture()
        }
        assertTrue(state.isComplete)
        assertEquals(red, controller.scans()[ScanStep.Right.ordinal].map(LiveClassifier::classify))
    }

    @Test
    fun aFinishedScanIsRestoredFinished() {
        controller.setAutoCapture(false)
        for (step in ScanStep.entries) {
            show(face(*Array(9) { if (it == 4) step.color else step.topColor }), millis = 100)
            controller.capture()
        }
        val restored = saveAndRestore(controller)
        assertTrue(restored.state.value.isComplete)
        assertEquals(controller.scans(), restored.scans())
    }

    @Test
    fun unrecognizedSavedStateStartsAFreshScan() {
        val restored = ScanController.saver().restore(intArrayOf(7, 7, 7))!!
        assertEquals(ScanUiState(), restored.state.value)
    }

    @Test
    fun aSavedScanOfAnotherSizeIsNotRestored() {
        controller = ScanController(size = 4)
        controller.setAutoCapture(false)
        showN(scrambledFace(4, ScanStep.Front), millis = 100)
        controller.capture()
        val saved = with(ScanController.saver(4)) { SaverScope { true }.save(controller) }!!
        val asFiveByFive = ScanController.saver(5).restore(saved)!!
        assertEquals(ScanUiState(size = 5), asFiveByFive.state.value)
        assertEquals(1, ScanController.saver(4).restore(saved)!!.state.value.capturedCount)
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

    // Every size.

    @Test
    fun everySizeIsScannedFaceByFaceInTheGuidedOrder() {
        for (n in SIZES) {
            controller = ScanController(size = n)
            now += 10_000
            assertEquals(n % 2 == 1, state.hasFixedCenters)
            val faces = ScanStep.entries.associateWith { scrambledFace(n, it) }
            for (step in ScanStep.entries) {
                assertEquals("$n×$n", step, state.currentStep)
                showN(faces.getValue(step), millis = 500)
                assertEquals("$n×$n $step not yet", step.ordinal, state.captureCount)
                showN(faces.getValue(step), millis = 400)
                assertEquals("$n×$n $step captured after a steady 700 ms", step.ordinal + 1, state.captureCount)
            }
            assertTrue("$n×$n", state.isComplete)
            val scans = controller.scans()
            assertEquals(6, scans.size)
            for (step in ScanStep.entries) {
                assertEquals("$n×$n $step", n * n, scans[step.ordinal].size)
                assertEquals("$n×$n $step", faces.getValue(step), scans[step.ordinal].map(LiveClassifier::classify))
            }
        }
    }

    @Test
    fun framesMustMatchTheCubeSize() {
        controller = ScanController(size = 4)
        assertThrows(IllegalArgumentException::class.java) { controller.onFrame(List(9) { sample(GREEN) }, now) }
    }

    @Test
    fun bigFacesStaySteadyDespiteAFewFlickeringStickers() {
        for ((n, flickering) in listOf(4 to 1, 5 to 1, 7 to 2)) {
            controller = ScanController(size = n)
            now += 10_000
            val steady = scrambledFace(n, ScanStep.Front)
            // These stickers keep jumping between two readings, frame by frame (glare, an in-between color).
            val cells = (0 until n * n).filter { it != n * n / 2 }.take(flickering)
            showFlickering(steady, cells, millis = 800)
            assertEquals("$n×$n with $flickering flickering", 1, state.captureCount)
        }
    }

    @Test
    fun tooManyFlickeringStickersMeanTheFaceIsStillMoving() {
        for ((n, flickering) in listOf(3 to 1, 4 to 2, 7 to 3)) {
            controller = ScanController(size = n)
            now += 10_000
            val steady = scrambledFace(n, ScanStep.Front)
            val cells = (0 until n * n).filter { it != n * n / 2 }.take(flickering)
            showFlickering(steady, cells, millis = 2_000)
            assertEquals("$n×$n with $flickering flickering", 0, state.captureCount)
        }
    }

    @Test
    fun toleranceGrowsWithTheFace() {
        val tuning = ScanTuning()
        assertEquals(listOf(0, 0, 1, 1, 2, 2), (2..7).map(tuning::toleratedFlicker))
    }

    // Even sizes: no fixed centers.

    @Test
    fun evenSizesTakeAnyFaceWithoutCenterChecks() {
        for (n in listOf(2, 4, 6)) {
            controller = ScanController(size = n)
            now += 10_000
            // An all-red face for "the front": nothing says which color belongs there.
            val anyFace = List(n * n) { RED }
            showN(anyFace, millis = 2_000)
            assertEquals("$n×$n", 1, state.captureCount)
            assertFalse(state.centerMatches)
            assertNull(state.hint)
        }
    }

    @Test
    fun evenSizesDoNotAutoCaptureAFaceThatWasAlreadyScanned() {
        controller = ScanController(size = 4)
        val first = scrambledFace(4, ScanStep.Front)
        val other = scrambledFace(4, ScanStep.Back)
        showN(first, millis = 800)
        assertEquals(1, state.captureCount)
        assertNull("the face just captured is still in view: no heads-up", state.hint)
        showN(first, millis = 2_000)
        assertEquals("still the same face: not captured again", 1, state.captureCount)
        assertNull(state.hint)

        // Turned away for a moment (not long enough to be captured), then back to the first face:
        // now it is a repeat worth mentioning.
        showN(other, millis = 500)
        assertEquals(1, state.captureCount)
        showN(first, millis = 300)
        assertNull("no heads-up while it settles", state.hint)
        showN(first, millis = 2_000)
        assertEquals(ScanHint.SameAsCaptured(ScanStep.Front), state.hint)
        assertEquals(1, state.captureCount)
        assertEquals(0f, state.captureProgress)
    }

    @Test
    fun aFaceThatLooksLikeAnotherCanStillBeTakenByHandAndIsReported() {
        controller = ScanController(size = 4)
        val first = scrambledFace(4, ScanStep.Front)
        val second = scrambledFace(4, ScanStep.Right)
        showN(first, millis = 800)
        assertEquals(1, state.captureCount)
        // A sticker reads differently this time, still the same face as far as the tolerance goes.
        val firstAgain = first.toMutableList().also { it[0] = if (it[0] == BLUE) GREEN else BLUE }
        showN(firstAgain, millis = 500)
        assertTrue(controller.capture())
        assertEquals(setOf(ScanStep.Front, ScanStep.Right), state.lookAlikes)

        // Redo one of them with the real second face: the warning goes away.
        controller.selectStep(ScanStep.Right)
        showN(second, millis = 800)
        assertEquals(3, state.captureCount)
        assertEquals(emptySet<ScanStep>(), state.lookAlikes)
    }

    @Test
    fun redoingALookAlikeThatStillLooksAlikeSettlesIt() {
        // Two different faces of a scrambled cube that happen to look the same.
        controller = ScanController(size = 4)
        val first = scrambledFace(4, ScanStep.Front)
        val twin = first.toMutableList().also { it[0] = if (it[0] == BLUE) GREEN else BLUE }
        showN(first, millis = 800)
        showN(twin, millis = 500)
        controller.capture()
        assertEquals(listOf(LookAlike(ScanStep.Front, ScanStep.Right)), state.lookAlikePairs)

        // Redone as asked, the second face still looks like the first: it is held back, as any repeat.
        controller.selectStep(ScanStep.Right)
        showN(twin, millis = 2_000)
        assertEquals(ScanHint.SameAsCaptured(ScanStep.Front), state.hint)
        assertEquals(2, state.captureCount)
        // Taking it by hand again says they are two faces: no more warning.
        assertTrue(controller.capture())
        assertEquals(emptyList<LookAlike>(), state.lookAlikePairs)
        assertEquals(ScanStep.Back, state.currentStep)

        // Nor is one held back as a repeat of the other any more, and that survives a restart.
        controller = saveAndRestore(controller)
        assertEquals(emptyList<LookAlike>(), state.lookAlikePairs)
        controller.selectStep(ScanStep.Right)
        showN(twin, millis = 2_000)
        assertNull(state.hint)
        assertEquals(4, state.captureCount)
    }

    @Test
    fun aSettledLookAlikeIsReportedAgainOnceItWasRedoneDifferently() {
        controller = ScanController(size = 4)
        controller.setAutoCapture(false)
        val first = scrambledFace(4, ScanStep.Front)
        val twin = first.toMutableList().also { it[0] = if (it[0] == BLUE) GREEN else BLUE }
        for (face in listOf(first, twin)) {
            showN(face, millis = 100)
            controller.capture()
        }
        controller.selectStep(ScanStep.Right)
        showN(twin, millis = 100)
        controller.capture()
        assertEquals(emptyList<LookAlike>(), state.lookAlikePairs)

        // Face 2 redone with another face, then (by mistake) with the first face once more.
        controller.selectStep(ScanStep.Right)
        showN(scrambledFace(4, ScanStep.Right), millis = 100)
        controller.capture()
        controller.selectStep(ScanStep.Right)
        showN(first, millis = 100)
        controller.capture()
        assertEquals(listOf(LookAlike(ScanStep.Front, ScanStep.Right)), state.lookAlikePairs)
    }

    @Test
    fun redoingAFaceThatNowLooksLikeAnotherOneIsReported() {
        // Redoing face 2 settles it with face 1 only, not with a face it newly looks like.
        controller = ScanController(size = 4)
        controller.setAutoCapture(false)
        val first = scrambledFace(4, ScanStep.Front)
        val third = scrambledFace(4, ScanStep.Back)
        for (face in listOf(first, first, third)) {
            showN(face, millis = 100)
            controller.capture()
        }
        assertEquals(listOf(LookAlike(ScanStep.Front, ScanStep.Right)), state.lookAlikePairs)
        controller.selectStep(ScanStep.Right)
        showN(third, millis = 100)
        controller.capture()
        assertEquals(listOf(LookAlike(ScanStep.Right, ScanStep.Back)), state.lookAlikePairs)
    }

    @Test
    fun evenSizesSpotAFaceScannedAgainTurned() {
        controller = ScanController(size = 4)
        val faces = ScanStep.entries.associateWith { scrambledFace(4, it) }
        for (step in ScanStep.entries.dropLast(1)) showN(faces.getValue(step), millis = 800)
        assertEquals(ScanStep.Bottom, state.currentStep)
        assertEquals(5, state.captureCount)

        // Flipped only a quarter turn: the back face comes round again, upside down.
        val backUpsideDown = turned(faces.getValue(ScanStep.Back), n = 4, quarterTurns = 2)
        assertTrue("upside down it reads differently", backUpsideDown != faces.getValue(ScanStep.Back))
        showN(backUpsideDown, millis = 2_000)
        assertEquals(ScanHint.SameAsCaptured(ScanStep.Back), state.hint)
        assertEquals("not auto-captured", 5, state.captureCount)
        assertEquals(0f, state.captureProgress)

        // Flipped all the way: the bottom face is new and goes in.
        showN(faces.getValue(ScanStep.Bottom), millis = 800)
        assertTrue(state.isComplete)
        assertEquals(emptySet<ScanStep>(), state.lookAlikes)
    }

    @Test
    fun aFaceTakenByHandTurnedIsStillReportedAsALookAlike() {
        controller = ScanController(size = 2)
        controller.setAutoCapture(false)
        val first = scrambledFace(2, ScanStep.Front)
        showN(first, millis = 200)
        controller.capture()
        // Turned to another side, then back to the first one, held a quarter turn round.
        showN(scrambledFace(2, ScanStep.Back), millis = 600)
        assertNull(state.hint)
        showN(turned(first, n = 2, quarterTurns = 1), millis = 600)
        assertEquals(ScanHint.SameAsCaptured(ScanStep.Front), state.hint)
        controller.capture()
        assertEquals(setOf(ScanStep.Front, ScanStep.Right), state.lookAlikes)
    }

    @Test
    fun retakingAFaceOfAnEvenCubeTakesThatFaceAgain() {
        controller = ScanController(size = 2)
        val first = scrambledFace(2, ScanStep.Front)
        showN(first, millis = 800)
        showN(scrambledFace(2, ScanStep.Right), millis = 800)
        assertEquals(2, state.captureCount)
        controller.selectStep(ScanStep.Front)
        showN(first, millis = 800)
        assertEquals("its own earlier capture doesn't count as a repeat", 3, state.captureCount)
        assertNull(state.hint)
        assertEquals(ScanStep.Back, state.currentStep)
    }

    @Test
    fun oddSizesFlagTheCenterOnlyAndNeverReportLookAlikes() {
        controller = ScanController(size = 5)
        val front = scrambledFace(5, ScanStep.Front)
        showN(front, millis = 800)
        assertEquals(1, state.captureCount)
        showN(front, millis = 500)
        assertTrue(controller.capture())
        assertEquals(2, state.captureCount)
        assertEquals(emptySet<ScanStep>(), state.lookAlikes)
        assertEquals(12, state.centerIndex)
    }

    // Learning the cube's colors.

    @Test
    fun everyCaptureTeachesTheClassifierAndTheFacesFollowItsNames() {
        val classifier = AdaptiveLiveClassifier()
        controller = ScanController(size = 3, classifier = classifier)
        controller.setAutoCapture(false)
        // A pastel cube: a baby-blue face without white stickers first, then the white face.
        val blueFace = List(9) { if (it == 4) Pastel.getValue(BLUE) else Pastel.getValue(listOf(GREEN, RED, ORANGE, YELLOW)[it % 4]) }
        val whiteFace = List(9) { if (it == 4) Pastel.getValue(WHITE) else Pastel.getValue(listOf(BLUE, ORANGE, GREEN)[it % 3]) }

        showSamples(blueFace, millis = 200)
        controller.capture()
        val namesAfterOne = checkNotNull(AdaptiveLiveClassifier().learnFaces(listOf(blueFace), 3))
        assertEquals(namesAfterOne[0], state.captures[ScanStep.Front.ordinal]!![4])
        assertTrue("the classifier learned the captured center", namesAfterOne[0] in classifier.learned)

        showSamples(whiteFace, millis = 200)
        controller.capture()
        val namesAfterTwo = checkNotNull(AdaptiveLiveClassifier().learnFaces(listOf(blueFace, whiteFace), 3))
        assertEquals("names of earlier faces follow what was learned since", namesAfterTwo[0], state.captures[ScanStep.Front.ordinal]!![4])
        assertEquals(namesAfterTwo[1], state.captures[ScanStep.Right.ordinal]!![4])
        assertEquals(namesAfterTwo.toSet(), classifier.learned.keys)
        // The other stickers are read with what was learned, too.
        assertEquals(blueFace.map(classifier::classify).toMutableList().also { it[4] = namesAfterTwo[0] }, state.captures[ScanStep.Front.ordinal])
    }

    @Test
    fun retakesAreLearnedFromTheNewCapture() {
        val classifier = AdaptiveLiveClassifier()
        controller = ScanController(size = 3, classifier = classifier)
        controller.setAutoCapture(false)
        showSamples(List(9) { Pastel.getValue(GREEN) }, millis = 100)
        controller.capture()
        assertEquals(setOf(state.captures[0]!![4]), classifier.learned.keys)
        controller.selectStep(ScanStep.Front)
        showSamples(List(9) { sample(ORANGE) }, millis = 100)
        controller.capture()
        assertEquals(1, classifier.learned.size)
        assertEquals(setOf(state.captures[0]!![4]), classifier.learned.keys)
        assertEquals(ORANGE, state.captures[0]!![4])
    }

    @Test
    fun aNewSessionForgetsWhatTheLastOneLearned() {
        val classifier = AdaptiveLiveClassifier()
        classifier.learn(GREEN, sample(GREEN))
        ScanController(size = 3, classifier = classifier)
        assertTrue(classifier.learned.isEmpty())
    }

    @Test
    fun aPastelCubeIsDrawnInItsOwnColorsAndAStandardOneInStockColors() {
        controller.setAutoCapture(false)
        assertTrue("nothing learned yet: stock colors", state.stickerColors.isEmpty())
        showSamples(List(9) { Pastel.getValue(listOf(GREEN, RED, YELLOW, BLUE, GREEN, WHITE, ORANGE, RED, YELLOW)[it]) }, millis = 100)
        controller.capture()
        val pastel = state.stickerColors
        assertTrue("a pastel cube gets its own colors: $pastel", pastel.isNotEmpty())
        // Pink, not stock red: lighter and softer.
        val pink = pastel.getValue(state.captures[0]!![1])
        val lab = ColorMath.srgbToLab((pink shr 16) and 0xFF, (pink shr 8) and 0xFF, pink and 0xFF)
        assertTrue("light: $lab", lab.l > 70f)
        assertTrue("soft: $lab", lab.chroma < 50f)

        controller = ScanController()
        controller.setAutoCapture(false)
        show(face(GREEN, RED, WHITE, YELLOW, GREEN, RED, ORANGE, BLUE, WHITE), millis = 100)
        controller.capture()
        assertTrue("a standard cube keeps the stock colors", state.stickerColors.isEmpty())
    }

    @Test
    fun evenSizesAreReadWithWhatTheClassifierLearned() {
        val classifier = AdaptiveLiveClassifier()
        controller = ScanController(size = 4, classifier = classifier)
        val front = scrambledFace(4, ScanStep.Front)
        showN(front, millis = 800)
        assertEquals(1, state.captureCount)
        assertEquals(front.map { sample(it) }.map(classifier::classify), state.captures[0])
    }

    // Helpers

    /** Shows the same 3×3 face for [millis], one frame every [FRAME] ms. */
    private fun show(colors: List<CubeColor>, millis: Long) = showN(colors, millis)

    /** Shows the same face (any size) for [millis], one frame every [FRAME] ms. */
    private fun showN(colors: List<CubeColor>, millis: Long) = showSamples(colors.map(::sample), millis)

    private fun showSamples(samples: List<StickerSample>, millis: Long) {
        val end = now + millis
        while (now < end) {
            controller.onFrame(samples, now)
            now += FRAME
        }
    }

    /** Shows [colors] with the stickers at [cells] alternating, frame by frame, with another color. */
    private fun showFlickering(colors: List<CubeColor>, cells: List<Int>, millis: Long) {
        val end = now + millis
        var odd = false
        while (now < end) {
            val frame = colors.mapIndexed { i, c -> if (odd && i in cells) other(c) else c }
            controller.onFrame(frame.map(::sample), now)
            odd = !odd
            now += FRAME
        }
    }

    private fun other(color: CubeColor): CubeColor = if (color == RED) ORANGE else RED

    /** A row-major [n]×[n] face as it reads turned [quarterTurns] quarter turns clockwise in the frame. */
    private fun turned(colors: List<CubeColor>, n: Int, quarterTurns: Int): List<CubeColor> {
        var face = colors
        repeat(quarterTurns) { face = List(n * n) { i -> face[(n - 1 - i % n) * n + i / n] } }
        return face
    }

    private fun face(vararg colors: CubeColor): List<CubeColor> = colors.toList().also { require(it.size == 9) }

    /**
     * The face of a scrambled [n]×[n] cube that [step] scans. Odd cubes keep their centers, so each
     * face has the center color its step asks for; no two faces look alike.
     */
    private fun scrambledFace(n: Int, step: ScanStep): List<CubeColor> =
        NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(n))).face(step.face)

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

    private fun saveAndRestore(controller: ScanController): ScanController {
        val saver = ScanController.saver(controller.size)
        val saved = with(saver) { SaverScope { true }.save(controller) }
        return saver.restore(checkNotNull(saved))!!
    }

    private companion object {
        /** About 30 frames per second. */
        const val FRAME = 33L

        val SIZES = listOf(2, 3, 4, 5, 7)

        /**
         * A pastel knock-off's stickers as a phone camera captures them (design colors pink, peach,
         * lemon, mint, baby blue and warm white at a typical auto exposure).
         */
        val Pastel: Map<CubeColor, StickerSample> = mapOf(
            WHITE to 0xF7F5EE, YELLOW to 0xF3E58A, GREEN to 0x9EDDB0,
            BLUE to 0x93BFEA, RED to 0xF2A0B4, ORANGE to 0xF7BE92,
        ).mapValues { (_, rgb) ->
            fun channel(shift: Int) = ColorMath.linearToSrgb(ColorMath.srgbToLinear((rgb shr shift) and 0xFF) * 0.66)
            StickerSample.of(channel(16), channel(8), channel(0))
        }
    }
}
