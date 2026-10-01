package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NxNModelTest {

    @Test
    fun orbitCountsAndSizes() {
        for (n in 2..10) {
            val m = NxNModel.of(n)
            assertEquals("wing orbits of $n", maxOf(0, (n - 2) / 2), m.wingOrbits.size)
            val inner = maxOf(0, n - 2)
            assertEquals("center orbits of $n", (inner * inner - n % 2) / 4, m.centerOrbits.size)
            for (orbit in m.wingOrbits + m.centerOrbits) assertEquals(24, orbit.size)
            val covered = (m.wingOrbits + m.centerOrbits).flatMap { o -> o.slots.flatMap { it.toList() } }
            assertEquals(covered.size, covered.toSet().size)
            val expected = (0 until m.stickerCount).count {
                m.geometry.kindOf(it) == PieceKind.WING || m.geometry.kindOf(it) == PieceKind.CENTER
            }
            assertEquals(expected, covered.size)
            assertEquals(n % 2 == 1, m.midges != null)
            assertEquals(n % 2 == 1, m.fixedCenters != null)
            // Wing orbits are ordered from the corners inwards.
            assertEquals((1..m.wingOrbits.size).toList(), m.wingOrbits.map { it.depth })
        }
    }

    @Test
    fun frameOfTheThreeByThreeIsTheKociembaLayout() {
        val m = NxNModel.of(3)
        for (i in 0 until 8) assertArrayEquals(CubieCube.CORNER_FACELET[i], m.corners[i])
        for (i in 0 until 12) assertArrayEquals(CubieCube.EDGE_FACELET[i], m.midges!![i])
        assertNull(NxNModel.of(4).midges)
    }

    /**
     * Wing handedness: in every orbit the 24 slots of a solved cube show 24 different ordered color
     * pairs, the two wings of each edge are mirror images of each other, and no sequence of moves
     * ever produces a pair outside that set (a wing cannot be flipped in place).
     */
    @Test
    fun wingsHaveAHandednessThatMovesPreserve() {
        val random = Random(5)
        for (n in 4..10) {
            val m = NxNModel.of(n)
            val solved = NxNCube.solved(n)
            for (orbit in m.wingOrbits) {
                val pairs = orbit.slots.map { (a, b) -> solved[a] to solved[b] }
                assertEquals(24, pairs.toSet().size)
                for ((x, y) in pairs) assertTrue("mirror of $x-$y", (y to x) in pairs)
                for (slot in orbit.slots) {
                    val (a, b) = slot
                    assertEquals(m.geometry.position[a], m.geometry.position[b])
                }
                val cube = solved.apply(List(60) { m.layerMove(random.nextInt(m.singleCodeCount)) })
                val scrambledPairs = orbit.slots.map { (a, b) -> cube[a] to cube[b] }
                assertEquals(pairs.toSet(), scrambledPairs.toSet())
            }
        }
    }

    @Test
    fun codesMatchLayerMoves() {
        for (n in 2..7) {
            val m = NxNModel.of(n)
            assertEquals(9 * n + if (n % 2 == 0) 9 else 0, m.codeCount)
            for (code in 0 until m.codeCount) {
                val move = m.layerMove(code)
                assertArrayEquals(m.geometry.permutation(move), m.perm(code))
                assertEquals(code, m.inverse(m.inverse(code)))
                assertEquals(move.inverse, m.layerMove(m.inverse(code)))
                assertTrue(move.face in NxNModel.AXIS_FACES)
                for (i in 0 until m.stickerCount) assertEquals(i, m.perm(code)[m.dest(code)[i]])
                assertEquals(m.toLayer(code) != n - 1, m.keepsDblCorner(code))
            }
            if (n % 2 == 0) {
                // Block codes turn layers 0..n-2: the opposite outer turn plus a whole-cube rotation.
                val code = m.blockCode(1, 1)
                assertEquals(LayerMove(Face.R, 1, n - 1, 1), m.layerMove(code))
                assertNotEquals(true, m.isSingle(code))
            }
        }
    }

    @Test
    fun slotDestinationsFollowStickers() {
        val m = NxNModel.of(6)
        for (orbit in m.wingOrbits + m.centerOrbits) {
            for (code in 0 until m.codeCount) {
                val d = m.dest(code)
                for (s in 0 until orbit.size) {
                    val target = orbit.slotDest[code][s]
                    orbit.slots[s].forEachIndexed { k, sticker -> assertEquals(orbit.slots[target][k], d[sticker]) }
                }
            }
        }
    }
}
