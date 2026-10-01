package com.andhab.cubelens.ui.cube

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.ui.theme.CubeLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Taps on N×N nets and the face editor (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CubeNetInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun facePlatesReportTheirFaceAndStickerTapsFallThroughToThem() {
        var face: Face? = null
        compose.setContent {
            CubeLensTheme { CubeNet(NxNCube.solved(7).toColors(), Modifier.width(371.dp), onFaceClick = { face = it }) }
        }
        compose.onNodeWithContentDescription("Right face").performClick()
        assertEquals(Face.R, face)
        // Read-only stickers let the tap reach the plate underneath.
        compose.onNodeWithContentDescription("Front face, row 4, column 4: Green").performClick()
        assertEquals(Face.F, face)
    }

    @Test
    fun stickerTapsWinOverThePlateWhenBothAreSet() {
        var face: Face? = null
        var sticker: Int? = null
        compose.setContent {
            CubeLensTheme {
                CubeNet(
                    NxNCube.solved(4).toColors(),
                    Modifier.width(371.dp),
                    onStickerClick = { sticker = it },
                    onFaceClick = { face = it },
                )
            }
        }
        // Back face (index 5), row 2, column 3 of a 4×4: 5·16 + 1·4 + 2.
        compose.onNodeWithContentDescription("Back face, row 2, column 3: Blue").performClick()
        assertEquals(86, sticker)
        assertNull(face)
    }

    @Test
    fun faceEditorStickersAreComfortableTargets() {
        var tapped = -1
        val face = NxNCube.solved(7).face(Face.U).mapIndexed { i, c -> if (i == 10) CubeColor.RED else c }
        compose.setContent {
            CubeLensTheme { FaceEditor(face, Modifier.width(371.dp), selectedSticker = 3, onStickerClick = { tapped = it }) }
        }
        compose.onNodeWithContentDescription("Row 2, column 4: Red")
            .assertWidthIsAtLeast(40.dp)
            .performClick()
        assertEquals(10, tapped)
    }

    @Test
    fun netsAndGridsInferTheirSize() {
        compose.setContent {
            CubeLensTheme {
                CubeNet(NxNCube.solved(5).toColors(), Modifier.width(371.dp))
                FaceGrid(NxNCube.solved(2).face(Face.F), Modifier.width(80.dp))
            }
        }
        compose.onNodeWithContentDescription("Bottom face, row 5, column 5: Yellow").assertExists()
        compose.onNodeWithContentDescription("Face with Green, Green, Green, Green").assertExists()
    }
}
