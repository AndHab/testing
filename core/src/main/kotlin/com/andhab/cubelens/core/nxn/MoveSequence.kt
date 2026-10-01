package com.andhab.cubelens.core.nxn

/**
 * Builds a short move sequence for an N×N×N cube by merging and cancelling moves as they are added.
 *
 * Consecutive moves on one axis commute, so they are collected into a group that only records how
 * far each layer of that axis has turned in total (mod 4). A group whose layers all come back to
 * zero disappears, which lets the moves before and after it merge in turn (`R U U' R'` cancels
 * completely). When the sequence is read back, each group is written with the fewest block moves
 * (outer, wide, slice or `2-3Rw`-style range turns) that produce its layer turns, so `R 2R` becomes
 * `Rw`.
 *
 * Moves are written from the face they are closest to; a group never produces a whole-cube
 * rotation, and it only turns the outer D, L or B layer if a move added to it did.
 *
 * Layers are numbered as in [NxNModel]: along each axis from the U, R or F face, with turns measured
 * clockwise from that face.
 */
internal class MoveSequence(val n: Int) {
    init {
        require(n in NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE) { "Unsupported cube size $n" }
    }

    private val axes = ArrayList<Int>()
    private val groups = ArrayList<IntArray>()

    /** True if nothing is left after cancellation. */
    val isEmpty: Boolean get() = groups.isEmpty()

    /** Number of same-axis groups. */
    val groupCount: Int get() = groups.size

    fun groupAxis(index: Int): Int = axes[index]

    /** Total turns of each layer in group [index] (do not modify). */
    fun groupTurns(index: Int): IntArray = groups[index]

    /** Adds a turn of layers [fromLayer]..[toLayer] of [axis] by [quarterTurns] (any integer, taken mod 4). */
    fun add(axis: Int, fromLayer: Int, toLayer: Int, quarterTurns: Int) {
        require(axis in 0..2 && fromLayer in 0..toLayer && toLayer < n) { "Bad block $axis $fromLayer..$toLayer" }
        val q = quarterTurns and 3
        if (q == 0) return
        if (axes.isNotEmpty() && axes.last() == axis) {
            val t = groups.last()
            for (l in fromLayer..toLayer) t[l] = (t[l] + q) and 3
            if (t.all { it == 0 }) {
                axes.removeAt(axes.size - 1)
                groups.removeAt(groups.size - 1)
            }
        } else {
            val t = IntArray(n)
            for (l in fromLayer..toLayer) t[l] = q
            axes += axis
            groups += t
        }
    }

    /** Adds a move code of [model]. */
    fun addCode(model: NxNModel, code: Int) {
        add(model.axisOf(code), model.fromLayer(code), model.toLayer(code), model.turnsOf(code))
    }

    /** Adds a group of layer turns on [axis] (as returned by [groupTurns]). */
    fun addGroup(axis: Int, turns: IntArray) {
        for (l in 0 until n) if (turns[l] != 0) add(axis, l, l, turns[l])
    }

    fun add(move: LayerMove) {
        val axis = NxNModel.axisOf(move.face)
        if (move.face == NxNModel.AXIS_FACES[axis]) {
            add(axis, move.fromDepth - 1, move.toDepth - 1, move.turns)
        } else {
            add(axis, n - move.toDepth, n - move.fromDepth, 4 - move.turns)
        }
    }

    fun addAll(moves: Iterable<LayerMove>) = moves.forEach(::add)

    /** Appends everything in [other] (which must be for the same size). */
    fun addAll(other: MoveSequence) {
        for (i in other.groups.indices) addGroup(other.axes[i], other.groups[i])
    }

    /** Number of block moves [toLayerMoves] returns. */
    fun moveCount(): Int = groups.sumOf { blockCount(it) }

    /** The sequence as block moves. */
    fun toLayerMoves(): List<LayerMove> {
        val out = ArrayList<LayerMove>()
        for (i in groups.indices) out += blocks(axes[i], groups[i], n)
        return out
    }

