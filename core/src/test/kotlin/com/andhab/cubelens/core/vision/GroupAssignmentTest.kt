package com.andhab.cubelens.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GroupAssignmentTest {

    private fun total(cost: DoubleArray, groups: Int, items: IntArray, assigned: IntArray) = items.sumOf { cost[it * groups + assigned[it]] }

    @Test
    fun matchesTheHungarianMethodOnBalancedProblems() {
        // Items into groups of exactly `size` each, the Hungarian method on repeated columns as reference.
        val random = Random(1)
        repeat(200) { k ->
            val groups = random.nextInt(1, 7)
            val size = random.nextInt(1, 9)
            val count = groups * size
            val cost = DoubleArray(count * groups) { if (k % 4 == 0) random.nextInt(5).toDouble() else random.nextDouble(0.0, 50.0) }
            val square = DoubleArray(count * count) { cost[(it / count) * groups + (it % count) / size] }
            val hungarian = Assignment.solve(square, count, count).map { it / size }.toIntArray()
            val items = IntArray(count) { it }
            val assigned = IntArray(count) { -1 }
            assertTrue(GroupAssignment.solve(cost, groups, items, IntArray(groups) { size }, IntArray(groups) { size }, assigned))
            assertEquals(List(groups) { size }, (0 until groups).map { g -> assigned.count { it == g } })
            assertEquals(total(cost, groups, items, hungarian), total(cost, groups, items, assigned), 1e-9)
        }
    }

    /** The optimum over every assignment of [count] items to [groups] groups within the bounds, by brute force. */
    private fun bruteForce(cost: DoubleArray, groups: Int, count: Int, lower: IntArray, upper: IntArray): Double {
        var best = Double.POSITIVE_INFINITY
        val assigned = IntArray(count)
        fun go(i: Int, sizes: IntArray, sum: Double) {
            if (sum >= best) return
            if (i == count) {
                if ((0 until groups).all { sizes[it] >= lower[it] }) best = sum
                return
            }
            for (g in 0 until groups) {
                if (sizes[g] >= upper[g]) continue
                sizes[g]++
                assigned[i] = g
                go(i + 1, sizes, sum + cost[i * groups + g])
                sizes[g]--
            }
        }
        go(0, IntArray(groups), 0.0)
        return best
    }

    @Test
    fun respectsLowerAndUpperBounds() {
        val random = Random(2)
        repeat(150) {
            val groups = random.nextInt(2, 5)
            val count = random.nextInt(groups, 9)
            val lower = IntArray(groups) { random.nextInt(0, 2) }
            val upper = IntArray(groups) { lower[it] + random.nextInt(0, 4) }
            if (lower.sum() > count || upper.sum() < count) return@repeat
            val cost = DoubleArray(count * groups) { random.nextDouble(0.0, 20.0) }
            val items = IntArray(count) { it }
            val assigned = IntArray(count) { -1 }
            GroupAssignment.solve(cost, groups, items, lower, upper, assigned)
            for (g in 0 until groups) assertTrue(assigned.count { it == g } in lower[g]..upper[g])
            assertEquals(bruteForce(cost, groups, count, lower, upper), total(cost, groups, items, assigned), 1e-9)
        }
    }

    @Test
    fun warmStartsLeaveOtherIndicesAloneAndReportChanges() {
        val random = Random(3)
        val groups = 6
        val count = 60
        val cost = DoubleArray(count * groups) { random.nextDouble(0.0, 30.0) }
        // Items 0..5 are pinned (not passed); the other 54 get 9 per group.
        val items = IntArray(count - 6) { it + 6 }
        val assigned = IntArray(count) { if (it < 6) it else -1 }
        val quota = IntArray(groups) { 9 }
        assertTrue(GroupAssignment.solve(cost, groups, items, quota, quota, assigned))
        assertEquals((0 until 6).toList(), (0 until 6).map { assigned[it] })
        val optimum = total(cost, groups, items, assigned)
        // Solving again from the optimum changes nothing.
        assertFalse(GroupAssignment.solve(cost, groups, items, quota, quota, assigned))
        // A perturbed start is fixed back to an optimum.
        val shuffled = assigned.copyOf()
        for (i in items.indices.shuffled(random).take(20).windowed(2, 2)) {
            val a = items[i[0]]
            val b = items[i[1]]
            shuffled[a] = assigned[b].also { shuffled[b] = assigned[a] }
        }
        GroupAssignment.solve(cost, groups, items, quota, quota, shuffled)
        assertEquals(optimum, total(cost, groups, items, shuffled), 1e-9)
        // Non-finite costs count as prohibitive but don't break the solver.
        val forbidding = cost.copyOf().also { it[6 * groups] = Double.NaN; it[7 * groups + 1] = Double.POSITIVE_INFINITY }
        GroupAssignment.solve(forbidding, groups, items, quota, quota, IntArray(count) { -1 })
    }

    @Test
    fun isFastForBigCubes() {
        // 7x7: 294 stickers into six colors of 49.
        val random = Random(4)
        val count = 294
        val costs = List(20) { DoubleArray(count * 6) { random.nextDouble(0.0, 60.0) } }
        val items = IntArray(count) { it }
        val quota = IntArray(6) { 49 }
        costs.forEach { GroupAssignment.solve(it, 6, items, quota, quota, IntArray(count) { -1 }) } // warm-up
        val start = System.nanoTime()
        costs.forEach { GroupAssignment.solve(it, 6, items, quota, quota, IntArray(count) { -1 }) }
        val ms = (System.nanoTime() - start) / 1e6 / costs.size
        println("GroupAssignment, 294 items into 6 groups from scratch: %.2f ms".format(ms))
        assertTrue("$ms ms", ms < 50.0)
    }

    @Test
    fun rejectsInfeasibleBounds() {
        val cost = DoubleArray(12)
        assertThrows(IllegalArgumentException::class.java) { GroupAssignment.solve(cost, 3, IntArray(4) { it }, IntArray(3) { 2 }, IntArray(3) { 2 }, IntArray(4)) }
        assertThrows(IllegalArgumentException::class.java) { GroupAssignment.solve(cost, 3, IntArray(4) { it }, IntArray(3) { 0 }, IntArray(3) { 1 }, IntArray(4)) }
        assertThrows(IllegalArgumentException::class.java) { GroupAssignment.solve(cost, 3, IntArray(4) { it }, IntArray(3) { 2 }, IntArray(3) { 1 }, IntArray(4)) }
    }
}
