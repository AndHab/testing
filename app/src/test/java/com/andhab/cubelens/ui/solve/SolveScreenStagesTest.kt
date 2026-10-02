package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.review.ReviewSource
import com.andhab.cubelens.ui.theme.CubeLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The solve screen on big cubes with staged solutions from the app's solver: the stage header and
 * its jumps, the stage dividers in the timeline, plain-words multi-layer moves and the hint for
 * cubes without fixed centers (Robolectric, paused clock).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SolveScreenStagesTest {

    @get:Rule
    val compose = createComposeRule()

    private val fixture = RealSolutions.of(4)
    private lateinit var cube: CubeViewState
    private lateinit var playback: SolvePlayback

    @Test
    fun aCubeWithoutCentersIsHeldAsScanned() {
        show { SolveScreen(fixture.startColors, fixture.solution, onBack = {}, onDone = {}) }
        compose.onNodeWithText("Hold it like your first scan, same side on top").assertExists()
        compose.onNodeWithText("Move 1 of ${fixture.moves.size}").assertExists()
    }

    @Test
    fun aCubeWithoutCentersEnteredByHandIsHeldAsEntered() {
        show { SolveScreen(fixture.startColors, fixture.solution, onBack = {}, onDone = {}, source = ReviewSource.Manual) }
        compose.onNodeWithText("Hold your front face toward you, top face up").assertExists()
    }

    @Test
    fun aRandomScrambleWithoutCentersHasNoHoldHint() {
        show { SolveScreen(fixture.startColors, fixture.solution, onBack = {}, onDone = {}, source = null) }
        compose.onNodeWithText("Hold", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Move 1 of ${fixture.moves.size}").assertExists()
    }

    @Test
    fun theHeaderNamesTheStageAndJumpsBetweenStages() {
        showStage()
        val sizes = fixture.solution.stages.map { it.moves.size }
        compose.onNodeWithContentDescription("Corners, step 1 of 3, 0 of ${sizes[0]} moves done").assertExists()
        compose.onNodeWithContentDescription("Previous step").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Next step").performClick()
        settle()
        assertEquals(sizes[0], playback.position)
        assertEquals(fixture.colorsAfter(sizes[0]), cube.colors)
        compose.onNodeWithContentDescription("Edges, step 2 of 3, 0 of ${sizes[1]} moves done").assertExists()

        compose.onNodeWithContentDescription("Next step").performClick()
        settle()
        assertEquals(sizes[0] + sizes[1], playback.position)
        compose.onNodeWithContentDescription("Next step").assertIsNotEnabled()

        // Two moves in, "previous" goes back to the start of this stage, then to the one before.
        compose.onNodeWithContentDescription("Next move").performClick()
        settle()
        compose.onNodeWithContentDescription("Next move").performClick()
        settle()
        compose.onNodeWithContentDescription("Centers, step 3 of 3, 2 of ${sizes[2]} moves done").assertExists()
        compose.onNodeWithContentDescription("Previous step").performClick()
        settle()
        assertEquals(sizes[0] + sizes[1], playback.position)
        compose.onNodeWithContentDescription("Previous step").performClick()
        settle()
        assertEquals(sizes[0], playback.position)
        assertEquals(fixture.colorsAfter(sizes[0]), cube.colors)
    }

    // The longest stage name would be cut off beside the jump buttons on the narrowest phones: it
    // gets a line of its own under "STEP 1 OF 3" instead, in full. (Real text measuring needs the
    // native graphics.)
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun aLongStageNameGetsALineOfItsOwnOnNarrowPhones() {
        val fiveByFive = RealSolutions.of(5)
        show { SolveScreen(fiveByFive.startColors, fiveByFive.solution, onBack = {}, onDone = {}) }
        compose.onNodeWithText("STEP 1 OF 3", useUnmergedTree = true).assertExists()
        // The header's own line, and the timeline's divider.
        compose.onAllNodesWithText("Corners & middle edges", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Corners & middle edges, step 1 of 3, 0 of ${fiveByFive.solution.stages[0].moves.size} moves done")
            .assertExists()
    }

    // Where it fits, the stage stays in the one-line overline.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aShortStageNameStaysInTheOverline() {
        showStage()
        compose.onNodeWithText("STEP 1 OF 3 · CORNERS", useUnmergedTree = true).assertExists()
    }

    @Test
    fun timelineDividersJumpToTheirStage() {
        showStage()
        val third = playback.stageStart(2)
        // Item index of the third divider: the moves before it plus the two dividers before it.
        compose.onNodeWithContentDescription("Solution moves").performScrollToIndex(third + 2)
        settle()
        compose.onNodeWithContentDescription("Step 3: Centers").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(third, playback.position)
        assertEquals(fixture.colorsAfter(third), cube.colors)
    }

    @Test
    fun multiLayerMovesAreSpelledOut() {
        val wide = checkNotNull(fixture.moves.indexOfFirst { it.fromDepth == 1 && it.toDepth > 1 }.takeIf { it >= 0 })
        showStage(position = wide)
        val move = fixture.moves[wide]
        val counter = "Move ${wide + 1} of ${fixture.moves.size}"
        val layers = if (move.toDepth == 2) "${side(move.face)} two layers" else "${side(move.face)} ${move.toDepth} layers"
        val node = compose.onNodeWithContentDescription("$counter: ", substring = true)
        node.assertExists()
        val description = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        assertTrue(description, description.contains(layers))
    }

    @Test
    fun steppingToTheEndSolvesTheBigCube() {
        showStage(position = fixture.moves.size - 1)
        compose.onNodeWithContentDescription("Next move").performClick()
        settle()
        assertTrue(playback.isFinished)
        assertTrue(NxNCube.of(4, cube.colors.map { it!! }).isSolved)
        compose.onNodeWithText("Solved!").assertExists()
        compose.onNodeWithText("${fixture.moves.size} moves").assertExists()
        compose.onNodeWithText("Every layer, right back where it belongs.").assertExists()
    }

    private fun side(face: Face): String = when (face) {
        Face.U -> "Top"
        Face.D -> "Bottom"
        Face.F -> "Front"
        Face.B -> "Back"
        Face.L -> "Left"
        Face.R -> "Right"
    }

    private fun showStage(position: Int = 0) = show {
        val scope = rememberCoroutineScope()
        cube = remember { CubeViewState(fixture.startColors) }
        playback = remember {
            SolvePlayback(fixture.startColors, fixture.solution, CubeViewAnimator(cube), scope, initialPosition = position)
        }
        SolveContent(playback = playback, cubeState = cube, onBack = {}, onDone = {})
    }

    /** Renders [content] frozen (inspection mode) on a paused clock. */
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
