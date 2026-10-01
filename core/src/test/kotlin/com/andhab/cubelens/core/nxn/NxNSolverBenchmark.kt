package com.andhab.cubelens.core.nxn

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Performance report for the N×N solver: library build time and average moves, time and stage
 * lengths per size. Skipped unless the environment variable `CUBELENS_BENCH` is set:
 *
 * ```
 * CUBELENS_BENCH=1 ./gradlew :core:test --tests '*NxNSolverBenchmark' --rerun -i
 * ```
 */
class NxNSolverBenchmark {

    @Test
    fun report() {
        assumeTrue("Set CUBELENS_BENCH=1 to run the N×N benchmark", System.getenv("CUBELENS_BENCH") != null)
        val cornerStart = System.nanoTime()
        CornerSolver.prepare()
        println("Corner table: %.0f ms".format((System.nanoTime() - cornerStart) / 1e6))
        NxNSolver.prepare()
        for (n in 2..10) {
            val start = System.nanoTime()
            NxNSolver.prepareSize(n)
            val buildMillis = (System.nanoTime() - start) / 1e6
            val stats = if (n >= 4) " [${CycleLibrary.of(n).buildStats}]" else ""
            val random = Random(n * 1000 + 7)
            val count = if (n <= 5) 100 else 20
            var moves = 0
            var millis = 0.0
            val stages = LinkedHashMap<String, Int>()
            repeat(count) {
                val cube = NxNTestCubes.scrambled(n, random)
                val t = System.nanoTime()
                val solution = NxNSolver.solve(cube)
                millis += (System.nanoTime() - t) / 1e6
                moves += solution.length
                for (stage in solution.stages) stages.merge(stage.name, stage.moves.size, Int::plus)
            }
            println(
                "%dx%d: library %.0f ms%s; %d scrambles: %.1f moves, %.1f ms; stages %s".format(
                    n, n, buildMillis, stats, count, moves.toDouble() / count, millis / count,
                    stages.mapValues { "%.1f".format(it.value.toDouble() / count) },
                ),
            )
        }
    }
}
