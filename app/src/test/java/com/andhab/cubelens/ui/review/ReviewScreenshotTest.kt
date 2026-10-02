package com.andhab.cubelens.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
// A star import: N×N scan resolution is an extension today and becomes a member of ScanResolver later.
import com.andhab.cubelens.core.vision.*
import com.andhab.cubelens.ui.AppViewModel
import com.andhab.cubelens.ui.PastelLook
import com.andhab.cubelens.ui.UserCubeColors
import com.andhab.cubelens.ui.impossibleEdgeSwap
import com.andhab.cubelens.ui.scansOf
import com.andhab.cubelens.ui.screenshot
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import com.andhab.cubelens.ui.theme.StickerPalette
import com.andhab.cubelens.ui.twistedCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the review screen. PNGs land in app/build/outputs/roborazzi/review_*.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReviewScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun scannedValid() {
        // Three faces were held at an angle and straightened; two reds were close calls.
        val unsure = UserCubeColors.indices.filter { it % 9 != 4 && UserCubeColors[it] == CubeColor.RED }.take(2).toSet()
        val analysis = scanAnalysis(
            colors = UserCubeColors,
            faceRotations = Face.entries.associateWith { if (it in setOf(Face.R, Face.D, Face.B)) 1 else 0 },
            uncertain = unsure,
        )
        val review = ReviewState.fromScan(analysis)
        assertTrue(review.canSolve)
        compose.screenshot("review_scan_valid") { Review(review) }
        // A 3×3's stickers are big enough to carry the center locks, so the legend explains them.
        compose.onNodeWithText("Fixed center").assertIsDisplayed()
        compose.onNodeWithText("Hard to read").assertIsDisplayed()
    }

    @Test
    fun manualPartlyFilled() {
        compose.screenshot("review_manual_partial", advanceMillis = 1600) { Review(manualEntry(3, UserCubeColors, stickers = 19)) }
    }

    /** A small phone: the palette stays pinned under the net, the preview shrinks. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun manualOnASmallPhone() {
        compose.screenshot("review_compact_manual", advanceMillis = 1600) { Review(manualEntry(3, UserCubeColors, stickers = 30)) }
        // The whole net fits: the bottom face too.
        compose.onNodeWithContentDescription("Bottom face, row 3, column 3: Not set").assertIsDisplayed()
    }

    /** A small phone with a problem: the explanation shows (the tip makes room for it). */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun problemOnASmallPhone() {
        val review = ReviewState(colors = impossibleEdgeSwap().first, source = ReviewSource.Scan)
        compose.screenshot("review_compact_error", advanceMillis = 300) { Review(review) }
        compose.onNodeWithText("Those colors never meet on a real cube. Check the marked stickers.").assertIsDisplayed()
    }

    @Test
    fun leaving() {
        val review = manualEntry(3, UserCubeColors, stickers = 19).copy(confirmingLeave = true)
        compose.screenshot("review_leave", advanceMillis = 600) { Review(review) }
    }

    @Test
    fun twistedCornerPointsAtHardToReadStickers() {
        val review = ReviewState(colors = twistedCorner(), source = ReviewSource.Scan, uncertain = setOf(0, 20, 51))
        val check = review.check
        assertTrue(check is ReviewCheck.Invalid && check.error == CubeError.TwistedCorner)
        assertEquals(review.uncertain, review.flagged)
        compose.screenshot("review_error_twist", advanceMillis = 300) { Review(review) }
    }

    @Test
    fun impossibleEdge() {
        val review = ReviewState(colors = impossibleEdgeSwap().first, source = ReviewSource.Scan)
        val check = review.check
        assertTrue(check is ReviewCheck.Invalid && check.error is CubeError.ImpossibleEdge)
        compose.screenshot("review_error_edge", advanceMillis = 300) { Review(review) }
    }

    @Test
    fun paintMode() {
        val review = ReviewState(colors = impossibleEdgeSwap().first, source = ReviewSource.Scan).tapColor(CubeColor.RED)
        compose.screenshot("review_paint", advanceMillis = 300) { Review(review) }
    }

    /** A pastel knock-off, resolved from its scans like in the app: drawn in its own colors. */
    @Test
    fun pastelCubeInItsOwnColors() {
        val analysis = ScanResolver.resolve(3, scansOf(NxNCube.of(3, UserCubeColors), PastelLook), AppViewModel.ScanOrder)
        val palette = AppViewModel.paletteOf(analysis.palette)
        assertNotEquals(StickerPalette.Standard, palette)
        val review = ReviewState.fromScan(analysis)
        compose.screenshot("review_pastel", advanceMillis = 300) {
            CompositionLocalProvider(LocalStickerPalette provides palette) { Review(review) }
        }
        compose.onNodeWithText("Your cube's colors").assertExists()
    }

    @Test
    fun twoByTwoScan() {
        val colors = scrambledColors(2)
        val review = ReviewState.fromScan(
            scanAnalysis(colors, faceRotations = Face.entries.associateWith { if (it == Face.L) 2 else 0 }),
        )
        assertTrue(review.canSolve)
        compose.screenshot("review_2x2_scan", advanceMillis = 300) { Review(review) }
    }

    @Test
    fun twoByTwoManual() {
        val review = manualEntry(2, scrambledColors(2), stickers = 9)
        compose.screenshot("review_2x2_manual", advanceMillis = 300) { Review(review) }
    }

    /** A scanned 4×4 with a few stickers the camera found hard to read. */
    @Test
    fun fourByFourScanWithUnsureStickers() {
        val colors = scrambledColors(4)
        val review = ReviewState.fromScan(scanAnalysis(colors, uncertain = setOf(5, 22, 37, 70, 83)))
        assertEquals(5, review.uncertain.size)
        compose.screenshot("review_4x4_scan_uncertain", advanceMillis = 300) { Review(review) }
    }

    /** A 4×4 with a misread sticker: the validator's own words, the culprits ringed. */
    @Test
    fun fourByFourWithAProblem() {
        val colors = scrambledColors(4).toMutableList()
        val corner = 0
        colors[corner] = if (colors[corner] == CubeColor.RED) CubeColor.ORANGE else CubeColor.RED
        val review = ReviewState(colors, ReviewSource.Scan)
        assertTrue(review.check is ReviewCheck.InvalidNxN)
        compose.screenshot("review_4x4_error", advanceMillis = 300) { Review(review) }
        // Worded as on a 3×3: a short title, and the count in the message.
        compose.onNodeWithText("Found 15. A 4×4 cube has 16 of each color.").assertIsDisplayed()
    }

    /** Manual entry on a 7×7: the second face open in the face editor, partly copied. */
    @Test
    fun sevenBySevenManualInTheFaceEditor() {
        val review = manualEntry(7, scrambledColors(7), stickers = 48 + 19)
        assertEquals(Face.L, review.focusedFace)
        compose.screenshot("review_7x7_face_editor", advanceMillis = 900) { Review(review) }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun sevenBySevenFaceEditorOnASmallPhone() {
        val review = manualEntry(7, scrambledColors(7), stickers = 48 + 19)
        compose.screenshot("review_7x7_face_editor_compact", advanceMillis = 900) { Review(review) }
        compose.onNodeWithText("Done").assertExists()
    }

    /**
     * A scanned 7×7 on a small phone with the face editor closed: the palette, status and solve
     * button stay pinned, and the net scrolls under them (as on a 3×3), each face a roomy target.
     */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun sevenBySevenNetOnASmallPhone() {
        val review = ReviewState.fromScan(scanAnalysis(scrambledColors(7), uncertain = setOf(10, 120, 200)))
        compose.screenshot("review_7x7_compact", advanceMillis = 300) { Review(review) }
        // The net's stickers are too small to carry the center locks, so the legend leaves them out.
        assertTrue(review.locked.isNotEmpty())
        assertFalse(compose.onNodeWithText("Fixed center").fetchSemanticsNode().layoutInfo.isPlaced)
        assertTrue(compose.onNodeWithText("Hard to read").fetchSemanticsNode().layoutInfo.isPlaced)
    }

    /** A scanned 5×5 with a face open: unsure dots and the locked center in the editor too. */
    @Test
    fun fiveByFiveScanInTheFaceEditor() {
        val colors = scrambledColors(5)
        val front = Face.F.ordinal * 25
        val review = ReviewState.fromScan(scanAnalysis(colors, uncertain = setOf(front + 1, front + 13, front + 18)))
            .focusFace(Face.F)
            .tapSticker(front + 6)
        compose.screenshot("review_5x5_face_editor", advanceMillis = 900) { Review(review) }
    }

    @Composable
    private fun Review(review: ReviewState) {
        ReviewScreen(
            review = review,
            onBack = {},
            onRescan = {},
            onStickerTap = {},
            onColorTap = {},
            onFaceTap = {},
            onFaceStep = {},
            onFaceClose = {},
            onUndo = {},
            onSolve = {},
            onLeave = {},
            onStay = {},
        )
    }
}
