package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.solver.Coordinates.N_FLIP
import com.andhab.cubelens.core.solver.Coordinates.N_PERM8
import com.andhab.cubelens.core.solver.Coordinates.N_SLICE
import com.andhab.cubelens.core.solver.Coordinates.N_SLICE_PERM
import com.andhab.cubelens.core.solver.Coordinates.N_SLICE_SORTED
import com.andhab.cubelens.core.solver.Coordinates.N_TWIST

/**
 * Move and pruning tables of the two-phase algorithm. Immutable once built, so one instance is
 * shared by all concurrent searches.
 *
 * Move tables map `coordinate * moveCount + move` to the coordinate after the move. Phase 1 tables
 * use all 18 moves in [Move] order; phase 2 tables use the ten moves of [PHASE2_MOVES].
 *
 * Pruning tables hold the exact distance to the goal in the product of two coordinates, a lower
 * bound for the real distance:
 *  - phase 1: slice x twist, slice x flip and twist x flip (all 18 moves, goal: the subgroup H);
 *  - phase 2: corner permutation x slice permutation and U/D edge permutation x slice
 *    permutation (phase 2 moves, goal: solved).
 *
 * All tables are derived from [CubieCube.moveCube], i.e. from the facelet geometry, so the solver
 * agrees with the renderer by construction.
 */
