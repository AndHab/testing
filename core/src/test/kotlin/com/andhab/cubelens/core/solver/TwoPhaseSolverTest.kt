package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.solver.SolverFixture.assertSolves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TwoPhaseSolverTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun prepareTables() {
            SolverFixture.tables
        }
    }

    @Test
    fun solvedCubeNeedsNoMoves() {
        val solution = TwoPhaseSolver.solve(FaceletCube.SOLVED)
        assertEquals(emptyList<Move>(), solution.moves)
        assertEquals(0, solution.length)
        assertEquals("", solution.toString())
    }

    @Test
    fun singleTurnsAreUndoneByTheirInverse() {
        for (move in Move.entries) {
            val solution = TwoPhaseSolver.solve(FaceletCube.SOLVED.apply(move))
            assertEquals("after $move", listOf(move.inverse), solution.moves)
        }
    }

    @Test
    fun shortScramblesGetSolutionsNoLongerThanTheScramble() {
        val random = Random(11)
        for (length in 2..10) {
            repeat(4) {
                val scramble = Scrambler.randomMoves(length, random)
                val cube = FaceletCube.scrambled(scramble)
                val solution = TwoPhaseSolver.solve(cube, targetLength = length, timeoutMillis = 30_000)
                assertSolves(cube, solution)
                assertTrue("'$solution' is longer than '${Move.format(scramble)}'", solution.length <= length)
            }
        }
    }

    @Test
    fun randomMoveScramblesAreSolved() {
        val random = Random(2024)
        val cubes = List(300) { FaceletCube.scrambled(Scrambler.randomMoves(25, random)) }
        assertShortSolutions("300 random-move scrambles", cubes)
    }

    @Test
    fun randomStatesAreSolved() {
        val random = Random(1982)
        val cubes = List(300) { Scrambler.randomState(random) }
        assertShortSolutions("300 random states", cubes)
    }

    /** Solves [cubes] with the default target and timeout, like the app does. */
    private fun assertShortSolutions(label: String, cubes: List<FaceletCube>) {
        var totalLength = 0
        var longest = 0
        val start = System.nanoTime()
        for (cube in cubes) {
            val solution = TwoPhaseSolver.solve(cube)
            assertSolves(cube, solution)
            assertTrue("'$solution' has ${solution.length} moves", solution.length <= 23)
            totalLength += solution.length
            longest = maxOf(longest, solution.length)
        }
        val average = totalLength.toDouble() / cubes.size
        val millis = (System.nanoTime() - start) / 1e6 / cubes.size
        println("$label: average %.2f moves, longest %d, %.1f ms per cube".format(average, longest, millis))
        assertTrue("average length $average", average <= 21.5)
    }

    @Test
    fun superflipIsSolvedInExactlyTwentyMoves() {
        val solution = TwoPhaseSolver.solve(SolverFixture.superflip, targetLength = 20, timeoutMillis = 60_000)
        assertSolves(SolverFixture.superflip, solution)
        // The superflip is known to need 20 moves, so anything shorter would be a bug.
        assertEquals(20, solution.length)
    }

    @Test
    fun scannedCubeIsSolved() {
        val solution = TwoPhaseSolver.solve(SolverFixture.scannedCube)
        assertSolves(SolverFixture.scannedCube, solution)
        assertTrue("'$solution' has ${solution.length} moves", solution.length <= 20)
    }

    @Test
    fun symmetricPatternsAreSolved() {
        val patterns = mapOf(
            "checkerboard" to "U2 D2 F2 B2 L2 R2",
            "six spots" to "U D' R L' F B' U D'",
            "cube in a cube" to "F L F U' R U F2 L2 U' L' B D' B' L2 U",
            "superflip" to Move.format(TwoPhaseSolver.solve(SolverFixture.superflip, 30).moves),
        )
        for ((name, sequence) in patterns) {
            val cube = FaceletCube.scrambled(Move.parseSequence(sequence))
            val solution = TwoPhaseSolver.solve(cube)
            assertSolves(cube, solution)
            assertTrue("$name: '$solution'", solution.length <= maxOf(20, Move.parseSequence(sequence).size))
        }
        // Already in phase 2's subgroup, so phase 2 alone finds the optimal six moves.
        assertEquals(6, TwoPhaseSolver.solve(FaceletCube.scrambled(Move.parseSequence("U2 D2 F2 B2 L2 R2"))).length)
    }

    @Test
    fun zeroTimeoutStillReturnsASolution() {
        val cube = Scrambler.randomState(Random(5))
        val solution = TwoPhaseSolver.solve(cube, targetLength = 0, timeoutMillis = 0)
        assertSolves(cube, solution)
        assertTrue(solution.length <= 23)
    }

    @Test
    fun searchKeepsImprovingUntilTheTimeout() {
        // The first solution found is deterministic; pick a cube where it is not yet a short one.
        val random = Random(6)
        var cube: FaceletCube
        var first: Solution
        do {
            cube = Scrambler.randomState(random)
            first = TwoPhaseSolver.solve(cube, targetLength = 30)
        } while (first.length < 21)

        val timeout = 1_000L
        val start = System.nanoTime()
        val improved = TwoPhaseSolver.solve(cube, targetLength = 0, timeoutMillis = timeout)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertSolves(cube, improved)
        // Target 0 is unreachable, so the search must use the whole budget, and then stop promptly.
        assertTrue("searched only $elapsedMillis ms", elapsedMillis >= timeout)
        assertTrue("stopped late: $elapsedMillis ms", elapsedMillis < timeout + 2_000)
        assertTrue("${improved.length} vs first ${first.length}", improved.length <= 20)
    }

    @Test
    fun interruptedSearchReturnsBestSoFar() {
        val cube = Scrambler.randomState(Random(8))
        Thread.currentThread().interrupt()
        try {
            val start = System.nanoTime()
            val solution = TwoPhaseSolver.solve(cube, targetLength = 0, timeoutMillis = 60_000)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
            assertSolves(cube, solution)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun twistedCornerIsRejected() {
        val cube = scrambledCubie().also { it.co[CubieCube.URF] = (it.co[CubieCube.URF] + 1) % 3 }
        assertRejected(cube.toFaceletCube(), listOf(CubeError.TwistedCorner))
    }

    @Test
    fun flippedEdgeIsRejected() {
        val cube = scrambledCubie().also { it.eo[CubieCube.UB] = 1 - it.eo[CubieCube.UB] }
        assertRejected(cube.toFaceletCube(), listOf(CubeError.FlippedEdge))
    }

    @Test
    fun swappedPiecesAreRejected() {
        val cube = scrambledCubie().also {
            val t = it.cp[CubieCube.URF]
            it.cp[CubieCube.URF] = it.cp[CubieCube.DRB]
            it.cp[CubieCube.DRB] = t
        }
        assertRejected(cube.toFaceletCube(), listOf(CubeError.Parity))
    }

    @Test
    fun wrongColorCountsAreRejected() {
        val faces = scrambledCubie().toFaceletCube().toList().toMutableList()
        val sticker = CubieCube.EDGE_FACELET[CubieCube.UF][0]
        faces[sticker] = if (faces[sticker] == Face.R) Face.L else Face.R
        val error = assertThrows(UnsolvableCubeException::class.java) {
            TwoPhaseSolver.solve(FaceletCube.of(faces))
        }
        assertEquals(2, error.errors.count { it is CubeError.WrongColorCount })
        assertTrue(error.errors.any { it is CubeError.ImpossibleEdge || it is CubeError.DuplicateEdge })
    }

    @Test
    fun misplacedCentersAreRejected() {
        val faces = FaceletCube.SOLVED.toList().toMutableList()
        faces[Facelets.center(Face.U)] = Face.F
        faces[Facelets.center(Face.F)] = Face.U
        val error = assertThrows(UnsolvableCubeException::class.java) {
            TwoPhaseSolver.solve(FaceletCube.of(faces))
        }
        assertTrue(error.errors.contains(CubeError.CentersNotDistinct))
    }

    @Test
    fun eightThreadsCanSolveConcurrently() {
        val threads = 8
        val perThread = 6
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val solved = Collections.synchronizedList(mutableListOf<Int>())
        val ready = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { t ->
                pool.execute {
                    val random = Random(100 + t)
                    val cubes = List(perThread) { Scrambler.randomState(random) }
                    ready.countDown()
                    ready.await()
                    for (cube in cubes) {
                        try {
                            val solution = TwoPhaseSolver.solve(cube, targetLength = 21)
                            assertSolves(cube, solution)
                            solved += solution.length
                        } catch (e: Throwable) {
                            failures += e
                        }
                    }
                }
            }
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES))
        }
        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(threads * perThread, solved.size)
        assertTrue(solved.all { it <= 23 })
    }

    @Test
    fun prepareIsIdempotent() {
        val tables = SolverFixture.tables
        TwoPhaseSolver.prepare()
        TwoPhaseSolver.prepare()
        assertTrue(TwoPhaseSolver.isPrepared)
        assertSame(tables, TwoPhaseSolver.preparedTables())
    }

    @Test
    fun solutionFormatsInStandardNotation() {
        val solution = Solution(Move.parseSequence("R U2 F' D"))
        assertEquals("R U2 F' D", solution.toString())
        assertEquals(4, solution.length)
    }

    private fun scrambledCubie(): CubieCube = CubieCube.SOLVED.apply(Move.parseSequence("R U F' D2 L B' R2 U'"))

    private fun assertRejected(cube: FaceletCube, expected: List<CubeError>) {
        val error = assertThrows(UnsolvableCubeException::class.java) { TwoPhaseSolver.solve(cube) }
        assertEquals(expected, error.errors)
        assertTrue(error.message.orEmpty().contains(expected.first().message))
    }
}
