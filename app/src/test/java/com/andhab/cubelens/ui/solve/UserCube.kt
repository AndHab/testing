package com.andhab.cubelens.ui.solve

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move

/** The user's real scanned cube and a solution for it, shared by the solve screen tests. */
internal object UserCube {

    /** The cube as the user scanned it. */
    val cube: FaceletCube = FaceletCube.parse("DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR")

    /** Start colors for playback. */
    val startColors: List<CubeColor> = cube.toColors()

    /** A 20-move solution found by the app's solver (verified by the tests). */
    val solution: List<Move> = Move.parseSequence("L D2 F2 R B2 D2 F2 R2 B2 L F2 D R' B2 R2 D' B' L' U' F'")

    /**
     * Independent reference for "startColors with moves[0 until k] applied", computed by permuting
     * sticker colors directly instead of going through the playback's own cube model.
     */
    fun colorsAfter(k: Int, start: List<CubeColor> = startColors, moves: List<Move> = solution): List<CubeColor> =
        moves.take(k).fold(start) { colors, move ->
            val p = move.permutation
            List(Facelets.COUNT) { colors[p[it]] }
        }
}
