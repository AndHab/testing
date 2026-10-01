package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.theme.CubeLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The solve screen's controls driving playback and the 3D cube (Robolectric, paused clock). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SolveScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val moves = UserCube.solution
    private lateinit var cube: CubeViewState
    private lateinit var playback: SolvePlayback

    @Test
    fun opensOnTheFirstMoveWithTheHoldHint() {
        show { SolveScreen(UserCube.startColors, moves, onBack = {}, onDone = {}) }
        compose.onNodeWithText("Hold green toward you, white on top").assertExists()
        compose.onNodeWithText("Move 1 of 20").assertExists()
        // The move panel reads as one sentence.
        compose.onNodeWithContentDescription("Move 1 of 20: L, Left face · clockwise").assertExists()
        compose.onNodeWithContentDescription("Previous move").assertIsNotEnabled()
    }

    @Test
    fun nextTurnsTheCubeAndAdvances() {
        showStage()
        compose.onNodeWithContentDescription("Next move").performClick()
        settle()
        assertEquals(1, playback.position)
        assertEquals(UserCube.colorsAfter(1), cube.colors)
        compose.onNodeWithText("Move 2 of 20").assertExists()

        compose.onNodeWithContentDescription("Previous move").performClick()
        settle()
        assertEquals(0, playback.position)
        assertEquals(UserCube.startColors, cube.colors)
    }

    @Test
    fun tappingAMoveInTheTimelineJumpsThere() {
        showStage()
        val target = moves.indexOf(Move.R3)
        compose.onNodeWithContentDescription("Solution moves").performScrollToIndex(target)
        settle()
        compose.onNodeWithContentDescription("R prime").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(target, playback.position)
        assertEquals(UserCube.colorsAfter(target), cube.colors)
        settle()
        compose.onNodeWithText("Move ${target + 1} of 20").assertExists()
        compose.onNodeWithContentDescription("Move 13 of 20: R prime, Right face · counter-clockwise").assertExists()
    }

    @Test
    fun playAdvancesOnItsOwnUntilPaused() {
        showStage()
        compose.onNodeWithContentDescription("Play").performClick()
        compose.mainClock.advanceTimeBy(2_500)
        assertTrue(playback.isPlaying)
        assertTrue(playback.position >= 2)

        compose.onNodeWithContentDescription("Pause").performClick()
        settle()
        assertFalse(playback.isPlaying)
        val stoppedAt = playback.position
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(stoppedAt, playback.position)
        assertEquals(UserCube.colorsAfter(stoppedAt), cube.colors)
    }

    @Test
    fun finishingCelebratesAndOffersTheWayOut() {
        var done = 0
        showStage(position = moves.size - 1, onDone = { done++ })
        compose.onNodeWithContentDescription("Next move").performClick()
        settle()
        assertTrue(playback.isFinished)
        assertTrue(FaceletCube.fromColors(cube.colors.map { it!! })!!.isSolved)
        compose.onNodeWithText("Solved!").assertExists()
        compose.onNodeWithText("20 moves").assertExists()

        compose.onNodeWithText("Scan another cube").performClick()
        assertEquals(1, done)

        compose.onNodeWithText("Replay").performClick()
        settle()
        assertEquals(0, playback.position)
        assertEquals(UserCube.startColors, cube.colors)
        compose.onNodeWithText("Move 1 of 20").assertExists()
    }

    @Test
    fun anEmptySolutionIsAlreadySolved() {
        show { SolveScreen(FaceletCube.SOLVED.toColors(), emptyList(), onBack = {}, onDone = {}) }
        compose.onNodeWithText("Already solved!").assertExists()
        compose.onNodeWithText("Scan another cube").assertExists()
        compose.onNodeWithText("Replay").assertDoesNotExist()
    }

    @Test
    fun backLeavesTheScreen() {
        var back = 0
        show { SolveScreen(UserCube.startColors, moves, onBack = { back++ }, onDone = {}) }
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    /** Shows [SolveContent] on a playback this test can inspect. */
    private fun showStage(
        position: Int = 0,
        start: List<CubeColor> = UserCube.startColors,
        onDone: () -> Unit = {},
    ) = show {
        val scope = rememberCoroutineScope()
        cube = remember { CubeViewState(start) }
        playback = remember {
            SolvePlayback(start, moves, CubeViewAnimator(cube), scope, initialPosition = position)
        }
        SolveContent(playback = playback, cubeState = cube, onBack = {}, onDone = onDone)
    }

    /**
     * Renders [content] with the backdrop frozen; the clock is advanced by hand because the solved
     * cube's victory spin never lets the UI go idle.
     */
    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CubeLensTheme(content)
            }
        }
        settle()
    }

    /** Long enough for any turn, transition or scroll to finish. */
    private fun settle() = compose.mainClock.advanceTimeBy(1_500)
}
