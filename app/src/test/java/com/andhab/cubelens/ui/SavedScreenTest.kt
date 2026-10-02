package com.andhab.cubelens.ui

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.core.nxn.NxNSolver
import com.andhab.cubelens.core.nxn.SolveStage
import com.andhab.cubelens.ui.review.ReviewState
import com.andhab.cubelens.ui.review.focusFace
import com.andhab.cubelens.ui.review.tapColor
import com.andhab.cubelens.ui.theme.StickerPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [SavedScreen]: every screen comes back as it was, and anything else is ignored. */
class SavedScreenTest {

    private val pastel = StickerPalette.fromArgb(PastelLook)

    private val manual7 = Screen.Review(
        ReviewState.manual(7).let { review -> (0 until 40).fold(review) { state, k -> state.tapColor(CubeColor.entries[k % 6]) } }.focusFace(Face.R),
    )

    private val solution = NxNSolution(
        5,
        listOf(
            SolveStage(NxNSolver.STAGE_FRAME, listOf(LayerMove.outer(Face.R, 1), LayerMove.outer(Face.U, 3))),
            SolveStage(NxNSolver.STAGE_EDGES, listOf(LayerMove(Face.L, 2, 2, 2), LayerMove(Face.F, 1, 3, 1))),
            SolveStage("Somé other stage", listOf(LayerMove(Face.D, 1, 5, 3))),
        ),
    )

    @Test
    fun everyScreenRoundTrips() {
        val review = Screen.Review(ReviewState.manual(5).tapColor(CubeColor.RED), pastel)
        val screens = listOf(
            Screen.Home,
            Screen.Scan(size = 4),
            Screen.Scan(size = 5, returnTo = review),
            review,
            manual7,
            Screen.Solve(scrambledCube(5).toColors(), solution, returnTo = review),
            Screen.Solve(scrambledCube(5).toColors(), solution, returnTo = null, palette = pastel),
        )
        for (screen in screens) assertEquals(screen, SavedScreen.decode(SavedScreen.encode(screen)))
    }

    @Test
    fun anythingElseIsIgnored() {
        assertNull(SavedScreen.decode(intArrayOf()))
        assertNull(SavedScreen.decode(intArrayOf(99, 0)))
        assertNull(SavedScreen.decode(intArrayOf(1, 9)))
        assertNull("not a supported size", SavedScreen.decode(intArrayOf(1, 1, 12, 0)))
        val full = SavedScreen.encode(Screen.Solve(scrambledCube(5).toColors(), solution, returnTo = manual7))
        assertNull("cut short", SavedScreen.decode(full.copyOf(full.size - 1)))
        assertNull("trailing data", SavedScreen.decode(full + 0))
    }
}
