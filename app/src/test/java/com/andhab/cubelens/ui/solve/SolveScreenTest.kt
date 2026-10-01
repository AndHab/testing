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
        // The move panel reads as one sentence, with where to look from for a face pointing away.
        compose.onNodeWithContentDescription("Move 1 of 20: L, Left face · clockwise. Seen from the left").assertExists()
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

        // Replay starts over and plays the solution again straight away.
        compose.onNodeWithText("Replay").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(playback.isPlaying)
        assertEquals(0, playback.position)
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.mainClock.advanceTimeBy(SolvePlayback.TURN_MILLIS + 200L)
        assertEquals(1, playback.position)
        assertEquals(UserCube.colorsAfter(1), cube.colors)
        compose.onNodeWithText("Move 2 of 20").assertExists()
    }

    @Test
    fun aPausedCubeNudgesTheLayerThatTurnsNext() {
        showStage(frozen = false)
        // The first move's layer nudges; the cube itself is unchanged.
        awaitNudge(moves[0], UserCube.startColors)

        // Stepping on takes over cleanly from the nudge, and the next move gets its own nudge.
        compose.onNodeWithContentDescription("Next move").performClick()
        compose.mainClock.advanceTimeBy(SolvePlayback.TURN_MILLIS + 100L)
        assertEquals(1, playback.position)
        assertEquals(UserCube.colorsAfter(1), cube.colors)
        awaitNudge(moves[1], UserCube.colorsAfter(1))

        // Playing never nudges.
        compose.onNodeWithContentDescription("Play").performClick()
        repeat(40) {
            compose.mainClock.advanceTimeBy(97)
            val turning = cube.animatingMove
            assertTrue("only real turns while playing: $turning", turning == null || turning == moves[playback.position])
        }
        compose.onNodeWithContentDescription("Pause").performClick()
        settle()
        assertEquals(UserCube.colorsAfter(playback.position), cube.colors)
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

    /** Advances the clock until [move]'s layer nudges, checking the cube keeps showing [colors]. */
    private fun awaitNudge(move: Move, colors: List<CubeColor>) {
        val limit = TurnHint.FIRST_DELAY_MILLIS + TurnHint.INTERVAL_MILLIS + TurnHint.NUDGE_MILLIS
        var waited = 0L
        while (cube.animatingMove != move) {
            assertTrue("no nudge of $move within ${limit}ms", waited < limit)
            assertEquals(colors, cube.colors)
            compose.mainClock.advanceTimeBy(NUDGE_POLL_MILLIS)
            waited += NUDGE_POLL_MILLIS
        }
        assertEquals(colors, cube.colors)
    }

    /** Shows [SolveContent] on a playback this test can inspect; [frozen] as in [show]. */
    private fun showStage(
        position: Int = 0,
        start: List<CubeColor> = UserCube.startColors,
        onDone: () -> Unit = {},
        frozen: Boolean = true,
    ) = show(frozen) {
        val scope = rememberCoroutineScope()
        cube = remember { CubeViewState(start) }
        playback = remember {
            SolvePlayback(start, moves, CubeViewAnimator(cube), scope, initialPosition = position)
        }
        SolveContent(playback = playback, cubeState = cube, onBack = {}, onDone = onDone)
    }

    /**
     * Renders [content], [frozen] (inspection mode: still backdrop, no nudges) unless asked
     * otherwise; the clock is advanced by hand because the solved cube's victory spin never lets the
     * UI go idle.
     */
    private fun show(frozen: Boolean = true, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides frozen) {
                CubeLensTheme(content)
            }
        }
        settle()
    }

    /** Long enough for any turn, transition or scroll to finish. */
    private fun settle() = compose.mainClock.advanceTimeBy(1_500)

    private companion object {
        const val NUDGE_POLL_MILLIS = 50L
    }
}
