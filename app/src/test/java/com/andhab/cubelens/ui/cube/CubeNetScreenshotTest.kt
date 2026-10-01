package com.andhab.cubelens.ui.cube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensTheme
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the 2D net and face thumbnails. PNGs land in app/build/outputs/roborazzi/cube_*.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CubeNetScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val scrambled: List<CubeColor?> =
        FaceletCube.scrambled(Move.parseSequence("R U F' L2 D B' R2 U'")).toColors()

    @Test
    fun netScrambled() = shot("cube_net_scrambled") {
        CubeNet(scrambled, Modifier.fillMaxWidth())
    }

    @Test
    fun netWithNullsFlagsAndSelection() = shot("cube_net_editing") {
        val colors = scrambled.mapIndexed { i, c -> if (i in setOf(3, 7, 30, 31, 32, 49)) null else c }
        CubeNet(
            colors,
            Modifier.fillMaxWidth(),
            highlightFacelets = setOf(2, 11, 20, 47),
            selectedFacelet = 22,
            onStickerClick = {},
        )
    }

    @Test
    fun faceGrids() = shot("cube_face_grid") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceGrid(scrambled.subList(0, 9), Modifier.width(110.dp))
            FaceGrid(scrambled.subList(18, 27), Modifier.width(110.dp), active = true)
            FaceGrid(List(9) { if (it == 4) CubeColor.RED else null }, Modifier.width(110.dp))
        }
    }

    @Test
    fun faceGridActiveLarge() = shot("cube_face_grid_active") {
        FaceGrid(scrambled.subList(Face.R.ordinal * 9, Face.R.ordinal * 9 + 9), Modifier.size(200.dp), active = true)
    }

    @Test
    fun net4x4() = shot("cube_net_4x4") {
        CubeNet(TestCubes.scrambled(4), Modifier.fillMaxWidth(), highlightFacelets = setOf(5, 37, 70), selectedFacelet = 42)
    }

    @Test
    fun net7x7Tappable() = shot("cube_net_7x7") {
        CubeNet(TestCubes.scrambled(7), Modifier.fillMaxWidth(), highlightFacelets = setOf(120), onFaceClick = {})
    }

    @Test
    fun netPastel() = shot("cube_net_pastel") {
        CompositionLocalProvider(LocalStickerPalette provides TestCubes.Pastel) {
            CubeNet(scrambled, Modifier.fillMaxWidth(), selectedFacelet = 22, onStickerClick = {})
        }
    }

    @Test
    fun faceEditor5x5() = shot("cube_face_editor_5x5") {
        val face = TestCubes.scrambled(5).subList(2 * 25, 3 * 25).mapIndexed { i, c -> if (i == 24) null else c }
        FaceEditor(face, Modifier.fillMaxWidth(), highlightStickers = setOf(3, 16), selectedSticker = 12, onStickerClick = {})
    }

    @Test
    fun faceEditor7x7Pastel() = shot("cube_face_editor_7x7_pastel") {
        CompositionLocalProvider(LocalStickerPalette provides TestCubes.Pastel) {
            FaceEditor(TestCubes.scrambled(7).subList(0, 49), Modifier.fillMaxWidth(), selectedSticker = 24, onStickerClick = {})
        }
    }

    @Test
    fun faceGridSizes() = shot("cube_face_grid_sizes") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceGrid(TestCubes.scrambled(2).subList(0, 4), Modifier.width(110.dp))
            FaceGrid(TestCubes.scrambled(4).subList(32, 48), Modifier.width(110.dp), active = true)
            FaceGrid(TestCubes.scrambled(7).subList(98, 147), Modifier.width(110.dp))
        }
    }

    @Test
    fun faceGridPastel() = shot("cube_face_grid_pastel") {
        CompositionLocalProvider(LocalStickerPalette provides TestCubes.Pastel) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FaceGrid(scrambled.subList(0, 9), Modifier.width(110.dp))
                FaceGrid(scrambled.subList(18, 27), Modifier.width(110.dp), active = true)
                FaceGrid(scrambled.subList(36, 45), Modifier.width(110.dp))
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        // Infinite animations (pulse, breathing glow) never let the UI go idle, so drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CubeLensTheme {
                Box(Modifier.width(411.dp).background(Brand.Ink).padding(20.dp)) { content() }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
