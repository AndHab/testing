package com.andhab.cubelens.ui.cube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.toLayerMove
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Product-shot screenshots of the 3D cube in several sizes and palettes. PNGs land in
 * app/build/outputs/roborazzi/cube_*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CubeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val solved: List<CubeColor?> = FaceletCube.SOLVED.toColors()
    private val scrambled: List<CubeColor?> =
        FaceletCube.scrambled(Move.parseSequence(SCRAMBLE)).toColors()

    @Test
    fun solvedDefaultView() = shot("cube_solved") { CubeStage(solved) }

    @Test
    fun scrambled() = shot("cube_scrambled") { CubeStage(scrambled) }

    @Test
    fun midMoveR() = shot("cube_mid_R_040") { CubeStage(scrambled, preview = Move.R1.toLayerMove() to 0.4f) }

    @Test
    fun midMoveUPrime() = shot("cube_mid_Uprime_060") { CubeStage(scrambled, preview = Move.U3.toLayerMove() to 0.6f) }

    @Test
    fun midMoveF2() = shot("cube_mid_F2_050") { CubeStage(scrambled, preview = Move.F2.toLayerMove() to 0.5f) }

    @Test
    fun midSliceM() = shot("cube_mid_2R_045") { CubeStage(scrambled, preview = LayerMove.parse("2R") to 0.45f) }

    @Test
    fun twoByTwoScrambled() = shot("cube_2x2_scrambled") { CubeStage(TestCubes.scrambled(2)) }

    @Test
    fun fourByFourScrambled() = shot("cube_4x4_scrambled") { CubeStage(TestCubes.scrambled(4)) }

    @Test
    fun fourByFourMidWideR() = shot("cube_4x4_mid_Rw_045") {
        CubeStage(TestCubes.scrambled(4), preview = LayerMove.parse("Rw") to 0.45f)
    }

    @Test
    fun fiveByFiveMidInnerSlice() = shot("cube_5x5_mid_2R_050") {
        CubeStage(TestCubes.scrambled(5), preview = LayerMove.parse("2R") to 0.5f)
    }

    @Test
    fun sevenBySevenScrambled() = shot("cube_7x7_scrambled") { CubeStage(TestCubes.scrambled(7)) }

    @Test
    fun sevenBySevenMidWideU() = shot("cube_7x7_mid_3Uwprime_040") {
        CubeStage(TestCubes.scrambled(7), preview = LayerMove.parse("3Uw'") to 0.4f)
    }

    @Test
    fun sevenBySevenHighlightedAndFocused() = shot("cube_7x7_focus_F") {
        val (yaw, pitch) = viewAnglesFor(Face.F)
        CubeStage(TestCubes.scrambled(7), yaw = yaw, pitch = pitch, highlights = setOf(98, 110, 140), focus = Face.F)
    }

    @Test
    fun wholeCubeRotation() = shot("cube_4x4_mid_x_035") {
        CubeStage(TestCubes.scrambled(4), preview = LayerMove.parse("4Rw") to 0.35f)
    }

    @Test
    fun pastel() = shot("cube_pastel") {
        CompositionLocalProvider(LocalStickerPalette provides TestCubes.Pastel) { CubeStage(scrambled) }
    }

    @Test
    fun pastelMidMove() = shot("cube_pastel_mid_R_040") {
        CompositionLocalProvider(LocalStickerPalette provides TestCubes.Pastel) {
            CubeStage(scrambled, preview = Move.R1.toLayerMove() to 0.4f)
        }
    }

    @Test
    fun pastelSolvedFromBelow() = shot("cube_pastel_solved_below") {
        CompositionLocalProvider(LocalStickerPalette provides TestCubes.Pastel) {
            CubeStage(solved, yaw = 150f, pitch = -30f)
        }
    }

    @Test
    fun highlighted() = shot("cube_highlighted") {
        CubeStage(scrambled, highlights = setOf(2, 11, 20, 18, 22))
    }

    @Test
    fun focusRight() = shot("cube_focus_R") {
        val (yaw, pitch) = viewAnglesFor(Face.R)
        CubeStage(solved, yaw = yaw, pitch = pitch, focus = Face.R)
    }

    @Test
    fun unknownStickers() = shot("cube_unknown") {
        val partial = solved.mapIndexed { i, c -> if (i / 9 == Face.R.ordinal || i in setOf(0, 1, 5, 19, 25)) null else c }
        CubeStage(partial)
    }

    @Test
    fun viewPresets() {
        // All six scan-instruction views, each with its face in focus.
        val order = listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D)
        shot("cube_view_presets", width = 411, height = 290) {
            Column {
                for (row in order.chunked(3)) {
                    Row {
                        for (face in row) {
                            val (yaw, pitch) = viewAnglesFor(face)
                            val state = rememberCubeViewState(solved, yaw, pitch)
                            Cube3D(state, Modifier.size(137.dp, 145.dp), interactive = false, focusFace = face)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun heroAutoRotate() = shot("cube_hero", advanceMillis = 1200) {
        // 1.2 s of idle: about 19 degrees of spin and close to the top of the float.
        val state = rememberCubeViewState(scrambled)
        Cube3D(state, Modifier.fillMaxSize().padding(12.dp), autoRotate = true)
    }

    /**
     * Captures [content] on an ink stage after [advanceMillis] of animation time (at least one frame).
     */
    private fun shot(
        name: String,
        width: Int = 360,
        height: Int = 360,
        advanceMillis: Long = 0,
        content: @Composable () -> Unit,
    ) {
        // Infinite animations (the highlight pulse) never let the UI go idle, so drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CubeLensTheme {
                Box(Modifier.size(width.dp, height.dp).background(Brand.Ink)) { content() }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        if (advanceMillis > 0) compose.mainClock.advanceTimeBy(advanceMillis)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun CubeStage(
        colors: List<CubeColor?>,
        yaw: Float = -35f,
        pitch: Float = 28f,
        preview: Pair<LayerMove, Float>? = null,
        highlights: Set<Int> = emptySet(),
        focus: Face? = null,
    ) {
        val state = rememberCubeViewState(colors, yaw, pitch)
        remember(preview) { state.setPreview(preview?.first, preview?.second ?: 0f) }
        Cube3D(
            state,
            Modifier.fillMaxSize().padding(12.dp),
            interactive = false,
            highlightFacelets = highlights,
            focusFace = focus,
        )
    }

    private companion object {
        const val SCRAMBLE = "R U F' L2 D B' R2 U'"
    }
}
