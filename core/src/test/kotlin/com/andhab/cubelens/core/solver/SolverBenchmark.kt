package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Performance report for the two-phase solver. Skipped unless the environment variable
 * `CUBELENS_BENCH` is set:
 *
 * ```
 * CUBELENS_BENCH=1 ./gradlew :core:test --tests '*SolverBenchmark' --rerun -i
 * ```
 */
class SolverBenchmark {

    @Test
    fun report() {
        assumeTrue("Set CUBELENS_BENCH=1 to run the solver benchmark", System.getenv("CUBELENS_BENCH") != null)

        repeat(2) {
            val start = System.nanoTime()
            SolverTables.build()
            println("Table build: %.0f ms".format(millisSince(start)))
        }
        val tables = SolverFixture.tables

        val cache = File.createTempFile("cubelens-tables", ".bin")
        try {
            val writeStart = System.nanoTime()
            TableCache.write(tables, cache)
            val writeMillis = millisSince(writeStart)
            val readStart = System.nanoTime()
            checkNotNull(TableCache.read(cache))
            println(
                "Cache: %.1f MB, write %.0f ms, read %.0f ms"
                    .format(cache.length() / 1e6, writeMillis, millisSince(readStart)),
            )
        } finally {
            cache.delete()
        }

        val superflip = SolverFixture.superflip
        val superflipStart = System.nanoTime()
        val superflipSolution = TwoPhaseSolver.solveWith(tables, superflip, 20, 60_000)
        println("Superflip: ${superflipSolution.length} moves in %.0f ms".format(millisSince(superflipStart)))

        val cubes = List(500) { Scrambler.randomState(Random(it)) }
        cubes.take(50).forEach { TwoPhaseSolver.solveWith(tables, it, 21, 2_000) } // JIT warm-up
        for (target in listOf(30, 21, 20)) {
            val times = DoubleArray(cubes.size)
            var totalLength = 0
            var reached = 0
            for ((i, cube) in cubes.withIndex()) {
                val start = System.nanoTime()
                val solution = TwoPhaseSolver.solveWith(tables, cube, target, 2_000)
                times[i] = millisSince(start)
                totalLength += solution.length
                if (solution.length <= target) reached++
            }
            times.sort()
            println(
                "Target %d: avg %.1f ms, median %.1f ms, p95 %.1f ms, max %.0f ms; avg %.2f moves; %d/%d reached"
                    .format(
                        target, times.average(), times[times.size / 2], times[times.size * 95 / 100], times.last(),
                        totalLength.toDouble() / cubes.size, reached, cubes.size,
                    ),
            )
        }

        // Nearly solved cubes with the default settings, as when someone scans a cube after a few twists.
        for (length in listOf(4, 8, 9, 10, 12)) {
            val random = Random(length)
            val scrambles = List(200) { Scrambler.randomMoves(length, random) }
            report("$length-move scrambles", scrambles.map { FaceletCube.scrambled(it) }) { i, solution ->
                solution.length > scrambles[i].size
            }
        }
        // The most expensive case for the complete shallow phase 1 search: a short phase 1 distance
        // (one or two quarter turns) but a long solution.
        val phase2Moves = SolverTables.PHASE2_MOVES.map { Move.entries[it] }
        val quarterTurns = Move.entries.filter { it.ordinal !in SolverTables.PHASE2_MOVES }
        for (quarters in 1..2) {
            val random = Random(quarters)
            val cubes = List(100) {
                val moves = List(40) { phase2Moves.random(random) } + List(quarters) { quarterTurns.random(random) }
                FaceletCube.scrambled(moves)
            }
            report("Deep H position + $quarters quarter turns", cubes, longer = null)
        }
    }

    /**
     * Solves [cubes] with the default settings and prints timings and, if [longer] is given, for how
     * many cubes it says the solution is longer than their scramble.
     */
    private fun report(label: String, cubes: List<FaceletCube>, longer: ((Int, Solution) -> Boolean)?) {
        val times = DoubleArray(cubes.size)
        var totalLength = 0
        var longerCount = 0
        for ((i, cube) in cubes.withIndex()) {
            val start = System.nanoTime()
            val solution = TwoPhaseSolver.solve(cube)
            times[i] = millisSince(start)
            totalLength += solution.length
            if (longer?.invoke(i, solution) == true) longerCount++
        }
        times.sort()
        val longerText = if (longer == null) "" else ", $longerCount longer than the scramble"
        println(
            "%s: avg %.2f moves%s; avg %.1f ms, p95 %.1f ms, max %.0f ms".format(
                label, totalLength.toDouble() / cubes.size, longerText,
                times.average(), times[times.size * 95 / 100], times.last(),
            ),
        )
    }

    private fun millisSince(start: Long) = (System.nanoTime() - start) / 1e6
}
