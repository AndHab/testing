package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.theme.CubeLensTheme
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import com.andhab.cubelens.ui.theme.StickerPalette
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The solve screen at key moments: the user's real 3×3 and its 20-move solution, and real staged
 * solutions of 2×2 to 7×7 cubes. PNGs land in app/build/outputs/roborazzi/solve_*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SolveScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val user = UserCube.fixture
    private val moves = UserCube.moves

    @Test
    fun start() {
        assertTrue(UserCube.cube.apply(UserCube.solution).isSolved)
        shot("solve_start") {
            SolveScreen(startColors = UserCube.startColors, moves = UserCube.solution, onBack = {}, onDone = {})
        }
    }

    @Test
    fun midSolution() = shot("solve_mid") {
        // Six moves done, the seventh (F2) caught a little under half way through its turn.
        Stage(user, position = 6, turnProgress = 0.42f)
    }

    @Test
    fun counterClockwiseMove() = shot("solve_mid_prime") {
        Stage(user, position = moves.indexOf(LayerMove.parse("R'")))
    }

    // The solved cube spins slowly; capture it early in the spin, still at the three-quarter view.
    @Test
    fun finished() = shot("solve_finished", settleMillis = 120) { Stage(user, position = moves.size) }

    @Test
    fun alreadySolved() = shot("solve_already_solved", settleMillis = 120) {
        Stage(SolveFixture(FaceletCube.SOLVED.toColors(), NxNSolution(3, emptyList())), position = 0)
    }

    @Test
    fun smallPhone() = shot("solve_small_phone", qualifiers = "w360dp-h740dp-xxhdpi") { Stage(user, position = 3) }

    // The longest instruction (D′: "Bottom face", "Counter-clockwise", "Seen from below") with the
    // system text size turned up: the words wrap instead of being cut off.
    @Test
    fun largeText() = shot("solve_large_text", qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 1.3f) {
        Stage(user, position = UserCube.solution.indexOf(Move.D3))
    }

    // The narrowest phones (or "Display size: Large"): compact panel and controls inside the gutter.
    @Test
    fun narrowPhone() = shot("solve_narrow", qualifiers = "w320dp-h640dp-xxhdpi") {
        Stage(user, position = UserCube.solution.indexOf(Move.D2))
    }

    @Test
    fun narrowPhoneLargeText() = shot("solve_narrow_large_text", qualifiers = "w320dp-h640dp-xxhdpi", fontScale = 1.3f) {
        Stage(user, position = UserCube.solution.indexOf(Move.B3))
    }

    // Paused on B′: the back layer caught at the height of its "this way" nudge.
    @Test
    fun turnHint() = shot("solve_hint") {
        val position = UserCube.solution.indexOf(Move.B3)
        Stage(user, position = position, turnProgress = TurnHint.progressFor(moves[position], TurnHint.DEGREES))
    }

    // A knock-off cube with pastel stickers: the cube, the hint swatches and the pictogram in its own colors.
    @Test
    fun pastel() = shot("solve_3x3_pastel") {
        CompositionLocalProvider(LocalStickerPalette provides Pastel) {
            Stage(user, position = 6, turnProgress = 0.42f)
        }
    }

    @Test
    fun pastelFinished() = shot("solve_3x3_pastel_finished", settleMillis = 120) {
        CompositionLocalProvider(LocalStickerPalette provides Pastel) { Stage(user, position = moves.size) }
    }

    @Test
    fun twoByTwoStart() {
        val fixture = RealSolutions.of(2)
        shot("solve_2x2_start") { SolveScreen(fixture.startColors, fixture.solution, onBack = {}, onDone = {}) }
    }

    // A wide move a little under half way, with the stage header over it.
    @Test
    fun fourByFourMidWideMove() {
        val fixture = RealSolutions.of(4)
        val wide = checkNotNull(fixture.firstMoveIn(1) { it.fromDepth == 1 && it.toDepth > 1 } ?: fixture.firstMoveIn(2) { it.fromDepth == 1 && it.toDepth > 1 })
        shot("solve_4x4_mid_wide") { Stage(fixture, position = wide, turnProgress = 0.4f) }
    }

    @Test
    fun fiveByFiveMidInnerSlice() {
        val fixture = RealSolutions.of(5)
        val slice = checkNotNull(fixture.firstMoveIn(1) { it.fromDepth > 1 && it.fromDepth == it.toDepth })
        shot("solve_5x5_mid_slice") { Stage(fixture, position = slice, turnProgress = 0.5f) }
    }

    // Deep into the centers of a 7×7 at 4× speed.
    @Test
    fun sevenBySevenFast() {
        val fixture = RealSolutions.of(7)
        val centers = fixture.solution.stages.lastIndex
        val position = checkNotNull(fixture.firstMoveIn(centers) { it.fromDepth > 1 }) + 40
        shot("solve_7x7_mid_fast") { Stage(fixture, position = position, turnProgress = 0.3f, speed = PlaybackSpeed.Faster) }
    }

    @Test
    fun fourByFourFinished() {
        val fixture = RealSolutions.of(4)
        shot("solve_4x4_finished", settleMillis = 120) { Stage(fixture, position = fixture.moves.size) }
    }

    // A layer range on the narrowest phones.
    @Test
    fun sixBySixRangeNarrow() {
        val fixture = RealSolutions.of(6)
        val range = fixture.moves.indexOfFirst { it.fromDepth > 1 && it.toDepth > it.fromDepth }.takeIf { it >= 0 }
            ?: fixture.moves.indexOfFirst { !it.isOuter }
        shot("solve_6x6_range_narrow", qualifiers = "w320dp-h640dp-xxhdpi") { Stage(fixture, position = range) }
    }

    @Test
    fun fourByFourNarrowLargeText() {
        val fixture = RealSolutions.of(4)
        val slice = checkNotNull(fixture.firstMoveIn(1) { it.fromDepth > 1 })
        shot("solve_4x4_narrow_large_text", qualifiers = "w320dp-h640dp-xxhdpi", fontScale = 1.3f) { Stage(fixture, position = slice) }
    }

    @Test
    fun fiveByFiveSmallPhoneLargeText() {
        val fixture = RealSolutions.of(5)
        val slice = checkNotNull(fixture.firstMoveIn(2) { it.fromDepth > 1 && it.face == Face.D } ?: fixture.firstMoveIn(2) { it.fromDepth > 1 })
        shot("solve_5x5_large_text", qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 1.3f) { Stage(fixture, position = slice) }
    }

    /** A playback of [fixture] frozen at [position], optionally with the next move [turnProgress] of the way through. */
    @Composable
    private fun Stage(
        fixture: SolveFixture,
        position: Int,
        turnProgress: Float? = null,
        speed: PlaybackSpeed = PlaybackSpeed.Normal,
    ) {
        val scope = rememberCoroutineScope()
        val cubeState = remember { CubeViewState(fixture.startColors) }
        val playback = remember {
            SolvePlayback(
                fixture.startColors,
                fixture.solution,
                CubeViewAnimator(cubeState),
                scope,
                initialPosition = position,
                initialSpeed = speed,
            )
        }
        remember { if (turnProgress != null) cubeState.setPreview(fixture.moves[position], turnProgress) }
        SolveContent(playback = playback, cubeState = cubeState, onBack = {}, onDone = {})
    }

    /**
     * Renders [content] frozen (inspection mode, paused clock), lets it settle for [settleMillis] and
     * captures it, optionally on a device with other [qualifiers] or system [fontScale].
     */
    private fun shot(
        name: String,
        qualifiers: String? = null,
        fontScale: Float? = null,
        settleMillis: Long = 1_500,
        content: @Composable () -> Unit,
    ) {
        if (qualifiers != null) RuntimeEnvironment.setQualifiers(qualifiers)
        if (fontScale != null) RuntimeEnvironment.setFontScale(fontScale)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CubeLensTheme(content)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(settleMillis)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private companion object {
        /** A knock-off cube with pastel stickers. */
        val Pastel = StickerPalette(
            mapOf(
                CubeColor.WHITE to Color(0xFFF7F5EE),
                CubeColor.YELLOW to Color(0xFFF3E58A),
                CubeColor.GREEN to Color(0xFF9EDDB0),
                CubeColor.BLUE to Color(0xFF93BFEA),
                CubeColor.RED to Color(0xFFF2A0B4),
                CubeColor.ORANGE to Color(0xFFF7BE92),
            ),
        )
    }
}
