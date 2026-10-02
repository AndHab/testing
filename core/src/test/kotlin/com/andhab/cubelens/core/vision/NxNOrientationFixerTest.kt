package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.nxn.NxNValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NxNOrientationFixerTest {

    private fun face(n: Int, colors: List<CubeColor>, face: Face) = colors.subList(face.ordinal * n * n, (face.ordinal + 1) * n * n)

    /** [colors] with every face turned by [turns] (one entry per face). */
    private fun rotated(n: Int, colors: List<CubeColor>, turns: IntArray): List<CubeColor> {
        var out = colors
        for (f in Face.entries) out = NxNOrientationFixer.rotateFace(n, out, f, turns[f.ordinal])
        return out
    }

    /** A random scramble of an [n]x[n] cube using only half turns (faces then show few colors: many symmetric, ambiguous readings). */
    private fun halfTurnScramble(n: Int, random: Random): NxNCube =
        NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, random).map { it.copy(turns = 2) })

    @Test
    fun rotateFaceTurnsLikeTheFaceMove() {
        val random = Random(1)
        for (n in 2..7) {
            val cube = NxNSessions.scrambled(n, random)
            val colors = cube.toColors()
            for (f in Face.entries) {
                var turned = colors
                repeat(4) { turned = NxNOrientationFixer.rotateFace(n, turned, f, 1) }
                assertEquals(colors, turned)
                assertEquals(NxNOrientationFixer.rotateFace(n, colors, f, -1), NxNOrientationFixer.rotateFace(n, colors, f, 3))
                // A clockwise quarter turn of the outer layer turns that face's own stickers the same way.
                val moved = cube.apply(LayerMove(f, 1, 1, 1)).toColors()
                assertEquals(face(n, moved, f), face(n, NxNOrientationFixer.rotateFace(n, colors, f, 1), f))
                // destinationOf follows a sticker.
                for (p in 0 until n * n) {
                    val i = f.ordinal * n * n + p
                    for (k in 0 until 4) {
                        val marked = colors.indices.map { if (it == i) CubeColor.WHITE else CubeColor.RED }
                        assertEquals(CubeColor.WHITE, NxNOrientationFixer.rotateFace(n, marked, f, k)[NxNOrientationFixer.destinationOf(n, i, k)])
                    }
                }
            }
        }
    }

    @Test
    fun agreesWithTheThreeByThreeFixer() {
        val random = Random(2)
        val halfTurns = Move.entries.filter { it.notation.endsWith("2") }
        var ambiguous = 0
        repeat(300) { k ->
            val moves = if (k % 3 == 0) List(random.nextInt(8, 20)) { halfTurns[random.nextInt(halfTurns.size)] } else List(25) { Move.entries[random.nextInt(18)] }
            val colors = FaceletCube.scrambled(moves).toColors()
            val scanned = rotated(3, colors, IntArray(6) { random.nextInt(4) })
            val expected = OrientationFixer.orient(scanned)
            val actual = NxNOrientationFixer.orient(3, scanned)
            assertNotNull(expected)
            assertNotNull(actual)
            assertEquals(expected!!.colors, actual!!.colors)
            assertEquals(expected.rotations, actual.rotations)
            assertEquals(expected.ambiguous, actual.ambiguous)
            if (actual.readings > 1) ambiguous++
        }
        assertTrue("expected ambiguous readings among half-turn scrambles, got $ambiguous", ambiguous > 20)
        // Invalid colors: neither finds a reading.
        val colors = FaceletCube.scrambled(Move.parseSequence("R U F' L2 D B")).toColors().toMutableList()
        val red = colors.indices.first { it % 9 != 4 && colors[it] == CubeColor.RED }
        val orange = colors.indices.first { it % 9 != 4 && colors[it] == CubeColor.ORANGE }
        colors[red] = CubeColor.ORANGE
        colors[orange] = CubeColor.RED
        assertNull(OrientationFixer.orient(colors))
        assertNull(NxNOrientationFixer.orient(3, colors))
    }

    /**
     * Every combination of face rotations validated in full ([NxNValidator]), in the search's order
     * (face U most significant): the preferred reading, the readings' colors, and the ambiguous stickers.
     */
    private class BruteForce(n: Int, scanned: List<CubeColor>) {
        val readings = LinkedHashSet<List<CubeColor>>()
        var best: List<CubeColor>? = null
        var bestTurns: IntArray? = null

        init {
            var bestKey = Int.MAX_VALUE
            for (combo in 0 until 4096) {
                val turns = IntArray(6) { f -> (combo shr (2 * (5 - f))) and 3 }
                var colors = scanned
                for (f in Face.entries) colors = NxNOrientationFixer.rotateFace(n, colors, f, turns[f.ordinal])
                if (colors in readings) continue
                if (!NxNValidator.validate(NxNCube.of(n, colors)).isValid) continue
                readings += colors
                val key = turns.count { it != 0 } * 100 + turns.sumOf { minOf(it, 4 - it) }
                if (key < bestKey) {
                    bestKey = key
                    best = colors
                    bestTurns = turns
                }
            }
        }

        fun ambiguous(): Set<Int> {
            val chosen = best ?: return emptySet()
            return readings.flatMap { other -> chosen.indices.filter { chosen[it] != other[it] } }.toSet()
        }
    }

    @Test
    fun findsExactlyTheReadingsThatFullValidationAccepts() {
        // The pruned search must agree with validating all 4096 combinations in full: same preferred
        // reading, same number of distinct valid readings, same ambiguous stickers. Random scrambles,
        // half-turn scrambles (symmetric faces, several valid readings) and two stickers swapped.
        val random = Random(3)
        var cases = 0
        var multiple = 0
        for ((n, count) in listOf(2 to 10, 4 to 3, 5 to 3)) {
            repeat(count) { k ->
                val cube = if (k % 2 == 0) NxNSessions.scrambled(n, random) else halfTurnScramble(n, random)
                var colors = cube.toColors()
                if (k == count - 1) {
                    // Two stickers of different colors swapped: no valid reading.
                    val i = colors.indices.first { it >= n * n && colors[it] != colors[0] }
                    colors = colors.toMutableList().also { it[0] = colors[i]; it[i] = colors[0] }
                }
                val scanned = rotated(n, colors, IntArray(6) { random.nextInt(4) })
                val brute = BruteForce(n, scanned)
                val fixed = NxNOrientationFixer.orient(n, scanned)
                if (brute.best == null) {
                    assertNull("${n}x$n case $k", fixed)
                } else {
                    assertNotNull("${n}x$n case $k", fixed)
                    assertEquals("${n}x$n case $k", brute.best, fixed!!.colors)
                    assertEquals("${n}x$n case $k", brute.readings.size, fixed.readings)
                    assertEquals("${n}x$n case $k", brute.ambiguous(), fixed.ambiguous)
                    assertEquals("${n}x$n case $k", brute.bestTurns!!.toList(), Face.entries.map { fixed.rotations.getValue(it) })
                    if (fixed.readings > 1) multiple++
                }
                cases++
            }
        }
        println("NxNOrientationFixer vs. brute force: $cases cases agree, $multiple with several valid readings")
        assertTrue(multiple > 0)
    }

    @Test
    fun prefersTheReferenceOrientationAndFlagsTheRest() {
        // Half-turn scrambles admit several valid readings. Held in the reference orientation the
        // preferred reading is the cube itself; held at random angles, every sticker that comes out
        // wrong is among the ambiguous ones.
        val random = Random(4)
        val report = StringBuilder()
        for (n in listOf(2, 4, 5, 7)) {
            var ambiguous = 0
            var misread = 0
            repeat(12) {
                val colors = halfTurnScramble(n, random).toColors()
                val asHeld = NxNOrientationFixer.orient(n, colors)!!
                assertEquals(colors, asHeld.colors)
                assertTrue(asHeld.rotations.values.all { it == 0 })
                val turns = IntArray(6) { random.nextInt(4) }
                val atRandom = NxNOrientationFixer.orient(n, rotated(n, colors, turns))!!
                val wrong = colors.indices.filter { atRandom.colors[it] != colors[it] }.toSet()
                assertTrue("${n}x$n: wrong $wrong not flagged in ${atRandom.ambiguous}", atRandom.ambiguous.containsAll(wrong))
                if (atRandom.readings > 1) ambiguous++
                if (wrong.isNotEmpty()) misread++
            }
            report.append("${n}x$n $ambiguous/12 ambiguous, $misread/12 misread (flagged); ")
        }
        println("Half-turn scrambles held at random angles: $report")
    }

    @Test
    fun isFastForBigCubes() {
        val random = Random(5)
        val report = StringBuilder("NxNOrientationFixer.orient:")
        for (n in listOf(2, 4, 5, 6, 7)) {
            val inputs = List(30) { k ->
                val colors = when (k % 3) {
                    0 -> NxNSessions.scrambled(n, random).toColors()
                    1 -> halfTurnScramble(n, random).toColors()
                    // Two stickers swapped: invalid, the search must exhaust its options.
                    else -> NxNSessions.scrambled(n, random).toColors().toMutableList().also { c ->
                        val i = random.nextInt(c.size)
                        val j = c.indices.first { c[it] != c[i] }
                        c[i] = c[j].also { c[j] = c[i] }
                    }
                }
                rotated(n, colors, IntArray(6) { random.nextInt(4) })
            }
            inputs.forEach { NxNOrientationFixer.orient(n, it) } // warm-up
            var worst = 0L
            var total = 0L
            for (input in inputs) {
                val start = System.nanoTime()
                val result = NxNOrientationFixer.orient(n, input)
                val elapsed = System.nanoTime() - start
                worst = maxOf(worst, elapsed)
                total += elapsed
                if (inputs.indexOf(input) % 3 != 2) assertNotNull(result)
            }
            report.append(" ${n}x$n average %.2f ms, worst %.2f ms;".format(total / 1e6 / inputs.size, worst / 1e6))
            assertTrue("${n}x$n: worst ${worst / 1e6} ms", worst / 1e6 < 300.0)
        }
        println(report)
    }

    @Test
    fun rejectsColorsThatAreNotACube() {
        for (n in 2..7) {
            assertNull(NxNOrientationFixer.orient(n, List(6 * n * n) { CubeColor.WHITE }))
            assertNull(NxNOrientationFixer.orient(n, List(6 * n * n - 1) { CubeColor.WHITE }))
            val solved = NxNCube.solved(n).toColors()
            assertEquals(solved, NxNOrientationFixer.orient(n, solved)!!.colors)
            // A U corner sticker swapped with an R corner sticker: whichever way the faces turn, the
            // corner holding the red U sticker shows red twice or red and orange.
            val swapped = solved.toMutableList().also { it[0] = solved[n * n]; it[n * n] = solved[0] }
            assertNull(NxNOrientationFixer.orient(n, swapped))
        }
    }
}
