package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.CubeValidator
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
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
 * Phase 1 brings the cube into the subgroup H = <U, D, R2, L2, F2, B2> (corners and edges oriented,
 * the four middle-layer edges in the middle layer); phase 2 solves it using only moves of H. Both
 * phases are IDA* searches over small integer coordinates with precomputed move and pruning tables
 * (about 11 MB in total, built in well under a second on a desktop JVM). After the first solution the
 * search keeps looking for shorter ones, trying all three cube axes and the inverse cube.
 *
 * Typical results for random cubes on a desktop JVM: a first solution of 20 to 21 moves within
 * about 5 ms, 21 moves or fewer within about 5 ms on average, and 20 moves or fewer for nearly all
 * cubes within the default time budget (about 40 ms on average).
 *
 * All functions are thread-safe; [solve] may be called concurrently from several threads.
 */
object TwoPhaseSolver {

    /** Longest supported time budget (about 24 days); keeps the deadline arithmetic from overflowing. */
    private const val MAX_TIMEOUT_MILLIS = Int.MAX_VALUE.toLong()

    @Volatile
    private var tables: SolverTables? = null

    /** True once move and pruning tables are ready. */
    val isPrepared: Boolean
        get() = tables != null

    /**
     * Builds the move and pruning tables, or loads them from [cacheFile] when present and valid
     * (writing them there after building otherwise). Thread-safe and idempotent.
     *
     * A missing, outdated, truncated or corrupted cache file is rebuilt and replaced; a cache that
     * cannot be written is skipped. If the tables are already in memory, this only (re)writes
     * [cacheFile] when it does not look like a current cache.
     */
    @Synchronized
    fun prepare(cacheFile: File? = null) {
        val current = tables
        if (current != null) {
            if (cacheFile != null && !TableCache.looksValid(cacheFile)) TableCache.tryWrite(current, cacheFile)
            return
        }
        tables = TableCache.loadOrBuild(cacheFile)
    }

    /**
     * Returns a short solution for [cube]: searches until a solution of at most [targetLength]
     * moves is found or [timeoutMillis] has elapsed, then returns the shortest found so far.
     * Prepares tables first if needed.
     *
     * A solution is always returned for a valid cube: if the time runs out before any solution has
     * been found, the search continues until the first one (typically a few milliseconds). The
     * timeout does not include building the tables if [prepare] has not run yet. Interrupting the
     * calling thread (e.g. a cancelled `runInterruptible` coroutine) ends the search like a timeout.
     *
     * @throws UnsolvableCubeException if [cube] is not a valid cube.
     */
    fun solve(cube: FaceletCube, targetLength: Int = 20, timeoutMillis: Long = 2_000): Solution {
        requireSolvable(cube)
        if (cube.isSolved) return Solution(emptyList())
        return search(preparedTables(), cube, targetLength, timeoutMillis)
    }

    /** The shared tables, preparing them (without a cache file) if needed. */
    internal fun preparedTables(): SolverTables = tables ?: run {
        prepare()
        checkNotNull(tables)
    }

    /** [solve] with explicitly given [tables] instead of the shared ones. */
    internal fun solveWith(tables: SolverTables, cube: FaceletCube, targetLength: Int, timeoutMillis: Long): Solution {
        requireSolvable(cube)
        return search(tables, cube, targetLength, timeoutMillis)
    }

    private fun search(tables: SolverTables, cube: FaceletCube, targetLength: Int, timeoutMillis: Long): Solution {
        val deadline = System.nanoTime() + timeoutMillis.coerceIn(0, MAX_TIMEOUT_MILLIS) * 1_000_000
        val moves = TwoPhaseSearch(tables, targetLength, deadline).solve(cube)
        check(cube.apply(moves).isSolved) { "Internal error: ${Move.format(moves)} does not solve $cube" }
        return Solution(moves)
    }

    /** @throws UnsolvableCubeException listing everything that is wrong with [cube]. */
    private fun requireSolvable(cube: FaceletCube) {
        val errors = mutableListOf<CubeError>()
        // A center labelled with another face means that face's color appears on two centers.
        if (Face.entries.any { cube[Facelets.center(it)] != it }) errors += CubeError.CentersNotDistinct
        errors += CubeValidator.validate(cube).errors
        if (errors.isNotEmpty()) throw UnsolvableCubeException(errors)
    }
}
