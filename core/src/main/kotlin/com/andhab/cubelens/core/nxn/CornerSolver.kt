package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Move

/**
 * Optimal solver for the eight corners of any cube (the whole 2×2×2, or the corners of a larger
 * even cube) using only the outer U, R and F turns, so the DBL corner never moves.
 *
 * With DBL fixed there are 7! · 3⁶ = 3,674,160 corner states. A breadth-first search from the
 * solved state stores the distance of every state in a byte table (3.7 MB, built once in a few
 * tenths of a second on a desktop JVM); a solution then simply follows the distances down, so it
 * is optimal in the half-turn metric (never more than 11 moves) and takes microseconds.
 *
 * Thread-safe: the table is built once, on first use or in [prepare].
 */
internal object CornerSolver {
    private val MOVES = listOf(Move.U1, Move.U2, Move.U3, Move.R1, Move.R2, Move.R3, Move.F1, Move.F2, Move.F3)

    /** The seven corner positions that move (all but DBL), in coordinate order. */
    private val FREE = intArrayOf(
        CubieCube.URF, CubieCube.UFL, CubieCube.ULB, CubieCube.UBR,
        CubieCube.DFR, CubieCube.DLF, CubieCube.DRB,
    )
    private val FREE_INDEX = IntArray(8) { -1 }.also { FREE.forEachIndexed { i, p -> it[p] = i } }

    const val N_PERM = 5040
    const val N_TWIST = 729
    const val N_STATES = N_PERM * N_TWIST

    /** God's number for the 2×2×2 in the half-turn metric. */
    const val MAX_LENGTH = 11

    private class Tables(val permMove: IntArray, val twistMove: IntArray, val distance: ByteArray)

    private val tables: Tables by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { build() }

    /** Builds the tables now (idempotent). */
    fun prepare() {
        tables
    }

    /**
     * An optimal U/R/F solution for the corner permutation [cp] and twists [co] (Kociemba
     * conventions, [CubieCube]). The DBL corner must be home (`cp[DBL] == DBL`, `co[DBL] == 0`) and
     * the twists must add up.
     */
    fun solve(cp: IntArray, co: IntArray): List<Move> {
        require(cp[CubieCube.DBL] == CubieCube.DBL && co[CubieCube.DBL] == 0) { "The DBL corner must be home" }
        require(co.sum() % 3 == 0) { "Corner twists don't add up" }
        val t = tables
        var state = encodePerm(cp) * N_TWIST + encodeTwist(co)
        var d = t.distance[state].toInt()
        val out = ArrayList<Move>(d)
        while (d > 0) {
            val perm = state / N_TWIST
            val twist = state % N_TWIST
            var next = -1
            for (m in MOVES.indices) {
                val s = t.permMove[perm * 9 + m] * N_TWIST + t.twistMove[twist * 9 + m]
                if (t.distance[s].toInt() == d - 1) {
                    out += MOVES[m]
                    next = s
                    break
                }
            }
            check(next >= 0) { "Corner distance table is inconsistent" }
            state = next
            d--
        }
        return out
    }

    /** Lehmer code of the cubies at the seven free positions. */
    private fun encodePerm(cp: IntArray): Int {
        val p = IntArray(7) { FREE_INDEX[cp[FREE[it]]] }
        var index = 0
        for (i in 0 until 7) {
            var smaller = 0
            for (j in i + 1 until 7) if (p[j] < p[i]) smaller++
            index = index * (7 - i) + smaller
        }
        return index
    }

    private fun decodePerm(index: Int): IntArray {
        val digits = IntArray(7)
        var x = index
        for (i in 6 downTo 0) {
            digits[i] = x % (7 - i)
            x /= 7 - i
        }
        val remaining = (0 until 7).toMutableList()
        val cp = IntArray(8)
        cp[CubieCube.DBL] = CubieCube.DBL
        for (i in 0 until 7) cp[FREE[i]] = FREE[remaining.removeAt(digits[i])]
        return cp
    }

    /** Base-3 code of the twists of the first six free positions (the seventh follows). */
    private fun encodeTwist(co: IntArray): Int {
        var index = 0
        for (i in 0 until 6) index = index * 3 + co[FREE[i]]
        return index
    }

    private fun decodeTwist(index: Int): IntArray {
        val co = IntArray(8)
        var x = index
        var sum = 0
        for (i in 5 downTo 0) {
            co[FREE[i]] = x % 3
            sum += x % 3
            x /= 3
        }
        co[FREE[6]] = (3 - sum % 3) % 3
        return co
    }

    private fun build(): Tables {
        val moveCubes = MOVES.map { CubieCube.moveCube(it) }
        val permMove = IntArray(N_PERM * 9)
        for (p in 0 until N_PERM) {
            val cube = CubieCube(cp = decodePerm(p))
            for (m in 0 until 9) permMove[p * 9 + m] = encodePerm(cube.multiply(moveCubes[m]).cp)
        }
        val twistMove = IntArray(N_TWIST * 9)
        for (t in 0 until N_TWIST) {
            val cube = CubieCube(co = decodeTwist(t))
            for (m in 0 until 9) twistMove[t * 9 + m] = encodeTwist(cube.multiply(moveCubes[m]).co)
        }
        val distance = ByteArray(N_STATES) { -1 }
        val queue = IntArray(N_STATES)
        var head = 0
        var tail = 0
        distance[0] = 0
        queue[tail++] = 0
        while (head < tail) {
            val s = queue[head++]
            val d = (distance[s] + 1).toByte()
            val perm = s / N_TWIST
            val twist = s - perm * N_TWIST
            for (m in 0 until 9) {
                val next = permMove[perm * 9 + m] * N_TWIST + twistMove[twist * 9 + m]
                if (distance[next] < 0) {
                    distance[next] = d
                    queue[tail++] = next
                }
            }
        }
        check(tail == N_STATES) { "Corner search reached $tail of $N_STATES states" }
        return Tables(permMove, twistMove, distance)
    }
}
