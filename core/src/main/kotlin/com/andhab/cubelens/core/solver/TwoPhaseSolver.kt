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
 * cubes within the default time budget (about 40 ms on average). Cubes that can be solved in 8
 * moves or fewer get an optimal solution.
 *
 * All functions are thread-safe; [solve] may be called concurrently from several threads. [prepare]
 * and [solve] block the calling thread for up to seconds, so call them off the main thread (e.g. on
 * `Dispatchers.Default`, with [solve] inside `runInterruptible` to make it cancellable).
 */
object TwoPhaseSolver {

    /** Longest supported time budget (about 24 days); keeps the deadline arithmetic from overflowing. */
    private const val MAX_TIMEOUT_MILLIS = Int.MAX_VALUE.toLong()

    @Volatile
    private var tables: SolverTables? = null

    /** The cache file [tables] were loaded from, or null if they were built by this process. */
    private var loadedFrom: File? = null // Guarded by this object's monitor.

    /** True once move and pruning tables are ready. */
    val isPrepared: Boolean
        get() = tables != null

    /**
     * Builds the move and pruning tables, or loads them from [cacheFile] when present and valid
     * (writing them there after building otherwise). Thread-safe and idempotent.
     *
     * Blocks the calling thread: building takes well under a second on a desktop JVM and a few
     * seconds on a slow phone, loading a cache (about 11 MB) a fraction of that. Call it early, off
     * the main thread, so that the first [solve] does not have to wait.
     *
     * A missing, outdated, truncated or corrupted cache file is rebuilt and replaced; a cache that
     * cannot be written is skipped. This never fails because of the file. If the tables are already
     * in memory, this only (re)writes [cacheFile] when it does not look like a current cache.
     */
    @Synchronized
    fun prepare(cacheFile: File? = null) {
        val current = tables
        if (current != null) {
            if (cacheFile != null && !TableCache.looksValid(cacheFile)) TableCache.tryWrite(current, cacheFile)
            return
        }
        var built = false
        val prepared = TableCache.loadOrBuild(cacheFile) { SolverTables.build().also { built = true } }
        loadedFrom = if (built) null else cacheFile
        tables = prepared
    }

    /**
     * Returns a short solution for [cube]: searches until a solution of at most [targetLength]
     * moves is found or [timeoutMillis] has elapsed, then returns the shortest found so far.
     * Prepares tables first if needed.
     *
     * Reaching [targetLength] does not end the search before all short phase 1 candidates have
     * been tried (a few milliseconds, at most a few hundred), so a nearly solved cube is not given
     * a solution longer than needed: one that can be solved in 8 moves or fewer gets an optimal
     * solution, unless [timeoutMillis] runs out first.
     *
     * Blocks the calling thread for up to [timeoutMillis] (usually far less: about 40 ms for a
     * random cube on a desktop JVM), plus the time [prepare] takes if it has not run yet. A solution
     * is always returned for a valid cube: if the time runs out before any solution has been found,
     * the search continues until the first one (typically a few milliseconds). Interrupting the
     * calling thread (e.g. a cancelled `runInterruptible` coroutine) ends the search like a timeout.
     *
     * Every solution is applied to [cube] before it is returned. Should that check fail with tables
     * loaded from a cache file, the tables are rebuilt (and the file rewritten) and the search is
     * run once more.
     *
     * @throws UnsolvableCubeException if [cube] is not a valid cube.
     * @throws IllegalStateException only on an internal error (a bug in the solver), never because
     *   of the input or a damaged cache file.
     */
    fun solve(cube: FaceletCube, targetLength: Int = 20, timeoutMillis: Long = 2_000): Solution {
        requireSolvable(cube)
        if (cube.isSolved) return Solution(emptyList())
        return solveWithRecovery(preparedTables(), cube, targetLength, timeoutMillis, ::replaceCachedTables)
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

    /**
     * Searches with [tables]. If that gives no verified solution (only possible with wrong tables),
     * asks [replace] for replacement tables and, if it provides any, searches once more with them
     * and a fresh time budget.
     *
     * @throws IllegalStateException if no verified solution was found.
     */
    internal fun solveWithRecovery(
        tables: SolverTables,
        cube: FaceletCube,
        targetLength: Int,
        timeoutMillis: Long,
        replace: (failed: SolverTables) -> SolverTables?,
    ): Solution = try {
        search(tables, cube, targetLength, timeoutMillis)
    } catch (e: IllegalStateException) {
        val replacement = replace(tables) ?: throw e
        search(replacement, cube, targetLength, timeoutMillis)
    }

    /**
     * Called after a search with [failed] gave no verified solution. If [failed] are the shared
     * tables and were loaded from a cache file that slipped past [TableCache]'s checks, rebuilds them,
     * rewrites the file and returns the new tables. If another thread already replaced them, returns
     * the current tables. Returns null if [failed] were built by this process: rebuilding would not
     * change anything.
     */
    @Synchronized
    internal fun replaceCachedTables(failed: SolverTables): SolverTables? {
        val current = tables
        if (current != null && current !== failed) return current
        val file = loadedFrom ?: return null
        val rebuilt = SolverTables.build()
        TableCache.tryWrite(rebuilt, file)
        loadedFrom = null
        tables = rebuilt
        return rebuilt
    }

    /** @throws IllegalStateException if the search ends without a solution that solves [cube]. */
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
