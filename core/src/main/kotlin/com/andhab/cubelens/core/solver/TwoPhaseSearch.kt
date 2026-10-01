package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.solver.Coordinates.N_FLIP
import com.andhab.cubelens.core.solver.Coordinates.N_SLICE_PERM
import com.andhab.cubelens.core.solver.Coordinates.N_TWIST
import com.andhab.cubelens.core.solver.SolverTables.Companion.N_MOVES
import com.andhab.cubelens.core.solver.SolverTables.Companion.N_PHASE2_MOVES
import com.andhab.cubelens.core.solver.SolverTables.Companion.PHASE2_MOVES

/**
 * One run of the two-phase search. Holds all mutable search state, so every [TwoPhaseSolver.solve]
 * call uses its own instance and concurrent calls never share anything but the immutable [tables].
 *
 * The cube is searched in up to six "views": rotated so that each of its three axes becomes the
 * U-D axis, each both as given and inverted. Views are interleaved by phase 1 depth, so the shortest
 * phase 1 solutions of all views are tried first. Every phase 1 solution is followed by an IDA*
 * phase 2 search bounded by the best total length found so far, which keeps improving until
 * [targetLength] is reached, the deadline passes, or the search space is exhausted.
 *
 * @param deadlineNanos [System.nanoTime] after which the search stops, once it has any solution.
 *   Interrupting the searching thread has the same effect.
 */
