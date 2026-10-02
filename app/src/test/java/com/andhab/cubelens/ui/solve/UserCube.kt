package com.andhab.cubelens.ui.solve

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.core.nxn.NxNSolver
import com.andhab.cubelens.core.nxn.SolveStage
import com.andhab.cubelens.core.nxn.toLayerMove
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A scrambled cube and a solution for it, with an independent reference for every step. */
internal class SolveFixture(val startColors: List<CubeColor>, val solution: NxNSolution) {
    val n: Int = solution.n

    /** The solution's moves, stage after stage. */
    val moves: List<LayerMove> = solution.moves

    /**
     * Independent reference for "startColors with moves[0 until k] applied" (index k), computed by
     * permuting the color list sticker by sticker instead of going through the playback's cube model.
     */
    private val reference: List<List<CubeColor>> by lazy {
        val geometry = NxNGeometry.of(n)
        moves.runningFold(startColors) { colors, move ->
            val p = geometry.permutation(move)
            List(colors.size) { colors[p[it]] }
        }
    }

    fun colorsAfter(k: Int): List<CubeColor> = reference[k]

    /** The first index of a move in stage [stage] matching [predicate], or null. */
    fun firstMoveIn(stage: Int, predicate: (LayerMove) -> Boolean): Int? {
        val start = solution.stages.take(stage).sumOf { it.moves.size }
        return solution.stages[stage].moves.indexOfFirst(predicate).takeIf { it >= 0 }?.plus(start)
    }
}

/** The user's real scanned cube and a solution for it, shared by the solve screen tests. */
internal object UserCube {

    /** The cube as the user scanned it. */
    val cube: FaceletCube = FaceletCube.parse("DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR")

    /** Start colors for playback. */
    val startColors: List<CubeColor> = cube.toColors()

    /** A 20-move solution found by the app's solver (verified by the tests). */
    val solution: List<Move> = Move.parseSequence("L D2 F2 R B2 D2 F2 R2 B2 L F2 D R' B2 R2 D' B' L' U' F'")

    /** [solution] as the single stage the solver gives a 3×3. */
    val fixture = SolveFixture(startColors, NxNSolution(3, listOf(SolveStage(NxNSolver.STAGE_SOLVE, solution.map { it.toLayerMove() }))))

    /** [solution] as layer moves. */
    val moves: List<LayerMove> = fixture.moves

    fun colorsAfter(k: Int): List<CubeColor> = fixture.colorsAfter(k)
}

/**
 * Real solutions from the app's solver for repeatable scrambles of every size, computed once per
 * test run (preparing the solver takes a moment; a 7×7 solve a fraction of a second).
 */
internal object RealSolutions {
    private val prepared: Unit by lazy { NxNSolver.prepare() }
    private val cache = ConcurrentHashMap<Int, SolveFixture>()

    /** A scrambled [n]×[n] cube in standard colors and the solver's staged solution for it. */
    fun of(n: Int): SolveFixture = cache.getOrPut(n) {
        prepared
        NxNSolver.prepareSize(n)
        val scrambled = NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(SEED + n)))
        SolveFixture(scrambled.toColors(), NxNSolver.solve(scrambled))
    }

    private const val SEED = 2026_1002
}
