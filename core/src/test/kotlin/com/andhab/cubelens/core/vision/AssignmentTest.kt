package com.andhab.cubelens.core.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class AssignmentTest {

    private fun permutations(n: Int): Sequence<IntArray> = sequence {
        val p = IntArray(n) { it }
        suspend fun SequenceScope<IntArray>.go(k: Int) {
            if (k == n) {
                yield(p.copyOf())
                return
            }
            for (i in k until n) {
                p[k] = p[i].also { p[i] = p[k] }
                go(k + 1)
                p[k] = p[i].also { p[i] = p[k] }
            }
        }
        go(0)
    }

    private fun total(cost: DoubleArray, cols: Int, assignment: IntArray) =
        assignment.indices.sumOf { cost[it * cols + assignment[it]] }

    @Test
    fun matchesBruteForceOnSquareMatrices() {
        val random = Random(9)
        repeat(60) {
            val n = random.nextInt(1, 8)
            val cost = DoubleArray(n * n) { random.nextDouble(0.0, 100.0).let { v -> if (random.nextInt(5) == 0) v.toInt().toDouble() else v } }
            val best = permutations(n).minOf { total(cost, n, it) }
            val result = Assignment.solve(cost, n, n)
            assertEquals(n, result.toSet().size)
            assertEquals(best, total(cost, n, result), 1e-9)
        }
    }

    @Test
    fun handlesRectangularMatricesAndForbiddenCells() {
        val random = Random(4)
        repeat(30) {
            val rows = random.nextInt(1, 5)
            val cols = rows + random.nextInt(0, 3)
            val cost = DoubleArray(rows * cols) { if (random.nextInt(6) == 0) Double.POSITIVE_INFINITY else random.nextDouble(10.0) }
            // Brute force over injective maps rows -> cols.
            var best = Double.POSITIVE_INFINITY
            for (perm in permutations(cols)) {
                val t = (0 until rows).sumOf { r -> cost[r * cols + perm[r]].let { if (it.isFinite()) it else Assignment.FORBIDDEN } }
                if (t < best) best = t
            }
            val result = Assignment.solve(cost, rows, cols)
            assertEquals(rows, result.toSet().size)
            val got = (0 until rows).sumOf { r -> cost[r * cols + result[r]].let { if (it.isFinite()) it else Assignment.FORBIDDEN } }
            assertEquals(best, got, 1e-6)
        }
    }

    @Test
    fun balancedAssignmentGivesEachGroupItsQuota() {
        // 12 items into 3 groups of 4 each: duplicate each group's column 4 times.
        val random = Random(2)
        val items = 12
        val groupCost = Array(items) { DoubleArray(3) { random.nextDouble() } }
        val cost = DoubleArray(items * items) { groupCost[it / items][(it % items) / 4] }
        val groups = Assignment.solve(cost, items, items).map { it / 4 }
        assertEquals(listOf(4, 4, 4), (0 until 3).map { g -> groups.count { it == g } })
    }
}
