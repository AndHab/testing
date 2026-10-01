package com.andhab.cubelens.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.vision.ScanAnalysis
import com.andhab.cubelens.ui.UserCubeColors
import com.andhab.cubelens.ui.impossibleEdgeSwap
import com.andhab.cubelens.ui.screenshot
import com.andhab.cubelens.ui.twistedCorner
import org.junit.Assert.assertEquals
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
        val analysis = ScanAnalysis(
            rawColors = UserCubeColors,
            colors = UserCubeColors,
            faceRotations = Face.entries.associateWith { if (it in setOf(Face.R, Face.D, Face.B)) 1 else 0 },
            isValid = true,
            uncertain = unsure,
        )
        val review = ReviewState.fromScan(analysis)
        assertTrue(review.canSolve)
        compose.screenshot("review_scan_valid") { Review(review) }
    }

    @Test
    fun manualPartlyFilled() {
        compose.screenshot("review_manual_partial", advanceMillis = 1600) { Review(manualEntry(stickers = 19)) }
    }

    /** A small phone: the palette stays pinned under the net, the preview shrinks. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun manualOnASmallPhone() {
        compose.screenshot("review_compact_manual", advanceMillis = 1600) { Review(manualEntry(stickers = 30)) }
    }

    @Test
    fun leaving() {
        val review = manualEntry(stickers = 19).copy(confirmingLeave = true)
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
        val review = ReviewState(colors = swappedIntoImpossibleEdge(), source = ReviewSource.Scan)
        val check = review.check
        assertTrue(check is ReviewCheck.Invalid && check.error is CubeError.ImpossibleEdge)
        compose.screenshot("review_error_edge", advanceMillis = 300) { Review(review) }
    }

    @Test
    fun paintMode() {
        val review = ReviewState(colors = swappedIntoImpossibleEdge(), source = ReviewSource.Scan).tapColor(CubeColor.RED)
        compose.screenshot("review_paint", advanceMillis = 300) { Review(review) }
    }

    @Composable
    private fun Review(review: ReviewState) {
        ReviewScreen(
            review = review,
            onBack = {},
            onRescan = {},
            onStickerTap = {},
            onColorTap = {},
            onUndo = {},
            onSolve = {},
            onLeave = {},
            onStay = {},
        )
    }

    /** Manual entry with the first [stickers] editable stickers copied from the user's cube, in entry order. */
    private fun manualEntry(stickers: Int): ReviewState {
        var review = ReviewState.manual()
        for (i in ReviewState.ENTRY_ORDER.filterNot(ReviewState::isCenter).take(stickers)) {
            review = review.tapColor(UserCubeColors[i])
        }
        return review
    }

    private fun swappedIntoImpossibleEdge(): List<CubeColor> = impossibleEdgeSwap().first
}
