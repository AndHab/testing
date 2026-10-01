package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.FaceletCube
import org.junit.Assert.assertTrue

/** Shared state for solver tests: the tables are built once per test JVM. */
internal object SolverFixture {

    /** The tables of the shared [TwoPhaseSolver], prepared on first use. */
    val tables: SolverTables by lazy { TwoPhaseSolver.preparedTables() }

    /** Every edge flipped in place, nothing else changed: needs exactly 20 face turns. */
    val superflip: FaceletCube = CubieCube(eo = IntArray(12) { 1 }).toFaceletCube()

    /** A real cube scanned by the user. */
    val scannedCube: FaceletCube = FaceletCube.parse("DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR")

    fun assertSolves(cube: FaceletCube, solution: Solution) {
        assertTrue("'$solution' does not solve $cube", cube.apply(solution.moves).isSolved)
    }
}
