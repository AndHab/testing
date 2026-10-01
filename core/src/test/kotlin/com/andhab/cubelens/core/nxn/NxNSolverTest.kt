package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.solver.TwoPhaseSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class NxNSolverTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun prepare() {
            NxNSolver.prepare()
        }

        /**
         * Average-length limits per size (block turns). 2×2: optimal; 3×3: two-phase; 4×4 and 5×5
         * from the targets (4×4 at the stretch goal of 160).
         */
        private val MAX_AVERAGE_MOVES = mapOf(2 to 11.0, 3 to 20.5, 4 to 160.0, 5 to 250.0, 6 to 450.0, 7 to 600.0)

        /** Time per solve: the average plus building the size's library (as the first solve of a size does). */
        private val MAX_AVERAGE_MILLIS = mapOf(2 to 50.0, 3 to 500.0, 4 to 1000.0, 5 to 1000.0, 6 to 3000.0, 7 to 3000.0)
    }

    /**
     * Checks [solution] for [cube]: it solves the cube in the orientation given by the validator's
     * scheme, never turns the DBL corner of an even cube (not even temporarily) and keeps the fixed
     * centers of an odd cube.
     */
    private fun assertSolves(cube: NxNCube, solution: NxNSolution) {
        val n = cube.n
        val scheme = NxNValidator.validate(cube).scheme!!
        val solved = cube.apply(solution.moves)
        assertTrue("${LayerMove.format(solution.moves)} does not solve $cube", solved.isSolved)
        assertEquals(NxNCube.solved(n, scheme), solved)
        assertTrue(solution.stages.all { it.moves.isNotEmpty() })
        if (n % 2 == 0) {
            val dbl = NxNTestCubes.dblStickers(n)
            var state = cube
            for (move in solution.moves) {
                val outerDlb = move.face in listOf(Face.D, Face.L, Face.B) && move.fromDepth == 1
                val farLayer = move.face in NxNModel.AXIS_FACES && move.toDepth == n
                assertFalse("$move turns the DBL corner", outerDlb || farLayer)
                state = state.apply(move)
                for (s in dbl) assertEquals(cube[s], state[s])
            }
        } else {
            for (s in NxNModel.of(n).fixedCenters!!) assertEquals(cube[s], solved[s])
        }
    }

    private fun scrambleCount(n: Int) = if (n <= 5) 30 else 10

    @Test
    fun randomScramblesOfEverySize() {
        val report = StringBuilder()
        for (n in 2..7) {
            // Building the size's library is part of the first solve; measure a fresh build.
            val buildStart = System.nanoTime()
            if (n >= 4) CycleLibrary.buildUncached(n)
            val buildMillis = (System.nanoTime() - buildStart) / 1e6
            NxNSolver.prepareSize(n)
            val random = Random(1000 + n)
            var moves = 0
            var millis = 0.0
            var longest = 0
            val count = scrambleCount(n)
            repeat(count) {
                val cube = NxNTestCubes.scrambled(n, random)
                val start = System.nanoTime()
                val solution = NxNSolver.solve(cube)
                millis += (System.nanoTime() - start) / 1e6
                assertSolves(cube, solution)
                moves += solution.length
                longest = maxOf(longest, solution.length)
                if (n == 2) assertTrue(solution.length <= 11)
                if (n == 3) assertTrue(solution.length <= 21)
            }
            val avgMoves = moves.toDouble() / count
            val avgMillis = millis / count
            report.appendLine(
                "%dx%d: %d scrambles, average %.1f moves (max %d), %.1f ms per solve, library build %.0f ms"
                    .format(n, n, count, avgMoves, longest, avgMillis, buildMillis),
            )
            assertTrue("$n: average $avgMoves moves", avgMoves <= MAX_AVERAGE_MOVES.getValue(n))
            // Even a solve that has to build the library first stays within the budget.
            assertTrue("$n: $avgMillis ms + $buildMillis ms", avgMillis + buildMillis <= MAX_AVERAGE_MILLIS.getValue(n))
        }
        println(report)
    }

    @Test
    fun stagesAreNamedForTheUser() {
        val random = Random(77)
        val expected = mapOf(
            2 to listOf(NxNSolver.STAGE_SOLVE),
            3 to listOf(NxNSolver.STAGE_SOLVE),
            4 to listOf(NxNSolver.STAGE_CORNERS, NxNSolver.STAGE_EDGES, NxNSolver.STAGE_CENTERS),
            5 to listOf(NxNSolver.STAGE_FRAME, NxNSolver.STAGE_EDGES, NxNSolver.STAGE_CENTERS),
        )
        for ((n, names) in expected) {
            val solution = NxNSolver.solve(NxNTestCubes.scrambled(n, random))
            assertEquals(names, solution.stages.map { it.name })
            assertEquals(solution.length, solution.moves.size)
        }
    }

    @Test
    fun largerSizesAreSupported() {
        val random = Random(88)
        for (n in 8..10) {
            val cube = NxNTestCubes.scrambled(n, random)
            assertSolves(cube, NxNSolver.solve(cube))
        }
    }

    @Test
    fun threeByThreeUsesTheTwoPhaseSolver() {
        val random = Random(5)
        repeat(10) {
            val cube = NxNTestCubes.scrambled(3, random)
            val expected = TwoPhaseSolver.solve(cube.toFaceletCube()!!).moves.map(LayerMove::of)
            val solution = NxNSolver.solve(cube)
            assertSolves(cube, solution)
            assertTrue(solution.length <= maxOf(expected.size, 20))
        }
        // Short scrambles are solved optimally by both, so the solutions are the same.
        for (scramble in listOf("R U F'", "L2 B D' R", "F")) {
            val cube = NxNCube.solved(3).apply(LayerMove.parseSequence(scramble))
            val expected = TwoPhaseSolver.solve(FaceletCube.SOLVED.apply(Move.parseSequence(scramble)))
            assertEquals(expected.moves.map(LayerMove::of), NxNSolver.solve(cube).moves)
        }
    }

    @Test
    fun solvedCubesNeedNoMoves() {
        for (n in 2..10) {
            val solution = NxNSolver.solve(NxNCube.solved(n))
            assertEquals(0, solution.length)
            assertTrue(solution.stages.isEmpty())
        }
    }

    /**
     * Every single block turn is undone by a one-move solution, except that a turn of an odd cube's
     * middle layer moves its fixed centers; keeping them, the rest of the cube is two moves away
     * (`M` is undone by `R' L` in the new orientation).
     */
    @Test
    fun oneMoveFromSolvedGivesTinySolutions() {
        for (n in 2..7) {
            val moves = Face.entries.flatMap { face ->
                (1..n).flatMap { from -> (from..n).map { to -> from to to } }
                    .filter { (from, to) -> !(from == 1 && to == n) }
                    .flatMap { (from, to) -> (1..3).map { LayerMove(face, from, to, it) } }
            }
            for (move in moves) {
                val cube = NxNCube.solved(n).apply(move)
                val solution = NxNSolver.solve(cube)
                assertSolves(cube, solution)
                val turnsMiddle = n % 2 == 1 && (n + 1) / 2 in move.fromDepth..move.toDepth
                val expected = if (turnsMiddle) 2 else 1
                assertTrue("$n: $move -> ${LayerMove.format(solution.moves)}", solution.length <= expected)
                if (!turnsMiddle) assertEquals(1, solution.length)
            }
        }
    }

    @Test
    fun twoMovesFromSolvedGiveTinySolutions() {
        val random = Random(9)
        for (n in 4..7) repeat(15) {
            val cube = NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, random, 2))
            val solution = NxNSolver.solve(cube)
            assertSolves(cube, solution)
            assertTrue("$n: ${LayerMove.format(solution.moves)}", solution.length <= 2)
        }
    }

    @Test
    fun knockOffColorSchemes() {
        val random = Random(99)
        val whiteOppositeBlue = mapOf(
            Face.U to CubeColor.WHITE, Face.D to CubeColor.BLUE, Face.F to CubeColor.RED,
            Face.B to CubeColor.ORANGE, Face.R to CubeColor.YELLOW, Face.L to CubeColor.GREEN,
        )
        for (n in 2..5) {
            for (scheme in listOf(whiteOppositeBlue) + List(4) { NxNTestCubes.randomScheme(random) }) {
                val cube = NxNTestCubes.scrambled(n, random, scheme)
                val solution = NxNSolver.solve(cube)
                assertSolves(cube, solution)
                if (n % 2 == 1) assertEquals(NxNCube.solved(n, scheme), cube.apply(solution.moves))
            }
        }
    }

    @Test
    fun evenCubesEndInTheScannedOrientation() {
        val random = Random(101)
        for (n in listOf(2, 4, 6)) repeat(5) {
            // A scrambled cube held in a random orientation: the DBL corner decides which way is up.
            val rotation = List(3) { LayerMove(NxNModel.AXIS_FACES[random.nextInt(3)], 1, n, random.nextInt(1, 4)) }
            val cube = NxNTestCubes.scrambled(n, random).apply(rotation)
            val solution = NxNSolver.solve(cube)
            assertSolves(cube, solution)
            val solved = cube.apply(solution.moves)
            val (d, b, l) = NxNTestCubes.dblStickers(n)
            assertEquals(cube[d], solved[d])
            assertEquals(cube[b], solved[b])
            assertEquals(cube[l], solved[l])
        }
    }

    @Test
    fun impossibleCubesAreRejectedWithTheValidatorsErrors() {
        val g = NxNGeometry.of(4)
        // A mirrored wing: its two stickers exchanged.
        val cube = NxNTestCubes.swap(NxNCube.solved(4), g.index(Face.U, 3, 1), g.index(Face.F, 0, 1))
        val exception = assertThrows(UnsolvableNxNException::class.java) { NxNSolver.solve(cube) }
        assertEquals(NxNValidator.validate(cube).errors, exception.errors)
        assertTrue(exception.errors.isNotEmpty())
    }

    @Test
    fun solvesConcurrently() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val failures = Collections.synchronizedList(ArrayList<String>())
            val tasks = (0 until 16).map { k ->
                Callable {
                    val n = 2 + k % 6
                    val cube = NxNTestCubes.scrambled(n, Random(500 + k))
                    val solution = NxNSolver.solve(cube)
                    if (!cube.apply(solution.moves).isSolved) failures += "$n: ${LayerMove.format(solution.moves)}"
                    solution.length
                }
            }
            val results = pool.invokeAll(tasks).map { it.get(60, TimeUnit.SECONDS) }
            assertTrue(failures.toString(), failures.isEmpty())
            // Same cube, same answer: solving does not depend on other threads.
            val again = NxNSolver.solve(NxNTestCubes.scrambled(4, Random(502)))
            assertEquals(results[2], again.length)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun standardSchemeStaysTheDefault() {
        assertEquals(ColorScheme.STANDARD.centers, NxNValidator.validate(NxNCube.solved(4)).scheme)
    }
}
