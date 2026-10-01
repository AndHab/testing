package com.andhab.cubelens.ui

import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.solver.TwoPhaseSolver
import java.io.File

/**
 * What the app needs from a cube solver. Both functions block the calling thread, so call them off
 * the main thread. Lets tests substitute a fake.
 */
interface CubeSolver {
    /** Gets the solver ready (e.g. loads its tables) so that the first [solve] is quick. */
    fun prepare()

    /**
     * Returns moves that solve [cube].
     *
     * @throws com.andhab.cubelens.core.solver.UnsolvableCubeException if [cube] is not a real cube.
     */
    fun solve(cube: FaceletCube): List<Move>
}

/**
 * [CubeSolver] backed by [TwoPhaseSolver], caching its tables (about 11 MB) in [cacheFile] so later
 * launches load them instead of building them again.
 */
class TwoPhaseCubeSolver(private val cacheFile: File?) : CubeSolver {
    override fun prepare() = TwoPhaseSolver.prepare(cacheFile)

    override fun solve(cube: FaceletCube): List<Move> = TwoPhaseSolver.solve(cube).moves
}
