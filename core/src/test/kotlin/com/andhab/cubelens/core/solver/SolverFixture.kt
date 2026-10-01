package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertTrue

/** Shared state for solver tests: the tables are built once per test JVM. */
internal object SolverFixture {

    /** The tables of the shared [TwoPhaseSolver], prepared on first use. */
    val tables: SolverTables by lazy { TwoPhaseSolver.preparedTables() }

    /** Every edge flipped in place, nothing else changed: needs exactly 20 face turns. */
    val superflip: FaceletCube = CubieCube(eo = IntArray(12) { 1 }).toFaceletCube()

    /** A real cube scanned by the user. */
    val scannedCube: FaceletCube = FaceletCube.parse("DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR")

    /**
     * A copy of [tables] whose twist move table has the columns of U and R swapped, like tables made
     * for a different move geometry. Every value is still in range.
     */
    fun tablesWithSwappedTwistMoves(): SolverTables =
        copyOfTables(twistMove = swapColumns(tables.twistMove, SolverTables.N_MOVES, Move.U1.ordinal, Move.R1.ordinal))

    /** A copy of [tables] with the given tables replaced. */
    fun copyOfTables(
        twistMove: CharArray = tables.twistMove,
        cornerPermMove: CharArray = tables.cornerPermMove,
        sliceFlipPrune: ByteArray = tables.sliceFlipPrune,
    ): SolverTables = SolverTables(
        twistMove = twistMove,
        flipMove = tables.flipMove,
        sliceSortedMove = tables.sliceSortedMove,
        cornerPermMove = cornerPermMove,
        udEdgePermMove = tables.udEdgePermMove,
        slicePermMove = tables.slicePermMove,
        sliceTwistPrune = tables.sliceTwistPrune,
        sliceFlipPrune = sliceFlipPrune,
        twistFlipPrune = tables.twistFlipPrune,
        cornerSlicePrune = tables.cornerSlicePrune,
        edgeSlicePrune = tables.edgeSlicePrune,
    )

    /** A copy of the move table [table] with the columns of moves [a] and [b] swapped. */
    fun swapColumns(table: CharArray, moveCount: Int, a: Int, b: Int): CharArray {
        val copy = table.copyOf()
        for (row in copy.indices step moveCount) {
            copy[row + a] = table[row + b]
            copy[row + b] = table[row + a]
        }
        return copy
    }

    fun assertSolves(cube: FaceletCube, solution: Solution) {
        assertTrue("'$solution' does not solve $cube", cube.apply(solution.moves).isSolved)
    }
}