internal class TwoPhaseSearch(
    private val tables: SolverTables,
    private val targetLength: Int,
    private val deadlineNanos: Long,
) {
    /** A rotated and/or inverted copy of the input cube and its phase 1 start coordinates. */
    private class View(val cubie: CubieCube, val rotation: Int, val inverse: Boolean, tables: SolverTables) {
        val twist = Coordinates.twist(cubie.co)
        val flip = Coordinates.flip(cubie.eo)
        val sliceSorted = Coordinates.sliceSorted(cubie.ep)
        val distance: Int = phase1Bound(tables, twist, flip, sliceSorted / N_SLICE_PERM)
    }

    private val twistMove = tables.twistMove
    private val flipMove = tables.flipMove
    private val sliceSortedMove = tables.sliceSortedMove
    private val cornerPermMove = tables.cornerPermMove
    private val udEdgePermMove = tables.udEdgePermMove
    private val slicePermMove = tables.slicePermMove
    private val sliceTwistPrune = tables.sliceTwistPrune
    private val sliceFlipPrune = tables.sliceFlipPrune
    private val twistFlipPrune = tables.twistFlipPrune
    private val cornerSlicePrune = tables.cornerSlicePrune
    private val edgeSlicePrune = tables.edgeSlicePrune

    /** Moves of the current phase 1 / phase 2 candidate (as [Move] ordinals). */
    private val path1 = IntArray(MAX_LENGTH + 1)
    private val path2 = IntArray(MAX_LENGTH + 1)

    /**
     * Corner and edge permutations after each prefix of [path1], filled lazily when a phase 1
     * solution needs its phase 2 start state; entries up to [cachedDepth] are current.
     */
    private val cpAfter = IntArray((MAX_LENGTH + 1) * 8)
    private val epAfter = IntArray((MAX_LENGTH + 1) * 12)
    private var cachedDepth = 0

    private lateinit var view: View
    private var best: List<Move>? = null

    /** Longest total length still worth finding (one less than the best so far). */
    private var bound = MAX_LENGTH
    private var stopped = false
    private var nodes = 0L

    /** Returns the shortest solution found for [cube], which must be a valid cube. */
    fun solve(cube: FaceletCube): List<Move> {
        val views = buildViews(cube)
        var depth = views.minOf { it.distance }
        while (!stopped && depth <= bound && depth <= MAX_LENGTH) {
            for (v in views) {
                if (stopped || depth > bound) break
                if (v.distance > depth) continue
                // A view already in H only needs depth 0 or a real detour (see phase1()).
                if (v.distance == 0 && depth in 1 until MIN_DETOUR) continue
                startView(v)
                phase1(v.twist, v.flip, v.sliceSorted, 0, depth, NO_FACE)
            }
            depth++
        }
        return checkNotNull(best) { "Two-phase search ended without a solution" }
    }

    private fun buildViews(cube: FaceletCube): List<View> {
        val views = ArrayList<View>(6)
        for (rotation in 0 until 3) {
            val cubie = checkNotNull(CubieCube.fromFacelets(CubeRotation.rotate(cube, rotation)))
            for (inverse in listOf(false, true)) {
                val c = if (inverse) cubie.inverse() else cubie
                // Symmetric cubes (e.g. the superflip) give identical views; search each only once.
                if (views.none { it.cubie == c }) views += View(c, rotation, inverse, tables)
            }
        }
        return views
    }

    private fun startView(v: View) {
        view = v
        v.cubie.cp.copyInto(cpAfter, 0)
        v.cubie.ep.copyInto(epAfter, 0)
        cachedDepth = 0
    }

    /**
     * Depth-first phase 1 search for sequences of exactly [togo] more moves that bring the cube
     * into H. Returns true when the whole search must stop.
     */
    private fun phase1(twist: Int, flip: Int, sliceSorted: Int, depth: Int, togo: Int, lastFace: Int): Boolean {
        if (togo == 0) return startPhase2(sliceSorted, depth)
        if ((++nodes and TIME_CHECK_MASK) == 0L && timeUp()) return true

        val twistRow = twist * N_MOVES
        val flipRow = flip * N_MOVES
        val sliceRow = sliceSorted * N_MOVES
        val allowedRow = lastFace * 6
        for (m in 0 until N_MOVES) {
            val face = FACE_OF_MOVE[m]
            if (!ALLOWED_AFTER[allowedRow + face]) continue
            // Ending phase 1 with a move from H would only repeat a shorter phase 1 solution.
            if (togo == 1 && !ENDS_PHASE1[m]) continue

            val nTwist = twistMove[twistRow + m].code
            val nSorted = sliceSortedMove[sliceRow + m].code
            val nSlice = nSorted / N_SLICE_PERM
            val h1 = sliceTwistPrune[nSlice * N_TWIST + nTwist].toInt()
            if (h1 >= togo) continue
            val nFlip = flipMove[flipRow + m].code
            val h2 = sliceFlipPrune[nSlice * N_FLIP + nFlip].toInt()
            if (h2 >= togo) continue
            val h3 = twistFlipPrune[nTwist * N_FLIP + nFlip].toInt()
            if (h3 >= togo) continue
            // Inside H with a few moves left: any detour out of H and back in that short can be
            // replaced by phase 2 moves, so it would only rediscover phase 2 solutions.
            if ((h1 or h2 or h3) == 0 && togo - 1 in 1 until MIN_DETOUR) continue

            path1[depth] = m
            if (cachedDepth > depth) cachedDepth = depth
            if (phase1(nTwist, nFlip, nSorted, depth + 1, togo - 1, face)) return true
        }
        return false
    }

    /**
     * Called for each phase 1 solution of length [depth1]: computes the phase 2 coordinates and runs
     * an iterative-deepening phase 2 search within the current bound. Returns true to stop.
     */
    private fun startPhase2(sliceSorted: Int, depth1: Int): Boolean {
        // Phase 2 longer than this from here cannot beat the best solution (or is not worth it).
        val limit = minOf(bound - depth1, phase2Cap(depth1))
        if (limit < 0) return false

        updatePermutations(depth1)
        val slicePerm = sliceSorted % N_SLICE_PERM
        val cornerPerm = Coordinates.rankPermutation(cpAfter, depth1 * 8, 8)
        var h = cornerSlicePrune[cornerPerm * N_SLICE_PERM + slicePerm].toInt()
        if (h > limit) return false
        val edgePerm = Coordinates.rankPermutation(epAfter, depth1 * 12, 8)
        h = maxOf(h, edgeSlicePrune[edgePerm * N_SLICE_PERM + slicePerm].toInt())
        if (h > limit) return false

        val lastFace = if (depth1 == 0) NO_FACE else FACE_OF_MOVE[path1[depth1 - 1]]
        for (depth2 in h..limit) {
            if (phase2(cornerPerm, edgePerm, slicePerm, 0, depth2, lastFace)) {
                record(depth1, depth2)
                return stopped
            }
            if (stopped) return true
        }
        return false
    }

    /** Depth-first phase 2 search for a solution of exactly [togo] more moves. */
    private fun phase2(cornerPerm: Int, edgePerm: Int, slicePerm: Int, depth: Int, togo: Int, lastFace: Int): Boolean {
        if (togo == 0) return cornerPerm == 0 && edgePerm == 0 && slicePerm == 0
        if ((++nodes and TIME_CHECK_MASK) == 0L && timeUp()) return false

        val cornerRow = cornerPerm * N_PHASE2_MOVES
        val edgeRow = edgePerm * N_PHASE2_MOVES
        val sliceRow = slicePerm * N_PHASE2_MOVES
        val allowedRow = lastFace * 6
        for (k in 0 until N_PHASE2_MOVES) {
            val face = FACE_OF_PHASE2_MOVE[k]
            if (!ALLOWED_AFTER[allowedRow + face]) continue
            val nSlice = slicePermMove[sliceRow + k].code
            val nCorner = cornerPermMove[cornerRow + k].code
            if (cornerSlicePrune[nCorner * N_SLICE_PERM + nSlice] >= togo) continue
            val nEdge = udEdgePermMove[edgeRow + k].code
            if (edgeSlicePrune[nEdge * N_SLICE_PERM + nSlice] >= togo) continue

            path2[depth] = PHASE2_MOVES[k]
            if (phase2(nCorner, nEdge, nSlice, depth + 1, togo - 1, face)) return true
            if (stopped) return false
        }
        return false
    }

    /** Brings [cpAfter]/[epAfter] up to date for the first [depth] moves of [path1]. */
    private fun updatePermutations(depth: Int) {
        for (d in cachedDepth until depth) {
            val m = path1[d]
            val from8 = d * 8
            val to8 = from8 + 8
            val moveCp = m * 8
            for (i in 0 until 8) cpAfter[to8 + i] = cpAfter[from8 + MOVE_CP[moveCp + i]]
            val from12 = d * 12
            val to12 = from12 + 12
            val moveEp = m * 12
            for (i in 0 until 12) epAfter[to12 + i] = epAfter[from12 + MOVE_EP[moveEp + i]]
        }
        cachedDepth = depth
    }

    /** Stores the solution path1[0 until depth1] + path2[0 until depth2] in original orientation. */
    private fun record(depth1: Int, depth2: Int) {
        val moves = ArrayList<Move>(depth1 + depth2)
        for (i in 0 until depth1) moves += Move.entries[path1[i]]
        for (i in 0 until depth2) moves += Move.entries[path2[i]]
        // A solution S of the inverse cube C' = C^-1 means C^-1 * S = 1, so C is solved by S^-1.
        val oriented = if (view.inverse) moves.asReversed().map { it.inverse } else moves
        best = oriented.map { CubeRotation.unrotate(it, view.rotation) }
        bound = moves.size - 1
        // Done if good enough, or if no longer solution can exist at this or any deeper phase 1 depth.
        if (moves.size <= targetLength || bound < depth1) stopped = true
    }

    private fun phase2Cap(depth1: Int): Int =
        if (best == null && depth1 > FIRST_SOLUTION_PHASE1_LIMIT) MAX_PHASE2 else maxOf(PHASE2_CAP, MAX_PHASE2 - depth1)

    /** Stops the search once it has a solution and the deadline has passed or the thread was interrupted. */
    private fun timeUp(): Boolean {
        if (best != null && (System.nanoTime() - deadlineNanos > 0 || Thread.currentThread().isInterrupted)) {
            stopped = true
        }
        return stopped
    }

    companion object {
        /** Longest phase 1 + phase 2 sequence the search arrays can hold. */
        const val MAX_LENGTH = 31

        /** God's number for phase 2 (moves within H). */
        private const val MAX_PHASE2 = 18

        /**
         * Usual cap on phase 2 length. Long phase 2 searches are expensive and rarely pay off: another
         * phase 1 solution with a short phase 2 is usually found sooner. The cap is relaxed after
         * short phase 1 solutions so that cubes already (nearly) in H still get their best solution.
         */
        private const val PHASE2_CAP = 11

        /**
         * Phase 1 depth after which, if nothing has been found yet, phase 2 may use all 18 moves. This
         * makes finding a first solution certain (phase 1 never needs more than 12 moves).
         */
        private const val FIRST_SOLUTION_PHASE1_LIMIT = 14

        /** Shortest detour out of H and back that phase 2 moves cannot replace. */
        private const val MIN_DETOUR = 5

        private const val TIME_CHECK_MASK = 0x3FFL
        private const val NO_FACE = 6

        private val FACE_OF_MOVE = IntArray(N_MOVES) { Move.entries[it].face.ordinal }
        private val FACE_OF_PHASE2_MOVE = IntArray(N_PHASE2_MOVES) { FACE_OF_MOVE[PHASE2_MOVES[it]] }

        /** Quarter turns of R, F, L or B: the only moves that can complete phase 1 (they leave H). */
        private val ENDS_PHASE1 = BooleanArray(N_MOVES) {
            val move = Move.entries[it]
            move.turns != 2 && move.face != Face.U && move.face != Face.D
        }

        /**
         * `ALLOWED_AFTER[last * 6 + face]`: whether a turn of `face` may follow a turn of `last`
         * (6 = no previous move). Rejects turning the same face twice in a row and, since opposite
         * faces commute, allows them only in one order (U before D, R before L, F before B).
         */
        private val ALLOWED_AFTER = BooleanArray(7 * 6) {
            val last = it / 6
            val face = it % 6
            last == NO_FACE || (face != last && face != last - 3)
        }

        /** Move cube permutations, flattened: `MOVE_CP[m * 8 + i]`, `MOVE_EP[m * 12 + i]`. */
        private val MOVE_CP = IntArray(N_MOVES * 8) { CubieCube.moveCube(Move.entries[it / 8]).cp[it % 8] }
        private val MOVE_EP = IntArray(N_MOVES * 12) { CubieCube.moveCube(Move.entries[it / 12]).ep[it % 12] }

        /** Admissible phase 1 distance estimate. */
        private fun phase1Bound(tables: SolverTables, twist: Int, flip: Int, slice: Int): Int = maxOf(
            tables.sliceTwistPrune[slice * N_TWIST + twist].toInt(),
            tables.sliceFlipPrune[slice * N_FLIP + flip].toInt(),
            tables.twistFlipPrune[twist * N_FLIP + flip].toInt(),
        )
    }
}
