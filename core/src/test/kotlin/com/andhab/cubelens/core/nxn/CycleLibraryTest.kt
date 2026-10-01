package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CycleLibraryTest {

    /**
     * Every library entry 3-cycles exactly the slots it claims and leaves every other sticker alone;
     * for even sizes no move of any entry turns an outer D, L or B layer. All entries are checked
     * for 4×4 and 5×5, a sample for 6×6 and 7×7.
     */
    @Test
    fun everyCycleIsPureAndComplete() {
        val random = Random(1)
        for (n in 4..7) {
            val library = CycleLibrary.of(n)
            val start = NxNTestCubes.scrambled(n, random)
            for (orbitCycles in library.wingCycles + library.centerCycles) {
                val cycles = orbitCycles.cycles
                assertEquals(4048, cycles.size)
                assertEquals(4048, cycles.map { CycleLibrary.normalizedKey(it.a, it.b, it.c) }.toSet().size)
                val checked = if (n <= 5) cycles else cycles.shuffled(random).take(300)
                for (c in checked) {
                    val moves = MoveSequence(n).also { c.appendTo(it) }.toLayerMoves()
                    assertEquals(c.cost, moves.size)
                    if (n % 2 == 0) {
                        val dbl = NxNTestCubes.dblStickers(n)
                        var cube = start
                        for (m in moves) {
                            cube = cube.apply(m)
                            for (s in dbl) assertEquals("DBL corner moved by $m", start[s], cube[s])
                        }
                    }
                    val after = start.apply(moves)
                    val orbit = orbitCycles.orbit
                    val expected = start.toColors().toMutableList()
                    // a -> b -> c -> a, sticker by sticker (wing slots keep their sticker order).
                    for (k in orbit.slots[c.a].indices) {
                        expected[orbit.slots[c.b][k]] = start[orbit.slots[c.a][k]]
                        expected[orbit.slots[c.c][k]] = start[orbit.slots[c.b][k]]
                        expected[orbit.slots[c.a][k]] = start[orbit.slots[c.c][k]]
                    }
                    assertEquals("cycle ${c.a}->${c.b}->${c.c}: ${LayerMove.format(moves)}", expected, after.toColors())
                }
            }
        }
    }

    @Test
    fun cyclesAreShort() {
        for (n in 4..7) {
            val library = CycleLibrary.of(n)
            for (o in library.wingCycles + library.centerCycles) {
                assertTrue("average ${o.averageCost} for $n", o.averageCost < 10.0)
                assertTrue(o.cycles.all { it.cost in 6..16 })
            }
        }
    }

    @Test
    fun cornerSolverIsOptimalAndKeepsDbl() {
        val random = Random(3)
        var total = 0
        repeat(300) {
            val scramble = List(30) { listOf(Move.U1, Move.R1, Move.F1, Move.U2, Move.R3)[random.nextInt(5)] }
            val cube = CubieCube().apply(scramble)
            val solution = CornerSolver.solve(cube.cp, cube.co)
            assertTrue(solution.size <= CornerSolver.MAX_LENGTH)
            assertTrue(solution.all { it.face in NxNModel.AXIS_FACES })
            val solved = cube.apply(solution)
            assertEquals((0 until 8).toList(), solved.cp.take(8))
            assertEquals(List(8) { 0 }, solved.co.toList())
            total += solution.size
        }
        // Random 2x2 states average about 8.8 moves (optimal, half-turn metric).
        assertTrue("average ${total / 300.0}", total / 300.0 in 7.5..9.5)
        for (m in listOf(Move.U1, Move.R2, Move.F3)) {
            val cube = CubieCube().apply(listOf(m))
            assertEquals(listOf(m.inverse), CornerSolver.solve(cube.cp, cube.co))
        }
    }
}
