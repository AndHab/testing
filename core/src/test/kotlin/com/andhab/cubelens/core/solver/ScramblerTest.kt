package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubeValidator
import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.FaceletCube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ScramblerTest {

    @Test
    fun randomMovesHaveTheRequestedLength() {
        assertEquals(25, Scrambler.randomMoves().size)
        assertEquals(0, Scrambler.randomMoves(0).size)
        assertEquals(7, Scrambler.randomMoves(7).size)
        assertThrows(IllegalArgumentException::class.java) { Scrambler.randomMoves(-1) }
    }

    @Test
    fun randomMovesNeverCancelOrMerge() {
        val random = Random(1)
        repeat(1000) {
            val moves = Scrambler.randomMoves(30, random)
            for (i in 1 until moves.size) {
                assertNotEquals(moves[i - 1].face, moves[i].face)
                if (i >= 2) {
                    val sameAxis = moves[i].face.opposite == moves[i - 1].face && moves[i - 2].face == moves[i].face
                    assertTrue("three turns on one axis in $moves", !sameAxis)
                }
            }
        }
    }

    @Test
    fun scramblesAreReproducibleFromASeed() {
        assertEquals(Scrambler.randomMoves(25, Random(42)), Scrambler.randomMoves(25, Random(42)))
        assertEquals(Scrambler.randomState(Random(42)), Scrambler.randomState(Random(42)))
        assertNotEquals(Scrambler.randomState(Random(42)), Scrambler.randomState(Random(43)))
    }

    @Test
    fun randomStatesAreValidAndLookUniform() {
        val random = Random(2)
        val samples = 6000
        var oddParity = 0
        val firstCornerTwist = IntArray(3)
        var firstCornerAtHome = 0
        var lastEdgeFlipped = 0
        repeat(samples) {
            val state: FaceletCube = Scrambler.randomState(random)
            val result = CubeValidator.validate(state)
            assertTrue(result.errors.toString(), result.isValid)
            val cubie: CubieCube = checkNotNull(result.cubie)
            oddParity += cubie.cornerParity()
            firstCornerTwist[cubie.co[0]]++
            if (cubie.cp[0] == 0) firstCornerAtHome++
            lastEdgeFlipped += cubie.eo[11]
        }
        assertClose(0.5, oddParity, samples)
        assertClose(0.5, lastEdgeFlipped, samples)
        assertClose(1.0 / 8, firstCornerAtHome, samples)
        for (count in firstCornerTwist) assertClose(1.0 / 3, count, samples)
    }

    private fun assertClose(expected: Double, count: Int, samples: Int) {
        val actual = count.toDouble() / samples
        assertTrue("expected about $expected, got $actual", abs(actual - expected) < 0.03)
    }
}
