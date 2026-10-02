package com.andhab.cubelens.ui

import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.core.nxn.NxNSolver
import java.io.File

/**
 * What the app needs from a cube solver, for every cube size. All functions block the calling
 * thread, so call them off the main thread; they may throw [InterruptedException] when the thread
 * is interrupted (run them in `runInterruptible` to make coroutine cancellation stop them). Lets
 * tests substitute a fake.
 */
interface CubeSolver {
    /** Gets the solver ready (e.g. loads its tables) so that the first [solve] is quick. */
    fun prepare()

    /** Prepares what solving an [n]×[n] cube needs beyond [prepare], so its first [solve] is quick. */
    fun prepareSize(n: Int)

    /**
     * Returns a solution that solves [cube].
     *
     * @throws com.andhab.cubelens.core.nxn.UnsolvableNxNException if [cube] is not a real cube.
     */
    fun solve(cube: NxNCube): NxNSolution
}

/**
 * [CubeSolver] backed by [NxNSolver], caching the 3×3 tables (about 11 MB) in [cacheDir] (when
 * given) so later launches load them instead of building them again.
 */
class NxNCubeSolver(private val cacheDir: File?) : CubeSolver {
    override fun prepare() = NxNSolver.prepare(cacheDir)

    override fun prepareSize(n: Int) = NxNSolver.prepareSize(n)

    override fun solve(cube: NxNCube): NxNSolution = NxNSolver.solve(cube)
}
