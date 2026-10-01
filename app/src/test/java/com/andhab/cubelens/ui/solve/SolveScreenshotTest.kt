package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.theme.CubeLensTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The solve screen at key moments of the user's real cube and its 20-move solution.
 * PNGs land in app/build/outputs/roborazzi/solve_*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SolveScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val moves = UserCube.solution

    @Test
    fun start() {
        assertTrue(UserCube.cube.apply(moves).isSolved)
        shot("solve_start") {
            SolveScreen(startColors = UserCube.startColors, moves = moves, onBack = {}, onDone = {})
        }
    }

    @Test
    fun midSolution() = shot("solve_mid") {
        // Six moves done, the seventh (F2) caught a little under half way through its turn.
        Stage(position = 6, turnProgress = 0.42f)
    }

    @Test
    fun counterClockwiseMove() = shot("solve_mid_prime") {
        Stage(position = moves.indexOf(Move.R3))
    }

    // The solved cube spins slowly; capture it early in the spin, still at the three-quarter view.
    @Test
    fun finished() = shot("solve_finished", settleMillis = 120) { Stage(position = moves.size) }

    @Test
    fun alreadySolved() = shot("solve_already_solved", settleMillis = 120) {
        Stage(position = 0, start = FaceletCube.SOLVED.toColors(), solution = emptyList())
    }

    @Test
    fun smallPhone() = shot("solve_small_phone", qualifiers = "w360dp-h740dp-xxhdpi") { Stage(position = 3) }

    // The longest instruction (D′: "Bottom face", "Counter-clockwise", "Seen from below") with the
    // system text size turned up: the words wrap instead of being cut off.
    @Test
    fun largeText() = shot("solve_large_text", qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 1.3f) {
        Stage(position = moves.indexOf(Move.D3))
    }

    // The narrowest phones (or "Display size: Large"): compact panel and controls inside the gutter.
    @Test
    fun narrowPhone() = shot("solve_narrow", qualifiers = "w320dp-h640dp-xxhdpi") {
        Stage(position = moves.indexOf(Move.D2))
    }

    @Test
    fun narrowPhoneLargeText() = shot("solve_narrow_large_text", qualifiers = "w320dp-h640dp-xxhdpi", fontScale = 1.3f) {
        Stage(position = moves.indexOf(Move.B3))
    }

    // Paused on B′: the back layer caught at the height of its "this way" nudge.
    @Test
    fun turnHint() = shot("solve_hint") {
        val move = Move.B3
        Stage(position = moves.indexOf(move), turnProgress = TurnHint.progressFor(move, TurnHint.DEGREES))
    }

    /** A playback frozen at [position], optionally with the next move [turnProgress] of the way through. */
    @Composable
    private fun Stage(
        position: Int,
        start: List<CubeColor> = UserCube.startColors,
        solution: List<Move> = moves,
        turnProgress: Float? = null,
    ) {
        val scope = rememberCoroutineScope()
        val cubeState = remember { CubeViewState(start) }
        val playback = remember {
            SolvePlayback(start, solution, CubeViewAnimator(cubeState), scope, initialPosition = position)
        }
        remember { if (turnProgress != null) cubeState.setPreview(solution[position], turnProgress) }
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
}
