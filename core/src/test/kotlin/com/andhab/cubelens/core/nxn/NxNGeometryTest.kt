package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.cube.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NxNGeometryTest {

    @Test
    fun threeByThreeMatchesFaceletGeometryAndMoves() {
        val g = NxNGeometry.of(3)
        for (i in 0 until 54) {
            val p = Facelets.position[i]
            assertEquals(Vec3(p.x * 2, p.y * 2, p.z * 2), g.position[i])
        }
        for (m in Move.entries) {
            assertArrayEquals(m.notation, m.permutation, g.permutation(m.toLayerMove()))
        }
        val rnd = Random(3)
        val seq = List(40) { Move.entries[rnd.nextInt(18)] }
        val facelet = FaceletCube.scrambled(seq)
        val nxn = NxNCube.solved(3).apply(seq.map { it.toLayerMove() })
        assertEquals(facelet.toColors(), nxn.toColors())
    }

    @Test
    fun geometryIsABijectionForAllSizes() {
        for (n in NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE) {
            val g = NxNGeometry.of(n)
            val keys = (0 until g.stickerCount).map { g.position[it] to g.normal[it] }.toSet()
            assertEquals(6 * n * n, keys.size)
            for (i in 0 until g.stickerCount) {
                assertEquals(i, g.indexOf(g.position[i], g.normal[i]))
                assertEquals(1, g.depthOf(i, g.faceOf(i)))
                assertEquals(n, g.depthOf(i, g.faceOf(i).opposite))
            }
            val expectedCubies = n * n * n - maxOf(0, n - 2).let { it * it * it }
            assertEquals(expectedCubies, g.cubies.size)
        }
    }

    @Test
    fun movesHaveOrderFourAndWideMovesComposeFromSlices() {
        val rnd = Random(11)
        for (n in 2..7) {
            val g = NxNGeometry.of(n)
            val start = NxNCube.solved(n).apply(List(30) { randomMove(n, rnd) })
            for (face in Face.entries) for (d in 1..n) {
                val q = LayerMove(face, d, d, 1)
                assertEquals(start, start.apply(listOf(q, q, q, q)))
                assertEquals(start, start.apply(q).apply(q.inverse))
            }
            for (face in Face.entries) for (to in 2..n) {
                val wide = LayerMove(face, 1, to, 1)
                val slices = (1..to).map { LayerMove(face, it, it, 1) }
                assertEquals(start.apply(wide), start.apply(slices))
            }
            // Turning layer d from one face is the inverse of turning layer n+1-d from the opposite face.
            for (face in Face.entries) for (d in 1..n) {
                val a = LayerMove(face, d, d, 1)
                val b = LayerMove(face.opposite, n + 1 - d, n + 1 - d, 3)
                assertArrayEquals(g.permutation(a), g.permutation(b))
            }
        }
    }

    @Test
    fun wholeCubeTurnKeepsSolvedCubeSolved() {
        for (n in 2..7) {
            val solved = NxNCube.solved(n)
            for (face in listOf(Face.U, Face.R, Face.F)) {
                val turned = solved.apply(LayerMove(face, 1, n, 1))
                assertTrue(turned.isSolved)
                assertFalse(turned == solved)
            }
        }
    }

    @Test
    fun pieceKinds() {
        val g4 = NxNGeometry.of(4)
        assertEquals(24, (0 until g4.stickerCount).count { g4.kindOf(it) == PieceKind.CORNER })
        assertEquals(48, (0 until g4.stickerCount).count { g4.kindOf(it) == PieceKind.WING })
        assertEquals(24, (0 until g4.stickerCount).count { g4.kindOf(it) == PieceKind.CENTER })
        val g5 = NxNGeometry.of(5)
        assertEquals(24, (0 until g5.stickerCount).count { g5.kindOf(it) == PieceKind.MIDGE })
        assertEquals(48, (0 until g5.stickerCount).count { g5.kindOf(it) == PieceKind.WING })
        assertEquals(6, (0 until g5.stickerCount).count { g5.kindOf(it) == PieceKind.FIXED_CENTER })
        assertEquals(48, (0 until g5.stickerCount).count { g5.kindOf(it) == PieceKind.CENTER })
    }

    @Test
    fun notationRoundTrip() {
        val tokens = listOf("R", "U2", "F'", "Rw", "Rw'", "3Rw", "3Rw2", "2R", "3L'", "2-3Uw", "Bw2")
        for (t in tokens) assertEquals(t, LayerMove.parse(t).notation)
        assertEquals(LayerMove(Face.R, 1, 2, 1), LayerMove.parse("r"))
        assertEquals(LayerMove(Face.D, 1, 1, 3), LayerMove.parse("D3"))
        assertEquals("R U R' U'", LayerMove.format(LayerMove.parseSequence("R  U R' U'")))
    }

    private fun randomMove(n: Int, rnd: Random): LayerMove {
        val face = Face.entries[rnd.nextInt(6)]
        val depth = rnd.nextInt(1, n / 2 + 1)
        return if (rnd.nextBoolean()) LayerMove(face, depth, depth, rnd.nextInt(1, 4)) else LayerMove(face, 1, depth, rnd.nextInt(1, 4))
    }
}
