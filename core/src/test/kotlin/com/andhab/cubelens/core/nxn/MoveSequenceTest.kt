package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MoveSequenceTest {

    private fun simplify(n: Int, moves: String): String {
        val sequence = MoveSequence(n)
        sequence.addAll(LayerMove.parseSequence(moves))
        return LayerMove.format(sequence.toLayerMoves())
    }

    @Test
    fun cancelsAndMerges() {
        assertEquals("", simplify(4, "R U U' R'"))
        assertEquals("L", simplify(4, "R L R'"))
        assertEquals("R2", simplify(3, "R R"))
        assertEquals("Rw", simplify(4, "R 2R"))
        assertEquals("Rw'", simplify(5, "2R' R'"))
        assertEquals("3Rw", simplify(5, "R 2R 3R"))
        assertEquals("2-3Rw2", simplify(5, "2R2 3R2"))
        assertEquals("2L'", simplify(5, "4R"))
        assertEquals("U R", simplify(4, "U R"))
        // Opposite outer turns stay outer turns of their own face.
        assertEquals("R L'", simplify(3, "R L'"))
        // R 2R2 3R (layers turned 1, 2, 1) needs only two block moves.
        assertEquals(2, MoveSequence(4).apply { addAll(LayerMove.parseSequence("R 2R2 3R")) }.moveCount())
    }

    @Test
    fun simplifiedSequencesHaveTheSameEffect() {
        val random = Random(42)
        for (n in 2..7) {
            val g = NxNGeometry.of(n)
            repeat(200) {
                val moves = List(random.nextInt(1, 25)) {
                    val face = NxNModel.AXIS_FACES[random.nextInt(3)].let { if (random.nextBoolean()) it else it.opposite }
                    val from = random.nextInt(1, n + 1)
                    val to = random.nextInt(from, if (from == 1) n else n + 1) // no whole-cube rotations
                    LayerMove(face, from, to, random.nextInt(1, 4))
                }
                val sequence = MoveSequence(n)
                sequence.addAll(moves)
                val simplified = sequence.toLayerMoves()
                val start = NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, random, 10))
                assertEquals(LayerMove.format(moves), start.apply(moves), start.apply(simplified))
                // Never longer, except that a group adding up to a rotation may need one more move.
                assertTrue(simplified.size <= moves.size + 1)
                for (m in simplified) {
                    assertFalse("rotation $m", m.fromDepth == 1 && m.toDepth == n)
                    g.permutation(m)
                }
            }
        }
    }

    @Test
    fun neverTurnsTheOuterDlbLayersUnlessAsked() {
        val random = Random(7)
        for (n in listOf(2, 4, 6)) {
            repeat(300) {
                val moves = List(random.nextInt(1, 20)) {
                    val axis = random.nextInt(3)
                    val from = random.nextInt(0, n - 1)
                    val to = random.nextInt(from, n - 1)
                    LayerMove(NxNModel.AXIS_FACES[axis], from + 1, to + 1, random.nextInt(1, 4))
                }
                val simplified = MoveSequence(n).apply { addAll(moves) }.toLayerMoves()
                for (m in simplified) {
                    val outerDlb = (m.face == Face.D || m.face == Face.L || m.face == Face.B) && m.fromDepth == 1
                    val farLayer = (m.face == Face.U || m.face == Face.R || m.face == Face.F) && m.toDepth == n
                    assertFalse("$m turns a D, L or B outer layer", outerDlb || farLayer)
                }
            }
        }
    }

    /** [MoveSequence.blockCount] equals the true minimum, found by breadth-first search over block turns. */
    @Test
    fun blockCountIsMinimal() {
        for (n in 2..8) {
            val states = 1 shl (2 * n)
            val distance = IntArray(states) { -1 }
            distance[0] = 0
            val queue = ArrayDeque(listOf(0))
            val blocks = ArrayList<IntArray>()
            for (from in 0 until n) for (to in from until n) for (t in 1..3) {
                blocks += IntArray(n) { if (it in from..to) t else 0 }
            }
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                for (b in blocks) {
                    var next = 0
                    for (l in 0 until n) next = next or ((((s shr (2 * l)) + b[l]) and 3) shl (2 * l))
                    if (distance[next] < 0) {
                        distance[next] = distance[s] + 1
                        queue += next
                    }
                }
            }
            for (s in 0 until states) {
                val t = IntArray(n) { (s shr (2 * it)) and 3 }
                assertEquals(t.toList().toString(), distance[s], MoveSequence.blockCount(t))
                val emitted = MoveSequence.blocks(1, t, n)
                // Without a full-width block the emitted moves are optimal and produce exactly t.
                if (t[0] == 0 || t[n - 1] == 0) assertEquals(t.toList().toString(), distance[s], emitted.size)
                val check = MoveSequence(n).apply { addAll(emitted) }
                val produced = if (check.isEmpty) IntArray(n) else check.groupTurns(0)
                assertEquals(t.toList(), produced.toList())
            }
        }
    }

    /** The lookup table behind [MoveSequence.blockCount] is large enough for the largest supported size. */
    @Test
    fun largestSizeIsCovered() {
        val n = NxNGeometry.MAX_SIZE
        val random = Random(11)
        val patterns = List(2_000) { IntArray(n) { random.nextInt(4) } } + listOf(
            // Every layer turned differently from its neighbours: n + 1 nonzero differences.
            IntArray(n) { 1 + it % 3 },
            IntArray(n) { if (it % 2 == 0) 1 else 3 },
            IntArray(n) { if (it % 2 == 0) 2 else 1 },
        )
        for (t in patterns) {
            val emitted = MoveSequence.blocks(0, t, n)
            if (t[0] == 0 || t[n - 1] == 0) assertEquals(t.toList().toString(), MoveSequence.blockCount(t), emitted.size)
            val check = MoveSequence(n).apply { addAll(emitted) }
            val produced = if (check.isEmpty) IntArray(n) else check.groupTurns(0)
            assertEquals(t.toList(), produced.toList())
        }
        assertThrows(IllegalArgumentException::class.java) { MoveSequence(NxNGeometry.MAX_SIZE + 1) }
    }
}
