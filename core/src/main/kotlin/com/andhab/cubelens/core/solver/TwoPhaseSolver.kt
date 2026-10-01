package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import java.io.File

/** A sequence of face turns that solves a cube. */
data class Solution(val moves: List<Move>) {
    val length: Int get() = moves.size
    override fun toString(): String = Move.format(moves)
}

/** Thrown when asked to solve a state that is not a real cube. */
class UnsolvableCubeException(val errors: List<CubeError>) :
    IllegalArgumentException("Cube cannot be solved: " + errors.joinToString("; ") { it.message })

/**
 * Herbert Kociemba's two-phase algorithm.
 *
 * STUB: the real implementation replaces the bodies below while keeping these signatures.
 */
object TwoPhaseSolver {

    /** True once move and pruning tables are ready. */
    val isPrepared: Boolean
        get() = false

    /**
     * Builds the move and pruning tables, or loads them from [cacheFile] when present and valid
     * (writing them there after building otherwise). Thread-safe and idempotent.
     */
    fun prepare(cacheFile: File? = null) {
        TODO("Two-phase solver not implemented yet")
    }

    /**
     * Returns a short solution for [cube]: searches until a solution of at most [targetLength]
     * moves is found or [timeoutMillis] has elapsed, then returns the shortest found so far.
     * Prepares tables first if needed.
     *
     * @throws UnsolvableCubeException if [cube] is not a valid cube.
     */
    fun solve(cube: FaceletCube, targetLength: Int = 20, timeoutMillis: Long = 2_000): Solution {
        TODO("Two-phase solver not implemented yet")
    }
}
