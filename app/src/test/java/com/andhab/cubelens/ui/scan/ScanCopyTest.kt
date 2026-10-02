package com.andhab.cubelens.ui.scan

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The scan screen's words for each situation, as the user reads them. */
@RunWith(AndroidJUnit4::class)
class ScanCopyTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private val nine = List(9) { CubeColor.GREEN }

    private fun captured(vararg steps: ScanStep, size: Int = 3): List<List<CubeColor>?> =
        ScanStep.entries.map { if (it in steps) List(size * size) { CubeColor.RED } else null }

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
        assertEquals(StatusMessage("Looks like the red face — show green, white on top", StatusTone.Warning), statusMessage(wrong, res))

        val notTurnedYet = ScanUiState(currentStep = ScanStep.Right, liveColors = nine, hint = ScanHint.AlreadyScanned(CubeColor.GREEN, ScanStep.Front))
        assertEquals("Green's done — now show red, white on top", statusMessage(notTurnedYet, res).text)

        val wrongSpot = ScanUiState(currentStep = ScanStep.Right, liveColors = nine, hint = ScanHint.AlreadyScanned(CubeColor.RED, ScanStep.Front))
        assertEquals("Red went into green's spot. Tap green below to redo it.", statusMessage(wrongSpot, res).text)
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
        assertEquals(StatusMessage("Same as face 1 — turn the cube left", StatusTone.Warning), statusMessage(turnNext, res))
        assertEquals(
            "Same as face 4 — tip the top toward you",
            statusMessage(turnNext.copy(currentStep = ScanStep.Top, lastCaptured = ScanStep.Left, hint = ScanHint.SameAsCaptured(ScanStep.Left)), res).text,
        )
        assertEquals(
            "Same as face 3 — flip it over",
            statusMessage(turnNext.copy(currentStep = ScanStep.Bottom, lastCaptured = ScanStep.Top, hint = ScanHint.SameAsCaptured(ScanStep.Back)), res).text,
        )
        assertEquals(
            "You already scanned this side as face 1",
            statusMessage(turnNext.copy(lastCaptured = ScanStep.Back), res).text,
        )
    }

    @Test
    fun lookAlikesAreReportedUntilOneIsRedone() {
        val state = ScanUiState(size = 2, currentStep = ScanStep.Back, captures = captured(ScanStep.Front, ScanStep.Right, size = 2), lookAlikes = setOf(ScanStep.Right, ScanStep.Front))
        assertEquals("Faces 1 and 2 look the same. Tap one to redo it.", lookAlikeWarning(state, res))
        assertNull(lookAlikeWarning(state.copy(currentStep = ScanStep.Right), res))
        assertNull(lookAlikeWarning(state.copy(lookAlikes = emptySet()), res))
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
