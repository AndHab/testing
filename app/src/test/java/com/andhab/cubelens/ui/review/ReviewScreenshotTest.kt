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
        // The top face and part of the right face copied in reading order.
        var review = ReviewState.manual()
        for (i in 0 until 15) {
            if (!ReviewState.isCenter(i)) review = review.tapColor(UserCubeColors[i])
        }
        compose.screenshot("review_manual_partial", advanceMillis = 1600) { Review(review) }
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
        )
    }

    private fun swappedIntoImpossibleEdge(): List<CubeColor> = impossibleEdgeSwap().first
}