internal class SolverTables(
    val twistMove: CharArray,
    val flipMove: CharArray,
    val sliceSortedMove: CharArray,
    val cornerPermMove: CharArray,
    val udEdgePermMove: CharArray,
    val slicePermMove: CharArray,
    val sliceTwistPrune: ByteArray,
    val sliceFlipPrune: ByteArray,
    val twistFlipPrune: ByteArray,
    val cornerSlicePrune: ByteArray,
    val edgeSlicePrune: ByteArray,
) {
    init {
        require(twistMove.size == N_TWIST * N_MOVES)
        require(flipMove.size == N_FLIP * N_MOVES)
        require(sliceSortedMove.size == N_SLICE_SORTED * N_MOVES)
        require(cornerPermMove.size == N_PERM8 * N_PHASE2_MOVES)
        require(udEdgePermMove.size == N_PERM8 * N_PHASE2_MOVES)
        require(slicePermMove.size == N_SLICE_PERM * N_PHASE2_MOVES)
        require(sliceTwistPrune.size == N_SLICE * N_TWIST)
        require(sliceFlipPrune.size == N_SLICE * N_FLIP)
        require(twistFlipPrune.size == N_TWIST * N_FLIP)
        require(cornerSlicePrune.size == N_PERM8 * N_SLICE_PERM)
        require(edgeSlicePrune.size == N_PERM8 * N_SLICE_PERM)
    }

    /** The tables in serialization order (see [TableCache]). */
    val charTables: List<CharArray>
        get() = listOf(twistMove, flipMove, sliceSortedMove, cornerPermMove, udEdgePermMove, slicePermMove)

    /** The tables in serialization order (see [TableCache]). */
    val byteTables: List<ByteArray>
        get() = listOf(sliceTwistPrune, sliceFlipPrune, twistFlipPrune, cornerSlicePrune, edgeSlicePrune)

    /** True if every table of [other] has the same content as this one. */
    fun contentEquals(other: SolverTables): Boolean =
        charTables.zip(other.charTables).all { (a, b) -> a.contentEquals(b) } &&
            byteTables.zip(other.byteTables).all { (a, b) -> a.contentEquals(b) }

    companion object {
        /** Number of moves in phase 1 (all face turns). */
        const val N_MOVES = 18

        /** Number of moves in phase 2. */
        const val N_PHASE2_MOVES = 10

        /** Sizes of the char tables in serialization order, in elements. */
        val CHAR_TABLE_SIZES = intArrayOf(
            N_TWIST * N_MOVES,
            N_FLIP * N_MOVES,
            N_SLICE_SORTED * N_MOVES,
            N_PERM8 * N_PHASE2_MOVES,
            N_PERM8 * N_PHASE2_MOVES,
            N_SLICE_PERM * N_PHASE2_MOVES,
        )

        /** Sizes of the byte tables in serialization order, in elements. */
        val BYTE_TABLE_SIZES = intArrayOf(
            N_SLICE * N_TWIST,
            N_SLICE * N_FLIP,
            N_TWIST * N_FLIP,
            N_PERM8 * N_SLICE_PERM,
            N_PERM8 * N_SLICE_PERM,
        )

        /**
         * The moves that keep a cube inside H: U, U2, U', D, D2, D', R2, L2, F2, B2 (as [Move]
         * ordinals, in [Move] order). Phase 2 move index `k` stands for `Move.entries[PHASE2_MOVES[k]]`.
         */
        val PHASE2_MOVES: IntArray = Move.entries
            .filter { it.face == Face.U || it.face == Face.D || it.turns == 2 }
            .map { it.ordinal }
            .toIntArray()

        /** Builds all tables from scratch. Takes well under a second on a desktop JVM. */
        fun build(): SolverTables {
            check(PHASE2_MOVES.size == N_PHASE2_MOVES)
            val cubes = Move.entries.map { CubieCube.moveCube(it) }
            val p2Cubes = PHASE2_MOVES.map { cubes[it] }

            val twistMove = CharArray(N_TWIST * N_MOVES)
            val co = IntArray(8)
            val nco = IntArray(8)
            for (t in 0 until N_TWIST) {
                Coordinates.setTwist(co, t)
                for ((m, mc) in cubes.withIndex()) {
                    for (i in 0 until 8) nco[i] = (co[mc.cp[i]] + mc.co[i]) % 3
                    twistMove[t * N_MOVES + m] = Coordinates.twist(nco).toChar()
                }
            }

            val flipMove = CharArray(N_FLIP * N_MOVES)
            val eo = IntArray(12)
            val neo = IntArray(12)
            for (f in 0 until N_FLIP) {
                Coordinates.setFlip(eo, f)
                for ((m, mc) in cubes.withIndex()) {
                    for (i in 0 until 12) neo[i] = (eo[mc.ep[i]] + mc.eo[i]) and 1
                    flipMove[f * N_MOVES + m] = Coordinates.flip(neo).toChar()
                }
            }

            val ep = IntArray(12)
            val nep = IntArray(12)
            val sliceSortedMove = CharArray(N_SLICE_SORTED * N_MOVES)
            for (s in 0 until N_SLICE_SORTED) {
                Coordinates.setSliceSorted(ep, s)
                for ((m, mc) in cubes.withIndex()) {
                    for (i in 0 until 12) nep[i] = ep[mc.ep[i]]
                    sliceSortedMove[s * N_MOVES + m] = Coordinates.sliceSorted(nep).toChar()
                }
            }

            val cornerPermMove = CharArray(N_PERM8 * N_PHASE2_MOVES)
            val cp = IntArray(8)
            val ncp = IntArray(8)
            for (c in 0 until N_PERM8) {
                Coordinates.setCornerPerm(cp, c)
                for ((k, mc) in p2Cubes.withIndex()) {
                    for (i in 0 until 8) ncp[i] = cp[mc.cp[i]]
                    cornerPermMove[c * N_PHASE2_MOVES + k] = Coordinates.cornerPerm(ncp).toChar()
                }
            }

            val udEdgePermMove = CharArray(N_PERM8 * N_PHASE2_MOVES)
            for (c in 0 until N_PERM8) {
                Coordinates.setUdEdgePerm(ep, c)
                for ((k, mc) in p2Cubes.withIndex()) {
                    for (i in 0 until 12) nep[i] = ep[mc.ep[i]]
                    udEdgePermMove[c * N_PHASE2_MOVES + k] = Coordinates.udEdgePerm(nep).toChar()
                }
            }

            val slicePermMove = CharArray(N_SLICE_PERM * N_PHASE2_MOVES)
            for (c in 0 until N_SLICE_PERM) {
                Coordinates.setSlicePerm(ep, c)
                for ((k, mc) in p2Cubes.withIndex()) {
                    for (i in 0 until 12) nep[i] = ep[mc.ep[i]]
                    slicePermMove[c * N_PHASE2_MOVES + k] = Coordinates.slicePerm(nep).toChar()
                }
            }

            // The unsorted slice coordinate only matters for building the phase 1 pruning tables.
            val sliceMove = CharArray(N_SLICE * N_MOVES) { i ->
                val s = i / N_MOVES
                val m = i % N_MOVES
                (sliceSortedMove[s * N_SLICE_PERM * N_MOVES + m].code / N_SLICE_PERM).toChar()
            }

            return SolverTables(
                twistMove = twistMove,
                flipMove = flipMove,
                sliceSortedMove = sliceSortedMove,
                cornerPermMove = cornerPermMove,
                udEdgePermMove = udEdgePermMove,
                slicePermMove = slicePermMove,
                sliceTwistPrune = buildPruning(sliceMove, N_SLICE, twistMove, N_TWIST, N_MOVES),
                sliceFlipPrune = buildPruning(sliceMove, N_SLICE, flipMove, N_FLIP, N_MOVES),
                twistFlipPrune = buildPruning(twistMove, N_TWIST, flipMove, N_FLIP, N_MOVES),
                cornerSlicePrune = buildPruning(
                    cornerPermMove, N_PERM8, slicePermMove, N_SLICE_PERM, N_PHASE2_MOVES,
                ),
                edgeSlicePrune = buildPruning(
                    udEdgePermMove, N_PERM8, slicePermMove, N_SLICE_PERM, N_PHASE2_MOVES,
                ),
            )
        }

        private const val UNVISITED: Byte = -1

        /**
         * Breadth-first search over the product coordinate `a * sizeB + b` starting from 0 (solved).
         * Expands the frontier while it is small, then switches to scanning the unvisited entries for
         * a neighbour on the frontier, which is much cheaper once most entries are known. Both
         * directions are valid because every move set used here is closed under inverses.
         */
        private fun buildPruning(
            moveA: CharArray,
            sizeA: Int,
            moveB: CharArray,
            sizeB: Int,
            moveCount: Int,
        ): ByteArray {
            val size = sizeA * sizeB
            val table = ByteArray(size) { UNVISITED }
            table[0] = 0
            var filled = 1
            var depth = 0
            while (filled < size) {
                val current = depth.toByte()
                val next = (depth + 1).toByte()
                val backward = filled > size / 4
                var added = 0
                var idx = 0
                for (a in 0 until sizeA) {
                    val rowA = a * moveCount
                    for (b in 0 until sizeB) {
                        val value = table[idx]
                        if (backward) {
                            if (value == UNVISITED) {
                                val rowB = b * moveCount
                                for (m in 0 until moveCount) {
                                    val j = moveA[rowA + m].code * sizeB + moveB[rowB + m].code
                                    if (table[j] == current) {
                                        table[idx] = next
                                        added++
                                        break
                                    }
                                }
                            }
                        } else if (value == current) {
                            val rowB = b * moveCount
                            for (m in 0 until moveCount) {
                                val j = moveA[rowA + m].code * sizeB + moveB[rowB + m].code
                                if (table[j] == UNVISITED) {
                                    table[j] = next
                                    added++
                                }
                            }
                        }
                        idx++
                    }
                }
                check(added > 0) { "Pruning table has unreachable entries" }
                filled += added
                depth++
            }
            return table
        }
    }
}
