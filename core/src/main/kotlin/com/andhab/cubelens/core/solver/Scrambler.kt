package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import kotlin.random.Random

/** Random scrambles: move sequences for people to perform, and uniformly random cube states. */
object Scrambler {

    /**
     * A random move sequence in which no move undoes or merges with its neighbours: never the same
     * face twice in a row, and never three moves in a row on one axis (U D U' is just U2 D').
     */
    fun randomMoves(count: Int = 25, random: Random = Random.Default): List<Move> {
        require(count >= 0) { "count must not be negative, got $count" }
        val moves = ArrayList<Move>(count)
        while (moves.size < count) {
            val face = Face.entries[random.nextInt(6)]
            val last = moves.lastOrNull()?.face
            if (face == last) continue
            if (last != null && face.opposite == last && moves.size >= 2 && moves[moves.size - 2].face == face) continue
            moves += Move.of(face, random.nextInt(1, 4))
        }
        return moves
    }

    /**
     * A cube state drawn uniformly from all 43,252,003,274,489,856,000 reachable states: random
     * piece permutations and orientations, with the last corner twist, last edge flip and the
     * permutation parity fixed up so the state is solvable.
     */
    fun randomState(random: Random = Random.Default): FaceletCube {
        val cp = IntArray(8) { it }.also { it.shuffle(random) }
        val ep = IntArray(12) { it }.also { it.shuffle(random) }
        // Swapping two fixed positions maps odd permutations onto even ones one-to-one, so the
        // result stays uniform among the permutations with matching parity.
        if (parity(cp) != parity(ep)) {
            val t = ep[10]
            ep[10] = ep[11]
            ep[11] = t
        }
        val co = IntArray(8)
        for (i in 0 until 7) co[i] = random.nextInt(3)
        co[7] = (3 - co.sum() % 3) % 3
        val eo = IntArray(12)
        for (i in 0 until 11) eo[i] = random.nextInt(2)
        eo[11] = eo.sum() % 2
        return CubieCube(cp, co, ep, eo).toFaceletCube()
    }

    private fun parity(p: IntArray): Int {
        var inversions = 0
        for (i in p.indices) for (j in i + 1 until p.size) if (p[i] > p[j]) inversions++
        return inversions and 1
    }
}
