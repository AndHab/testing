package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NxNScramblerTest {

    @Test
    fun defaultLengths() {
        assertEquals(listOf(11, 20, 40, 60, 80, 100), (2..7).map { NxNScrambler.defaultLength(it) })
        assertThrows(IllegalArgumentException::class.java) { NxNScrambler.defaultLength(11) }
    }

    @Test
    fun movesFollowTheWcaStyle() {
        for (n in 2..10) {
            val random = Random(n)
            val moves = NxNScrambler.randomMoves(n, random, 500)
            assertEquals(500, moves.size)
            for (m in moves) {
                assertEquals("outer or wide turns only", 1, m.fromDepth)
                when {
                    n == 2 -> assertTrue("2x2 uses U, R and F", m.face in NxNModel.AXIS_FACES && m.width == 1)
                    n == 3 -> assertEquals(1, m.width)
                    n % 2 == 1 -> assertTrue(m.width <= (n - 1) / 2)
                    m.face in NxNModel.AXIS_FACES -> assertTrue(m.width <= n / 2)
                    else -> assertTrue(m.width < n / 2)
                }
            }
            for (i in 1 until moves.size) {
                assertFalse("same face twice: ${moves[i - 1]} ${moves[i]}", moves[i].face == moves[i - 1].face)
                if (i >= 2) {
                    val axes = (i - 2..i).map { NxNModel.axisOf(moves[it].face) }.toSet()
                    assertTrue("three turns on one axis at $i", axes.size > 1)
                }
            }
            // Big cubes get wide turns too.
            if (n >= 4) assertTrue(moves.any { it.width > 1 })
        }
    }

    @Test
    fun seededScramblesRepeat() {
        assertEquals(NxNScrambler.randomMoves(5, Random(3)), NxNScrambler.randomMoves(5, Random(3)))
        assertEquals(60, NxNScrambler.randomMoves(5, Random(3)).size)
        assertEquals(emptyList<LayerMove>(), NxNScrambler.randomMoves(4, Random(3), 0))
        assertThrows(IllegalArgumentException::class.java) { NxNScrambler.randomMoves(4, Random(3), -1) }
    }

    @Test
    fun scramblesActuallyScramble() {
        for (n in 2..7) {
            val cube = NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(n * 7)))
            assertFalse(cube.isSolved)
            assertTrue(NxNValidator.validate(cube).isValid)
            // Big cubes: every face is mixed after a full-length scramble (a small cube may keep a face).
            if (n >= 4) for (face in Face.entries) assertTrue(cube.face(face).toSet().size >= 3)
        }
    }
}
