package com.andhab.cubelens.ui.review

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.UserCubeColors
import com.andhab.cubelens.ui.impossibleEdgeSwap
import com.andhab.cubelens.ui.twistedCorner
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
    fun entryGoesFaceByFaceInNetOrder() {
        val order = ReviewState.entryOrder(3)
        assertEquals((0 until Facelets.COUNT).toSet(), order.toSet())
        assertEquals(
            listOf(Face.U, Face.L, Face.F, Face.R, Face.B, Face.D),
            order.chunked(9).map { face -> Facelets.faceOf(face.first()) },
        )
        // After the eight stickers of the top face, entry carries on at the left face's first sticker.
        var review = ReviewState.manual()
        repeat(8) { review = review.tapColor(CubeColor.WHITE) }
        assertEquals(Facelets.index(Face.L, 0, 0), review.selected)
        // Filling the very last sticker out of turn wraps around to the first gap.
        review = review.tapSticker(Facelets.index(Face.D, 2, 2)).tapColor(CubeColor.YELLOW)
        assertEquals(Facelets.index(Face.L, 0, 0), review.selected)
        // From the back face, entry moves down to the bottom face.
        review = review.tapSticker(Facelets.index(Face.B, 2, 2)).tapColor(CubeColor.BLUE)
        assertEquals(Facelets.index(Face.D, 0, 0), review.selected)
    }

    @Test
    fun copyingTheWholeCubeMakesItValid() {
        var review = ReviewState.manual()
        for (i in ReviewState.entryOrder(3)) {
            if (!ReviewState.manual().isLocked(i)) review = review.tapColor(UserCubeColors[i])
        }
        assertEquals(UserCubeColors, review.colors)
        assertNull(review.selected)
        assertEquals(ReviewCheck.Valid, review.check)
        assertTrue(review.canSolve)
        assertTrue(review.counts.values.all { it == 9 })
    }

    @Test
    fun centersChangeOnlyOnPurpose() {
        val front = Facelets.center(Face.F)
        // The brush never paints a center: a stray tap only explains why.
        val painting = ReviewState.manual().copy(selected = null).tapColor(CubeColor.RED)
        assertEquals(CubeColor.RED, painting.brush)
        val skipped = painting.tapSticker(front)
        assertEquals(ReviewHint.CenterLocked, skipped.hint)
        assertEquals(painting.colors, skipped.colors)
        assertEquals(CubeColor.RED, skipped.brush)

        // Selected on purpose, a center takes another color (a cube arranged differently), undoably.
        val selected = ReviewState.manual().tapSticker(front)
        assertEquals(front, selected.selected)
        assertEquals(ReviewHint.CenterSelected, selected.hint)
        assertEquals(ReviewState.manual().colors, selected.colors)
        val changed = selected.tapColor(CubeColor.BLUE)
        assertEquals(CubeColor.BLUE, changed.colors[front])
        assertNull(changed.hint)
        assertTrue(changed.canUndo)
        assertEquals(CubeColor.GREEN, changed.undo().colors[front])
        // Any other action clears the hint.
        assertNull(selected.tapSticker(10).hint)
    }

    @Test
    fun aCubeWithAnotherColorArrangementCanBeEnteredByHand() {
        // Red and orange swapped: the standard centers of the right and left faces are changed.
        val mirrored = ColorScheme(
            mapOf(
                Face.U to CubeColor.WHITE, Face.D to CubeColor.YELLOW, Face.F to CubeColor.GREEN,
                Face.B to CubeColor.BLUE, Face.R to CubeColor.ORANGE, Face.L to CubeColor.RED,
            ),
        )
        val cube = FaceletCube.scrambled(listOf(Move.R1, Move.U1, Move.F2, Move.L3, Move.D1)).toColors(mirrored)
        var review = ReviewState.manual()
        for (face in listOf(Face.R, Face.L)) {
            val center = Facelets.center(face)
            review = review.tapSticker(center).tapColor(cube[center])
        }
        review = review.copy(selected = review.entryOrder.first { review.colors[it] == null })
        for (i in review.entryOrder) if (review.colors[i] == null) review = review.tapColor(cube[i])
        assertEquals(cube, review.colors)
        assertEquals(ReviewCheck.Valid, review.check)
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
        val review = ReviewState(colors, ReviewSource.Scan)
        val check = review.check
        assertTrue(check is ReviewCheck.Invalid && check.error is CubeError.ImpossibleEdge)
        val invalid = check as ReviewCheck.Invalid
        assertTrue("flagged ${invalid.flagged} should include one of $swapped", swapped.any { it in invalid.flagged })
        assertEquals(invalid.flagged, review.flagged)
        // Swapping two edge stickers breaks both edges; the title counts them.
        assertEquals(invalid.errors.count { it is CubeError.ImpossibleEdge }, invalid.sameKindCount)
        assertEquals(2, invalid.sameKindCount)
    }

    @Test
    fun problemsWithoutStickersPointAtTheHardToReadOnes() {
        val colors = twistedCorner()
        val plain = ReviewState(colors, ReviewSource.Scan)
        val check = plain.check
        assertTrue(check is ReviewCheck.Invalid && check.error == CubeError.TwistedCorner)
        assertTrue((check as ReviewCheck.Invalid).flagged.isEmpty())
        assertTrue("nothing to point at", plain.flagged.isEmpty())

        val unsure = setOf(0, 18)
        assertEquals(unsure, plain.copy(uncertain = unsure).flagged)
    }

    @Test
    fun onlyWorkInProgressNeedsAConfirmationToLeave() {
        assertFalse(ReviewState.manual().hasWorkToLose)
        assertTrue(ReviewState.manual().tapColor(CubeColor.RED).hasWorkToLose)
        assertFalse(ReviewState.manual().tapColor(CubeColor.RED).undo().hasWorkToLose)
        assertTrue("a scan took effort too", scanned().hasWorkToLose)
    }

    @Test
    fun wrongCountsAreReported() {
        val colors = UserCubeColors.toMutableList()
        val firstGreen = colors.indices.first { !ReviewState.manual().isLocked(it) && colors[it] == CubeColor.GREEN }
        colors[firstGreen] = CubeColor.RED
        val review = ReviewState(colors, ReviewSource.Scan)
        assertEquals(10, review.counts[CubeColor.RED])
        assertEquals(8, review.counts[CubeColor.GREEN])
        val check = review.check
        assertTrue(check is ReviewCheck.Invalid && check.error is CubeError.WrongColorCount)
    }

    @Test
    fun scanAnalysisBecomesAReview() {
        val analysis = scanAnalysis(
            colors = UserCubeColors,
            faceRotations = mapOf(Face.U to 0, Face.R to 1, Face.F to 4, Face.D to -1, Face.L to 2, Face.B to 0),
            uncertain = setOf(3, 4, 20),
        )
        val review = ReviewState.fromScan(analysis)
        assertEquals(ReviewSource.Scan, review.source)
        assertEquals(3, review.straightenedFaces)
        assertEquals("a fixed center is never unsure", setOf(3, 20), review.uncertain)
        assertNull(review.selected)
        assertNull(review.focusedFace)
        assertTrue(review.canSolve)
    }

    @Test
    fun aBigScanKeepsItsUnsureStickers() {
        val colors = scrambledColors(4)
        val review = ReviewState.fromScan(scanAnalysis(colors, uncertain = setOf(0, 17, 95, 96)))
        assertEquals(4, review.n)
        assertEquals("index 96 is past the cube's 96 stickers", setOf(0, 17, 95), review.uncertain)
        assertTrue(review.locked.isEmpty())
        assertEquals(ReviewCheck.Valid, review.check)
        assertTrue(review.usesFaceEditor)
    }

    @Test
    fun everySizeCountsItsOwnColors() {
        for (n in 2..7) {
            val review = ReviewState(scrambledColors(n), ReviewSource.Scan)
            assertEquals(n, review.n)
            assertEquals(n * n, review.stickersPerColor)
            assertTrue("$n: ${review.counts}", review.counts.values.all { it == n * n })
            assertEquals("$n×$n", ReviewCheck.Valid, review.check)
            assertEquals(n >= ReviewState.FACE_EDITOR_MIN_SIZE, review.usesFaceEditor)
        }
    }

    @Test
    fun aSizeThatIsNoCubeIsRejected() {
        val tooFew = List(53) { CubeColor.WHITE }
        assertTrue(runCatching { ReviewState(tooFew, ReviewSource.Scan) }.isFailure)
    }

    @Test
    fun bigCubesExplainProblemsInPlainWordsAndFlagTheStickers() {
        val colors = scrambledColors(4).toMutableList()
        val firstGreen = colors.indexOf(CubeColor.GREEN)
        colors[firstGreen] = CubeColor.RED
        val review = ReviewState(colors, ReviewSource.Scan, uncertain = setOf(5))
        val check = review.check
        assertTrue(check is ReviewCheck.InvalidNxN)
        val invalid = check as ReviewCheck.InvalidNxN
        assertTrue(invalid.errors.toString(), invalid.errors.any { "17 red" in it.message })
        assertEquals(17, review.counts[CubeColor.RED])
        assertFalse(review.canSolve)
        // A wrong count points at no sticker in particular: the unsure ones stand in.
        if (invalid.flagged.isEmpty()) assertEquals(setOf(5), review.flagged) else assertEquals(invalid.flagged, review.flagged)
    }

    @Test
    fun oddBigCubesLockTheirFixedCentersAndEvenOnesNothing() {
        val five = ReviewState.manual(5)
        assertEquals(6, five.locked.size)
        val center = five.geometry.index(Face.F, 2, 2)
        assertTrue(five.isLocked(center))
        assertEquals(ReviewHint.CenterSelected, five.tapSticker(center).hint)
        assertEquals(CubeColor.GREEN, five.colors[center])

        val four = ReviewState.manual(4)
        assertTrue(four.locked.isEmpty())
        assertTrue(four.colors.all { it == null })
        assertEquals(0, four.selected)
        assertEquals(Face.U, four.focusedFace)
        val two = ReviewState.manual(2)
        assertTrue(two.colors.all { it == null })
        assertNull("a 2×2 is edited right on the net", two.focusedFace)
    }

    @Test
    fun theFaceEditorFollowsManualEntryFromFaceToFace() {
        var review = ReviewState.manual(5)
        assertEquals(Face.U, review.focusedFace)
        repeat(24) { review = review.tapColor(CubeColor.WHITE) }
        assertEquals("the top face's 24 free stickers are done", Face.L, review.focusedFace)
        assertEquals(review.geometry.index(Face.L, 0, 0), review.selected)

        // Undo shows the change: back to the top face, its last sticker selected.
        review = review.undo()
        assertEquals(Face.U, review.focusedFace)
        assertEquals(review.geometry.index(Face.U, 4, 4), review.selected)
    }

    @Test
    fun finishingABigCubeClosesTheFaceEditorToShowTheVerdict() {
        val colors = scrambledColors(4)
        val almost = manualEntry(4, colors, stickers = 6 * 16 - 1)
        assertEquals("the last face is still open", Face.D, almost.focusedFace)
        val done = almost.tapColor(colors[checkNotNull(almost.selected)])
        assertNull(done.focusedFace)
        assertNull(done.selected)
        assertEquals(colors, done.colors)
        assertTrue(done.canSolve)

        // Recoloring a sticker of a finished cube keeps the editor open on its face.
        val fixing = done.focusFace(Face.F).tapSticker(done.geometry.index(Face.F, 1, 1)).tapColor(CubeColor.RED)
        assertEquals(Face.F, fixing.focusedFace)
    }

    @Test
    fun openingAFacePicksItsFirstGapInManualEntryOnly() {
        val manual = ReviewState.manual(4).closeFace()
        assertNull(manual.focusedFace)
        val front = manual.focusFace(Face.F)
        assertEquals(Face.F, front.focusedFace)
        assertEquals(front.geometry.index(Face.F, 0, 0), front.selected)

        val scanned = ReviewState(scrambledColors(4), ReviewSource.Scan).tapSticker(3)
        assertEquals(3, scanned.focusFace(Face.U).selected)
        assertNull("a selection on another face is dropped", scanned.focusFace(Face.B).selected)
        val painting = scanned.tapSticker(3).tapColor(CubeColor.RED).focusFace(Face.B)
        assertNull("a brush stays up instead", painting.selected)
        assertEquals(CubeColor.RED, painting.brush)
    }

    @Test
    fun steppingThroughFacesWrapsAround() {
        val review = ReviewState(scrambledColors(6), ReviewSource.Scan).focusFace(Face.U)
        assertEquals(ReviewState.FACE_ORDER[1], review.stepFace(1).focusedFace)
        assertEquals(Face.D, review.stepFace(-1).focusedFace)
        assertEquals(Face.U, review.stepFace(6).focusedFace)
        assertNull(review.closeFace().stepFace(1).focusedFace)
        assertNull(review.clearTools().focusedFace)
    }

    @Test
    fun entryOrderCoversEveryStickerFaceByFace() {
        for (n in 2..7) {
            val order = ReviewState.entryOrder(n)
            assertEquals((0 until 6 * n * n).toList(), order.sorted())
            val geometry = ReviewState.manual(n).geometry
            assertEquals(ReviewState.FACE_ORDER, order.chunked(n * n).map { face -> geometry.faceOf(face.first()) })
        }
    }

    private fun scanned(uncertain: Set<Int> = emptySet()) =
        ReviewState(UserCubeColors, ReviewSource.Scan, uncertain = uncertain)
}
