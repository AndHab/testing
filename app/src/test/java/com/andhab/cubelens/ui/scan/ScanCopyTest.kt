package com.andhab.cubelens.ui.scan

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The scan screen's words for each situation, as the user reads them. */
@RunWith(AndroidJUnit4::class)
class ScanCopyTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private val nine = List(9) { CubeColor.GREEN }

    /** Faces captured at [steps]: red, around the step's standard center color on odd sizes. */
    private fun captured(vararg steps: ScanStep, size: Int = 3): List<List<CubeColor>?> =
        ScanStep.entries.map { step ->
            if (step in steps) List(size * size) { if (size % 2 == 1 && it == size * size / 2) step.color else CubeColor.RED } else null
        }

    @Test
    fun titleCountsFacesAndNamesRedos() {
        assertEquals("Face 1 of 6", scanTitle(ScanUiState(), res))
        assertEquals("Face 3 of 6", scanTitle(ScanUiState(currentStep = ScanStep.Back, captures = captured(ScanStep.Front, ScanStep.Right)), res))
        assertEquals("Redo green", scanTitle(ScanUiState(captures = captured(ScanStep.Front)), res))
        assertEquals("Redo face 1", scanTitle(ScanUiState(size = 4, captures = captured(ScanStep.Front, size = 4)), res))
        assertEquals("All done", scanTitle(ScanUiState(isComplete = true), res))
    }

    @Test
    fun captionCountsWhatIsScanned() {
        assertEquals("Six faces to go", progressCaption(ScanUiState(), res))
        assertEquals("2 of 6 scanned · tap one to redo it", progressCaption(ScanUiState(captures = captured(ScanStep.Front, ScanStep.Right)), res))
        assertEquals("Nice scanning!", progressCaption(ScanUiState(isComplete = true), res))
    }

    @Test
    fun statusFollowsTheFaceInView() {
        assertEquals(StatusMessage("Starting the camera…", StatusTone.Neutral), statusMessage(ScanUiState(), res))
        assertEquals(StatusMessage("Hold still…", StatusTone.Good), statusMessage(ScanUiState(liveColors = nine), res))
        assertEquals(StatusMessage("Looks right. Tap the shutter.", StatusTone.Good), statusMessage(ScanUiState(liveColors = nine, autoCapture = false), res))
        assertEquals(
            StatusMessage("Fit the face inside the frame", StatusTone.Neutral),
            statusMessage(ScanUiState(liveColors = List(9) { CubeColor.RED }), res),
        )
        assertEquals(StatusMessage("All six faces scanned", StatusTone.Good), statusMessage(ScanUiState(isComplete = true), res))
    }

    @Test
    fun headsUpsForCubesWithFixedCenters() {
        val wrong = ScanUiState(liveColors = nine, hint = ScanHint.WrongFace(seen = CubeColor.RED, expected = CubeColor.GREEN))
        assertEquals(StatusMessage("Looks like the red face\u00A0— show green, white on top", StatusTone.Warning), statusMessage(wrong, res))

        val notTurnedYet = ScanUiState(currentStep = ScanStep.Right, liveColors = nine, hint = ScanHint.AlreadyScanned(CubeColor.GREEN, ScanStep.Front))
        assertEquals("Green's done\u00A0— now show red, white on top", statusMessage(notTurnedYet, res).text)

        val wrongSpot = ScanUiState(currentStep = ScanStep.Right, liveColors = nine, hint = ScanHint.AlreadyScanned(CubeColor.RED, ScanStep.Front))
        assertEquals("Red went into green's spot. Tap green below to redo it.", statusMessage(wrongSpot, res).text)
    }

    @Test
    fun aCubeArrangedDifferentlyIsTalkedAboutByPosition() {
        // Turned left after green, orange comes round where the standard scheme has red: taken.
        val orange = List(9) { if (it == 4) CubeColor.ORANGE else CubeColor.BLUE }
        val turned = ScanUiState(
            currentStep = ScanStep.Right,
            captures = captured(ScanStep.Front),
            captureCount = 1,
            lastCaptured = ScanStep.Front,
            liveColors = orange,
        )
        assertTrue(turned.centerMatches)
        assertEquals(StatusMessage("Orange center works too. Hold still…", StatusTone.Good), statusMessage(turned, res))
        assertEquals("Orange center works too. Tap the shutter.", statusMessage(turned.copy(autoCapture = false), res).text)
        // White stays on top while turning: not a face this step takes.
        val white = turned.copy(liveColors = List(9) { if (it == 4) CubeColor.WHITE else CubeColor.BLUE })
        assertFalse(white.centerMatches)
        assertEquals("Red center facing you", instructionFor(ScanStep.Right, 3, true, res, turned.guidedByColor).title)

        // Once orange is captured for the right face, the steps go by position.
        val next = ScanUiState(
            currentStep = ScanStep.Back,
            captures = ScanStep.entries.map { if (it == ScanStep.Front || it == ScanStep.Right) (if (it == ScanStep.Front) captured(it)[0] else orange) else null },
            captureCount = 2,
            lastCaptured = ScanStep.Right,
            liveColors = List(9) { if (it == 4) CubeColor.ORANGE else CubeColor.RED },
            hint = ScanHint.AlreadyScanned(CubeColor.ORANGE, ScanStep.Right),
        )
        assertFalse(next.guidedByColor)
        assertEquals("Turn it left again", instructionFor(ScanStep.Back, 3, true, res, next.guidedByColor).title)
        assertEquals("Face 3 of 6", scanTitle(next, res))
        assertEquals("Orange face", faceName(ScanStep.Right, next, res))
        assertEquals("Face 3", faceName(ScanStep.Back, next, res))
        assertEquals("Orange's done\u00A0— turn the cube left", statusMessage(next, res).text)
        assertEquals("Redo orange", scanTitle(next.copy(currentStep = ScanStep.Right, hint = null), res))
    }

    @Test
    fun cubesWithoutFixedCentersAreTalkedAboutByPosition() {
        val sixteen = List(16) { CubeColor.BLUE }
        assertEquals(StatusMessage("Hold still…", StatusTone.Good), statusMessage(ScanUiState(size = 4, liveColors = sixteen), res))
        assertEquals("Line it up, then tap the shutter.", statusMessage(ScanUiState(size = 4, liveColors = sixteen, autoCapture = false), res).text)

        val turnNext = ScanUiState(
            size = 4,
            currentStep = ScanStep.Right,
            captures = captured(ScanStep.Front, size = 4),
            captureCount = 1,
            lastCaptured = ScanStep.Front,
            liveColors = sixteen,
            hint = ScanHint.SameAsCaptured(ScanStep.Front),
        )
        assertEquals(StatusMessage("Same as face 1\u00A0— turn the cube left", StatusTone.Warning), statusMessage(turnNext, res))
        assertEquals(
            "Same as face 4\u00A0— tip the top toward you",
            statusMessage(turnNext.copy(currentStep = ScanStep.Top, lastCaptured = ScanStep.Left, hint = ScanHint.SameAsCaptured(ScanStep.Left)), res).text,
        )
        assertEquals(
            "Same as face 3\u00A0— flip it over",
            statusMessage(turnNext.copy(currentStep = ScanStep.Bottom, lastCaptured = ScanStep.Top, hint = ScanHint.SameAsCaptured(ScanStep.Back)), res).text,
        )
        assertEquals(
            "You already scanned this side as face 1",
            statusMessage(turnNext.copy(lastCaptured = ScanStep.Back), res).text,
        )
    }

    @Test
    fun lookAlikesAreReportedGentlyUntilOneIsRedone() {
        val pair = LookAlike(ScanStep.Front, ScanStep.Right)
        val state = ScanUiState(size = 2, currentStep = ScanStep.Back, captures = captured(ScanStep.Front, ScanStep.Right, size = 2), lookAlikePairs = listOf(pair))
        assertEquals("Faces 1 and 2 look alike. Scanned one twice? Tap it to redo.", lookAlikeWarning(state, res))
        assertEquals(pair, shownLookAlike(state))
        assertNull(lookAlikeWarning(state.copy(currentStep = ScanStep.Right), res))
        assertNull(lookAlikeWarning(state.copy(lookAlikePairs = emptyList()), res))
    }

    @Test
    fun theWarningNamesTwoFacesThatReallyLookAlike() {
        // Faces 1 and 3 look alike, and so do 2 and 4: never "Faces 1 and 2".
        val state = ScanUiState(
            size = 2,
            currentStep = ScanStep.Top,
            captures = captured(ScanStep.Front, ScanStep.Right, ScanStep.Back, ScanStep.Left, size = 2),
            lookAlikePairs = listOf(LookAlike(ScanStep.Front, ScanStep.Back), LookAlike(ScanStep.Right, ScanStep.Left)),
        )
        assertEquals("Faces 1 and 3 look alike. Scanned one twice? Tap it to redo.", lookAlikeWarning(state, res))
        assertEquals(setOf(ScanStep.Front, ScanStep.Right, ScanStep.Back, ScanStep.Left), state.lookAlikes)
        // Redoing face 1: the other pair is still worth a word.
        assertEquals("Faces 2 and 4 look alike. Scanned one twice? Tap it to redo.", lookAlikeWarning(state.copy(currentStep = ScanStep.Front), res))
    }

    @Test
    fun redoingALookAlikeThatStillLooksAlikeOffersTheShutter() {
        val state = ScanUiState(
            size = 2,
            currentStep = ScanStep.Right,
            captures = captured(ScanStep.Front, ScanStep.Right, ScanStep.Back, size = 2),
            captureCount = 3,
            lastCaptured = ScanStep.Back,
            liveColors = List(4) { CubeColor.RED },
            hint = ScanHint.SameAsCaptured(ScanStep.Front),
            lookAlikePairs = listOf(LookAlike(ScanStep.Front, ScanStep.Right)),
        )
        assertEquals(StatusMessage("Looks like face 1. Different\u00A0side? Tap the shutter.", StatusTone.Warning), statusMessage(state, res))
        // Like a face that wasn't reported: a plain repeat.
        assertEquals("You already scanned this side as face 3", statusMessage(state.copy(hint = ScanHint.SameAsCaptured(ScanStep.Back)), res).text)
    }

    @Test
    fun theGuideReadsSmallFacesStickerByStickerAndSumsUpBigOnes() {
        assertEquals("Scan frame", guideDescription(3, null, complete = false, res))
        assertEquals("All faces scanned", guideDescription(7, List(49) { CubeColor.RED }, complete = true, res))
        val small = listOf(CubeColor.GREEN, CubeColor.RED, CubeColor.WHITE, CubeColor.YELLOW)
        assertEquals("Scan frame. Seeing green, red, white, yellow", guideDescription(2, small, complete = false, res))

        val four = List(16) { if (it < 9) CubeColor.WHITE else if (it < 13) CubeColor.RED else CubeColor.BLUE }
        assertEquals("Scan frame. Seeing 9 white, 4 red, 3 blue", guideDescription(4, four, complete = false, res))
        // A 7×7 face: its center, then each color once, most common first (ties in a fixed order).
        val seven = List(49) { if (it == 24) CubeColor.GREEN else listOf(CubeColor.ORANGE, CubeColor.YELLOW)[it % 2] }
        assertEquals("Scan frame. Green center. Seeing 24 yellow, 24 orange, 1 green", guideDescription(7, seven, complete = false, res))
    }

    @Test
    fun colorGuidedStepsForOddSizes() {
        for (n in listOf(3, 5, 7)) {
            val first = instructionFor(ScanStep.Front, n, followsPreviousStep = true, res)
            assertEquals("Green center facing you", first.title)
            assertEquals("Fill the frame with the green face.", first.cue)
            assertEquals(Hold.TopColor(CubeColor.WHITE, "White on top"), first.hold)
        }
        assertEquals("Turn the cube to the left.", instructionFor(ScanStep.Right, 3, true, res).cue)
        assertEquals("Turn left once more, then tilt the top toward you.", instructionFor(ScanStep.Top, 3, true, res).cue)
        assertEquals("Blue on top", instructionFor(ScanStep.Top, 3, true, res).hold.text)
        assertEquals("Flip the cube over toward you.", instructionFor(ScanStep.Bottom, 3, true, res).cue)
        assertEquals("Turn the cube until blue faces you.", instructionFor(ScanStep.Back, 5, false, res).cue)
    }

    @Test
    fun positionalStepsForEvenSizes() {
        for (n in listOf(2, 4, 6)) {
            val steps = ScanStep.entries.map { instructionFor(it, n, followsPreviousStep = true, res) }
            assertEquals(
                listOf(
                    "Pick any side as the front",
                    "Turn the cube left",
                    "Turn it left again",
                    "One more turn left",
                    "Tip the top toward you",
                    "Flip it so the bottom faces you",
                ),
                steps.map { it.title },
            )
            assertEquals("Remember which side is on top.", steps[0].cue)
            assertEquals("Back to your first face, then tip the top toward you.", steps[4].cue)
            assertEquals(
                listOf("Keep that side on top", "Same side on top", "Same side on top", "Same side on top", "First face at the bottom", "First face on top"),
                steps.map { it.hold.text },
            )
            assertEquals(Hold.FirstFace(onTop = false, "First face at the bottom"), steps[4].hold)
            // No colors anywhere: an even cube has no fixed centers to name.
            for (instruction in steps) {
                for (color in CubeColor.entries) assertTrue(instruction.toString(), color.displayName !in instruction.title + instruction.cue)
            }
        }
        val redo = instructionFor(ScanStep.Top, 4, followsPreviousStep = false, res)
        assertEquals("Top side toward you", redo.title)
        assertEquals("Tip it so your first face is at the bottom.", redo.cue)
    }

    @Test
    fun facesAreNamedByColorOrByNumber() {
        assertEquals("Green face", faceName(ScanStep.Front, ScanUiState(), res))
        assertEquals("Face 5", faceName(ScanStep.Top, ScanUiState(size = 6), res))
    }
}