    companion object {
        /**
         * Minimal number of block moves producing the layer turns [t] (one entry per layer, so at
         * most [NxNGeometry.MAX_SIZE]). With `d[i] = t[i] - t[i-1]` (mod 4, zero outside the cube),
         * a block on layers a..b-1 changes only `d[a]` and `d[b]`; nonzero differences that sum to
         * zero can be produced by a chain of `k - 1` blocks, so the minimum is the number of nonzero
         * differences minus the most zero-sum groups they split into.
         */
        fun blockCount(t: IntArray): Int {
            var c1 = 0
            var c2 = 0
            var c3 = 0
            var prev = 0
            for (i in 0..t.size) {
                val cur = if (i < t.size) t[i] else 0
                when ((cur - prev) and 3) {
                    1 -> c1++
                    2 -> c2++
                    3 -> c3++
                }
                prev = cur
            }
            return c1 + c2 + c3 - MAX_GROUPS[groupsIndex(c1, c2, c3)]
        }

        /**
         * The minimal zero-sum (mod 4) multisets of differences: (count of 1s, 2s, 3s). Every
         * zero-sum multiset splits into these.
         */
        private val ZERO_SUM_GROUPS = arrayOf(
            intArrayOf(1, 0, 1), intArrayOf(0, 2, 0), intArrayOf(2, 1, 0),
            intArrayOf(0, 1, 2), intArrayOf(4, 0, 0), intArrayOf(0, 0, 4),
        )

        private const val IMPOSSIBLE = -1000

        /**
         * Count limit (exclusive) per difference value in [MAX_GROUPS]: a cube of [NxNGeometry.MAX_SIZE]
         * layers has `MAX_SIZE + 1` layer differences, so each count is at most that. Derived from
         * the size limit so that raising it keeps the table large enough.
         */
        private const val COUNTS = NxNGeometry.MAX_SIZE + 2

        private fun groupsIndex(c1: Int, c2: Int, c3: Int): Int = (c1 * COUNTS + c2) * COUNTS + c3

        /** Most zero-sum groups for counts (c1, c2, c3) < [COUNTS] each, or [IMPOSSIBLE]. */
        private val MAX_GROUPS: IntArray = IntArray(COUNTS * COUNTS * COUNTS).also { table ->
            // Each entry only reads entries with smaller counts, so plain index order works.
            for (c1 in 0 until COUNTS) for (c2 in 0 until COUNTS) for (c3 in 0 until COUNTS) {
                var best = if (c1 == 0 && c2 == 0 && c3 == 0) 0 else IMPOSSIBLE
                for (g in ZERO_SUM_GROUPS) {
                    val r1 = c1 - g[0]
                    val r2 = c2 - g[1]
                    val r3 = c3 - g[2]
                    if (r1 < 0 || r2 < 0 || r3 < 0) continue
                    best = maxOf(best, table[groupsIndex(r1, r2, r3)] + 1)
                }
                table[groupsIndex(c1, c2, c3)] = best
            }
        }

        /** Block moves (as [LayerMove]s) producing the layer turns [t] on [axis]. */
        fun blocks(axis: Int, t: IntArray, n: Int): List<LayerMove> {
            val intervals = minimalIntervals(t) ?: runIntervals(t)
            return intervals.map { (from, to, turns) -> toLayerMove(axis, from, to, turns, n) }
        }

        /** Maximal runs of equal nonzero turns, never covering all layers at once. */
        private fun runIntervals(t: IntArray): List<Triple<Int, Int, Int>> {
            val out = ArrayList<Triple<Int, Int, Int>>()
            var i = 0
            while (i < t.size) {
                if (t[i] == 0) {
                    i++
                    continue
                }
                var j = i
                while (j + 1 < t.size && t[j + 1] == t[i]) j++
                if (i == 0 && j == t.size - 1) {
                    out += Triple(0, j - 1, t[i])
                    out += Triple(j, j, t[i])
                } else {
                    out += Triple(i, j, t[i])
                }
                i = j + 1
            }
            return out
        }

        /**
         * An optimal decomposition into blocks (see [blockCount]), or null if it would need a
         * whole-cube rotation (then [runIntervals] is used).
         */
        private fun minimalIntervals(t: IntArray): List<Triple<Int, Int, Int>>? {
            val byValue = Array(4) { ArrayDeque<Int>() }
            val d = IntArray(t.size + 1)
            var prev = 0
            for (i in 0..t.size) {
                val cur = if (i < t.size) t[i] else 0
                d[i] = (cur - prev) and 3
                if (d[i] != 0) byValue[d[i]] += i
                prev = cur
            }
            var c1 = byValue[1].size
            var c2 = byValue[2].size
            var c3 = byValue[3].size
            val out = ArrayList<Triple<Int, Int, Int>>()
            while (c1 + c2 + c3 > 0) {
                val here = MAX_GROUPS[groupsIndex(c1, c2, c3)]
                val g = ZERO_SUM_GROUPS.first { g ->
                    val r1 = c1 - g[0]
                    val r2 = c2 - g[1]
                    val r3 = c3 - g[2]
                    r1 >= 0 && r2 >= 0 && r3 >= 0 && MAX_GROUPS[groupsIndex(r1, r2, r3)] == here - 1
                }
                val members = ArrayList<Int>()
                repeat(g[0]) { members += byValue[1].removeFirst() }
                repeat(g[1]) { members += byValue[2].removeFirst() }
                repeat(g[2]) { members += byValue[3].removeFirst() }
                c1 -= g[0]
                c2 -= g[1]
                c3 -= g[2]
                members.sort()
                // Chain: block k covers members[k] until just before members[k + 1] and turns by
                // the running sum of the differences, so each member's difference comes out right.
                var sum = 0
                for (k in 0 until members.size - 1) {
                    sum = (sum + d[members[k]]) and 3
                    val from = members[k]
                    val to = members[k + 1] - 1
                    if (from == 0 && to == t.size - 1) return null
                    out += Triple(from, to, sum)
                }
            }
            return out
        }

        private fun toLayerMove(axis: Int, from: Int, to: Int, turns: Int, n: Int): LayerMove {
            val positive = NxNModel.AXIS_FACES[axis]
            val negative = positive.opposite
            return when {
                from == 0 -> LayerMove(positive, 1, to + 1, turns)
                to == n - 1 -> LayerMove(negative, 1, n - from, 4 - turns)
                from <= n - 1 - to -> LayerMove(positive, from + 1, to + 1, turns)
                else -> LayerMove(negative, n - to, n - from, 4 - turns)
            }
        }
    }
}
