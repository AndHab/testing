package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.solver.Coordinates.N_FLIP
import com.andhab.cubelens.core.solver.Coordinates.N_PERM8
import com.andhab.cubelens.core.solver.Coordinates.N_SLICE_PERM
import com.andhab.cubelens.core.solver.Coordinates.N_SLICE_SORTED
import com.andhab.cubelens.core.solver.Coordinates.N_TWIST
import com.andhab.cubelens.core.solver.SolverTables.Companion.N_MOVES
import com.andhab.cubelens.core.solver.SolverTables.Companion.N_PHASE2_MOVES
import com.andhab.cubelens.core.solver.SolverTables.Companion.PHASE2_MOVES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoordinatesTest {

    private val tables get() = SolverFixture.tables

    @Test
    fun solvedCubeHasAllCoordinatesZero() {
        val c = CubieCube.SOLVED
        assertEquals(0, Coordinates.twist(c.co))
        assertEquals(0, Coordinates.flip(c.eo))
        assertEquals(0, Coordinates.sliceSorted(c.ep))
        assertEquals(0, Coordinates.cornerPerm(c.cp))
        assertEquals(0, Coordinates.udEdgePerm(c.ep))
        assertEquals(0, Coordinates.slicePerm(c.ep))
    }

    @Test
    fun coordinatesRoundTripOverTheirWholeRange() {
        val co = IntArray(8)
        for (t in 0 until N_TWIST) {
            Coordinates.setTwist(co, t)
            assertEquals(0, co.sum() % 3)
            assertEquals(t, Coordinates.twist(co))
        }
        val eo = IntArray(12)
        for (f in 0 until N_FLIP) {
            Coordinates.setFlip(eo, f)
            assertEquals(0, eo.sum() % 2)
            assertEquals(f, Coordinates.flip(eo))
        }
        val ep = IntArray(12)
        for (s in 0 until N_SLICE_SORTED) {
            Coordinates.setSliceSorted(ep, s)
            assertEquals((0 until 12).toList(), ep.sorted())
            assertEquals(s, Coordinates.sliceSorted(ep))
        }
        val cp = IntArray(8)
        for (c in 0 until N_PERM8) {
            Coordinates.setCornerPerm(cp, c)
            assertEquals((0 until 8).toList(), cp.sorted())
            assertEquals(c, Coordinates.cornerPerm(cp))
            Coordinates.setUdEdgePerm(ep, c)
            assertEquals(c, Coordinates.udEdgePerm(ep))
        }
        for (c in 0 until N_SLICE_PERM) {
            Coordinates.setSlicePerm(ep, c)
            assertEquals(c, Coordinates.slicePerm(ep))
        }
    }

    @Test
    fun sliceCoordinateIsZeroExactlyWhenTheSliceEdgesAreInTheSlice() {
        val random = Random(3)
        repeat(2000) {
            val cube = CubieCube.SOLVED.apply(List(random.nextInt(1, 12)) { Move.entries[random.nextInt(18)] })
            val sorted = Coordinates.sliceSorted(cube.ep)
            val inSlice = (8 until 12).all { cube.ep[it] >= 8 }
            assertEquals(inSlice, sorted / N_SLICE_PERM == 0)
            if (inSlice) assertEquals(Coordinates.slicePerm(cube.ep), sorted % N_SLICE_PERM)
        }
    }

    @Test
    fun phase1MoveTablesAgreeWithTheCubieModel() {
        val random = Random(4)
        repeat(300) {
            val cube = CubieCube.SOLVED.apply(List(30) { Move.entries[random.nextInt(18)] })
            for (move in Move.entries) {
                val next = cube.apply(move)
                val m = move.ordinal
                assertEquals(Coordinates.twist(next.co), tables.twistMove[Coordinates.twist(cube.co) * N_MOVES + m].code)
                assertEquals(Coordinates.flip(next.eo), tables.flipMove[Coordinates.flip(cube.eo) * N_MOVES + m].code)
                assertEquals(
                    Coordinates.sliceSorted(next.ep),
                    tables.sliceSortedMove[Coordinates.sliceSorted(cube.ep) * N_MOVES + m].code,
                )
            }
        }
    }

    @Test
    fun phase2MoveTablesAgreeWithTheCubieModel() {
        val random = Random(5)
        repeat(300) {
            val cube = CubieCube.SOLVED.apply(List(30) { Move.entries[PHASE2_MOVES[random.nextInt(N_PHASE2_MOVES)]] })
            for (k in 0 until N_PHASE2_MOVES) {
                val next = cube.apply(Move.entries[PHASE2_MOVES[k]])
                assertEquals(
                    Coordinates.cornerPerm(next.cp),
                    tables.cornerPermMove[Coordinates.cornerPerm(cube.cp) * N_PHASE2_MOVES + k].code,
                )
                assertEquals(
                    Coordinates.udEdgePerm(next.ep),
                    tables.udEdgePermMove[Coordinates.udEdgePerm(cube.ep) * N_PHASE2_MOVES + k].code,
                )
                assertEquals(
                    Coordinates.slicePerm(next.ep),
                    tables.slicePermMove[Coordinates.slicePerm(cube.ep) * N_PHASE2_MOVES + k].code,
                )
            }
        }
    }

    @Test
    fun phase2MovesAreExactlyTheMovesThatKeepTheCubeInH() {
        val expected = Move.parseSequence("U U2 U' R2 F2 D D2 D' L2 B2").map { it.ordinal }
        assertEquals(expected, PHASE2_MOVES.toList())
        for (move in Move.entries) {
            val c = CubieCube.moveCube(move)
            val keepsH = Coordinates.twist(c.co) == 0 && Coordinates.flip(c.eo) == 0 &&
                Coordinates.sliceSorted(c.ep) / N_SLICE_PERM == 0
            assertEquals(move.toString(), move.ordinal in PHASE2_MOVES, keepsH)
        }
    }

    @Test
    fun pruningValuesAreLowerBoundsOfTheScrambleLength() {
        val random = Random(6)
        repeat(500) {
            val length = random.nextInt(0, 9)
            val p1 = CubieCube.SOLVED.apply(Scrambler.randomMoves(length, random))
            val twist = Coordinates.twist(p1.co)
            val flip = Coordinates.flip(p1.eo)
            val slice = Coordinates.sliceSorted(p1.ep) / N_SLICE_PERM
            assertTrue(tables.sliceTwistPrune[slice * N_TWIST + twist] <= length)
            assertTrue(tables.sliceFlipPrune[slice * N_FLIP + flip] <= length)
            assertTrue(tables.twistFlipPrune[twist * N_FLIP + flip] <= length)

            val p2 = CubieCube.SOLVED.apply(List(length) { Move.entries[PHASE2_MOVES[random.nextInt(N_PHASE2_MOVES)]] })
            val sp = Coordinates.slicePerm(p2.ep)
            assertTrue(tables.cornerSlicePrune[Coordinates.cornerPerm(p2.cp) * N_SLICE_PERM + sp] <= length)
            assertTrue(tables.edgeSlicePrune[Coordinates.udEdgePerm(p2.ep) * N_SLICE_PERM + sp] <= length)
        }
    }

    @Test
    fun pruningTablesAreZeroOnlyAtTheGoal() {
        for (table in tables.byteTables) {
            assertEquals(0, table[0].toInt())
            assertEquals(1, table.count { it.toInt() == 0 })
            assertTrue(table.all { it in 0..20 })
        }
    }

    @Test
    fun tablesFitTheMemoryBudget() {
        val bytes = SolverTables.CHAR_TABLE_SIZES.sumOf { it * 2L } + SolverTables.BYTE_TABLE_SIZES.sumOf { it.toLong() }
        assertEquals(TableCache.PAYLOAD_BYTES, bytes)
        assertTrue("$bytes bytes", bytes < 12L * 1024 * 1024)
    }
}
