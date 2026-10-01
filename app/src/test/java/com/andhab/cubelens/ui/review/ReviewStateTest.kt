package com.andhab.cubelens.ui.review

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.vision.ScanAnalysis
import com.andhab.cubelens.ui.UserCubeColors
import com.andhab.cubelens.ui.impossibleEdgeSwap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStateTest {

    @Test
    fun manualEntryStartsWithStandardCentersOnly() {
        val review = ReviewState.manual()
        for (face in Face.entries) {
            assertEquals(ColorScheme.STANDARD.colorOf(face), review.colors[Facelets.center(face)])
        }
        assertEquals(48, review.colors.count { it == null })
        assertEquals(0, review.selected)
        assertEquals(ReviewCheck.Incomplete(48), review.check)
        assertFalse(review.canSolve)
        assertTrue(review.counts.values.all { it == 1 })
    }

    @Test
    fun pickingColorsFillsStickersInReadingOrderSkippingCenters() {
        var review = ReviewState.manual()
        val picks = listOf(CubeColor.RED, CubeColor.BLUE, CubeColor.GREEN, CubeColor.ORANGE, CubeColor.YELLOW)
        for (color in picks) review = review.tapColor(color)
        assertEquals(picks, listOf(0, 1, 2, 3, 5).map { review.colors[it] })
        assertEquals(6, review.selected)
        assertEquals(ReviewCheck.Incomplete(43), review.check)
    }

    @Test
    fun copyingTheWholeCubeMakesItValid() {
        var review = ReviewState.manual()
        for (i in 0 until Facelets.COUNT) {
            if (!ReviewState.isCenter(i)) review = review.tapColor(UserCubeColors[i])
        }
        assertEquals(UserCubeColors, review.colors)
        assertNull(review.selected)
        assertEquals(ReviewCheck.Valid, review.check)
        assertTrue(review.canSolve)
        assertTrue(review.counts.values.all { it == 9 })
    }

    @Test
    fun centersAreLocked() {
        val review = ReviewState.manual().tapSticker(Facelets.center(Face.F))
        assertEquals(ReviewHint.CenterLocked, review.hint)
        assertEquals(0, review.selected)
        assertEquals(ReviewState.manual().colors, review.colors)
        // Any other action clears the hint.
        assertNull(review.tapSticker(10).hint)
    }

    @Test
    fun tappingAStickerSelectsAndTappingAgainDeselects() {
        val review = scanned()
        val selected = review.tapSticker(12)
        assertEquals(12, selected.selected)
        assertNull(selected.tapSticker(12).selected)
        assertEquals(30, selected.tapSticker(30).selected)
    }

    @Test
    fun recoloringAScannedStickerClearsTheSelection() {
        val review = scanned().tapSticker(12).tapColor(CubeColor.WHITE)
        assertEquals(CubeColor.WHITE, review.colors[12])
        assertNull(review.selected)
        assertTrue(review.canUndo)
    }

    @Test
    fun recoloringAFilledManualStickerDoesNotHop() {
        val review = ReviewState.manual().tapColor(CubeColor.RED).tapColor(CubeColor.BLUE)
            .tapSticker(0).tapColor(CubeColor.GREEN)
        assertEquals(CubeColor.GREEN, review.colors[0])
        assertNull(review.selected)
    }

    @Test
    fun paintModePaintsEveryTappedSticker() {
        var review = scanned().tapColor(CubeColor.ORANGE)
        assertEquals(CubeColor.ORANGE, review.brush)
        assertNull(review.selected)
        review = review.tapSticker(0).tapSticker(1).tapSticker(2)
        assertEquals(List(3) { CubeColor.ORANGE }, review.colors.subList(0, 3))
        assertEquals(CubeColor.ORANGE, review.brush)
        // Tapping the same color again puts the brush down; a different color swaps it.
        assertEquals(CubeColor.RED, review.tapColor(CubeColor.RED).brush)
        assertNull(review.tapColor(CubeColor.ORANGE).brush)
    }

    @Test
    fun paintingTheSameColorRecordsNoEdit() {
        val review = scanned()
        val same = review.tapColor(UserCubeColors[0]).tapSticker(0)
        assertFalse(same.canUndo)
        assertEquals(review.colors, same.colors)
    }

    @Test
    fun undoRestoresTheColorAndTheDoubleCheckMark() {
        val review = scanned(uncertain = setOf(7))
        val edited = review.tapSticker(7).tapColor(CubeColor.BLUE)
        assertFalse(7 in edited.uncertain)
        val undone = edited.undo()
        assertEquals(review.colors, undone.colors)
        assertTrue(7 in undone.uncertain)
        assertEquals(7, undone.selected)
        assertFalse(undone.canUndo)
        assertSame(undone, undone.undo())
    }

    @Test
    fun historyIsBounded() {
        var review = scanned()
        repeat(ReviewState.MAX_HISTORY + 10) { i ->
            review = review.tapSticker(0).tapColor(if (i % 2 == 0) CubeColor.RED else CubeColor.BLUE)
        }
        assertEquals(ReviewState.MAX_HISTORY, review.history.size)
    }

    @Test
    fun editsArePausedWhileSolving() {
        val solving = scanned().copy(solving = true)
        assertSame(solving, solving.tapSticker(0))
        assertSame(solving, solving.tapColor(CubeColor.RED))
        assertSame(solving, solving.undo())
    }

    @Test
    fun anImpossibleEdgeIsFlagged() {
        val (colors, swapped) = impossibleEdgeSwap()
        val check = ReviewState(colors, ReviewSource.Scan).check
        assertTrue(check is ReviewCheck.Invalid && check.error is CubeError.ImpossibleEdge)
        val flagged = (check as ReviewCheck.Invalid).flagged
        assertTrue("flagged $flagged should include one of $swapped", swapped.any { it in flagged })
    }

    @Test
    fun wrongCountsAreReported() {
        val colors = UserCubeColors.toMutableList()
        val firstGreen = colors.indices.first { !ReviewState.isCenter(it) && colors[it] == CubeColor.GREEN }
        colors[firstGreen] = CubeColor.RED
        val review = ReviewState(colors, ReviewSource.Scan)
        assertEquals(10, review.counts[CubeColor.RED])
        assertEquals(8, review.counts[CubeColor.GREEN])
        val check = review.check
        assertTrue(check is ReviewCheck.Invalid && check.error is CubeError.WrongColorCount)
    }

    @Test
    fun scanAnalysisBecomesAReview() {
        val analysis = ScanAnalysis(
            rawColors = UserCubeColors,
            colors = UserCubeColors,
            faceRotations = mapOf(Face.U to 0, Face.R to 1, Face.F to 4, Face.D to -1, Face.L to 2, Face.B to 0),
            isValid = true,
            uncertain = setOf(3, 4, 20),
        )
        val review = ReviewState.fromScan(analysis)
        assertEquals(ReviewSource.Scan, review.source)
        assertEquals(3, review.straightenedFaces)
        assertEquals(setOf(3, 20), review.uncertain)
        assertNull(review.selected)
        assertTrue(review.canSolve)
    }

    private fun scanned(uncertain: Set<Int> = emptySet()) =
        ReviewState(UserCubeColors, ReviewSource.Scan, uncertain = uncertain)
}
