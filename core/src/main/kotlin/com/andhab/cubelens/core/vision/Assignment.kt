package com.andhab.cubelens.core.vision

/**
 * Optimal assignment (Hungarian algorithm, O(n^2 m) with potentials).
 */
internal object Assignment {

    /**
     * Assigns every row of the [rows] x [cols] cost matrix (rows <= cols) to a distinct column so that
     * the total cost is minimal. [cost] is row-major (`cost[r * cols + c]`); non-finite entries are
     * treated as a prohibitively large cost. Returns the column chosen for each row.
     */
    fun solve(cost: DoubleArray, rows: Int, cols: Int): IntArray {
        require(rows <= cols) { "Need rows <= cols, got $rows x $cols" }
        require(cost.size == rows * cols) { "Cost matrix must have ${rows * cols} entries, got ${cost.size}" }
        if (rows == 0) return IntArray(0)
        val c = if (cost.all { it.isFinite() }) cost else DoubleArray(cost.size) { if (cost[it].isFinite()) cost[it] else FORBIDDEN }
        return solveFinite(c, rows, cols)
    }

    /** Cost substituted for non-finite entries. */
    const val FORBIDDEN = 1e9

    private fun solveFinite(cost: DoubleArray, rows: Int, cols: Int): IntArray {
        // 1-based arrays as in the classic formulation; index 0 is the virtual start column.
        val u = DoubleArray(rows + 1)
        val v = DoubleArray(cols + 1)
        val p = IntArray(cols + 1) // p[col] = row matched to col
        val way = IntArray(cols + 1)
        val minV = DoubleArray(cols + 1)
        val used = BooleanArray(cols + 1)
        for (i in 1..rows) {
            p[0] = i
            var j0 = 0
            minV.fill(Double.POSITIVE_INFINITY)
            used.fill(false)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                val rowBase = (i0 - 1) * cols
                for (j in 1..cols) {
                    if (used[j]) continue
                    val cur = cost[rowBase + j - 1] - u[i0] - v[j]
                    if (cur < minV[j]) {
                        minV[j] = cur
                        way[j] = j0
                    }
                    if (minV[j] < delta) {
                        delta = minV[j]
                        j1 = j
                    }
                }
                for (j in 0..cols) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minV[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val result = IntArray(rows)
        for (j in 1..cols) if (p[j] != 0) result[p[j] - 1] = j - 1
        return result
    }
}

/**
 * Minimum-cost assignment of items to a few groups whose sizes are bounded: a transportation problem,
 * e.g. the stickers of an N×N cube to six colors with exactly N² stickers per color.
 *
 * [Assignment] solves this by repeating each group's column once per slot, which costs
 * O(items³): fine for the 48 stickers of a 3x3 cube, far too slow for the 288 of a 7x7. Here the
 * solver works on the groups instead. Starting from a feasible assignment (the caller's if it is
 * one, else a greedy one), it cancels negative cycles: moving one item from group a to b, one from b
 * to c and so on back to a keeps every group's size and changes the cost by the sum of the moves'
 * cost differences, so a cycle with a negative sum is an improvement. A group with room to grow, or
 * to shrink, can also end, or start, a chain of moves. An assignment without such an improving cycle
 * is optimal (the optimality condition of min-cost flow: the residual network of this flow problem
 * has exactly these cycles, with each step taking the item whose move is cheapest).
 *
 * Each round costs O(items × groups + groups³); a warm start from the previous assignment, as in
 * iterative clustering, typically needs a handful of rounds.
 */
internal object GroupAssignment {

    /** A cycle must improve the total cost by more than this to be applied (guards against rounding loops). */
    private const val EPSILON = 1e-9

    /**
     * Assigns each of [items] (indices into [assigned] and rows of [cost]) to one of [groups] groups
     * so that group g gets between `lower[g]` and `upper[g]` of them and the total cost is minimal.
     * `cost[item * groups + g]` is the cost of putting item in group g; non-finite entries count as
     * prohibitively expensive ([Assignment.FORBIDDEN]). [assigned] holds the start (used if it is a
     * feasible assignment of [items]) and receives the result; entries of other indices are left
     * alone. Returns whether the assignment of any item changed.
     */
    fun solve(cost: DoubleArray, groups: Int, items: IntArray, lower: IntArray, upper: IntArray, assigned: IntArray): Boolean {
        require(groups >= 1 && lower.size == groups && upper.size == groups) { "Need bounds for each of the $groups groups" }
        require((0 until groups).all { lower[it] in 0..upper[it] }) { "Bounds must satisfy 0 <= lower <= upper" }
        require(lower.sum() <= items.size && items.size <= upper.sum()) { "${items.size} items don't fit bounds ${lower.toList()}..${upper.toList()}" }
        val before = IntArray(items.size) { assigned[items[it]] }
        val count = IntArray(groups)
        var feasible = true
        for (i in items) {
            val g = assigned[i]
            if (g !in 0 until groups) {
                feasible = false
                break
            }
            count[g]++
        }
        if (feasible) feasible = (0 until groups).all { count[it] in lower[it]..upper[it] }
        val c = Costs(cost, groups)
        if (!feasible) greedy(c, groups, items, lower, upper, assigned, count)
        cancelCycles(c, groups, items, lower, upper, assigned, count)
        return items.indices.any { assigned[items[it]] != before[it] }
    }

    /** Read access to the cost matrix with non-finite entries replaced by [Assignment.FORBIDDEN]. */
    private class Costs(private val cost: DoubleArray, private val groups: Int) {
        operator fun get(item: Int, group: Int): Double {
            val v = cost[item * groups + group]
            return if (v.isFinite()) v else Assignment.FORBIDDEN
        }
    }

    /**
     * A feasible start: items in order of regret (how much more their second choice costs) take
     * their cheapest group with room left; then groups below their lower bound take the items that
     * are cheapest to move from groups above theirs.
     */
    private fun greedy(c: Costs, groups: Int, items: IntArray, lower: IntArray, upper: IntArray, assigned: IntArray, count: IntArray) {
        count.fill(0)
        val regret = DoubleArray(items.size) { r ->
            var best = Double.POSITIVE_INFINITY
            var second = Double.POSITIVE_INFINITY
            for (g in 0 until groups) {
                val v = c[items[r], g]
                if (v < best) {
                    second = best
                    best = v
                } else if (v < second) {
                    second = v
                }
            }
            if (second.isFinite()) second - best else 0.0
        }
        val order = items.indices.sortedByDescending { regret[it] }
        for (r in order) {
            val i = items[r]
            var choice = -1
            for (g in 0 until groups) {
                if (count[g] >= upper[g]) continue
                if (choice < 0 || c[i, g] < c[i, choice]) choice = g
            }
            assigned[i] = choice
            count[choice]++
        }
        while (true) {
            val short = (0 until groups).firstOrNull { count[it] < lower[it] } ?: break
            var bestItem = -1
            var bestDelta = Double.POSITIVE_INFINITY
            for (i in items) {
                val g = assigned[i]
                if (g == short || count[g] <= lower[g]) continue
                val delta = c[i, short] - c[i, g]
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestItem = i
                }
            }
            if (bestItem < 0) break // unreachable when the bounds admit the items
            count[assigned[bestItem]]--
            assigned[bestItem] = short
            count[short]++
        }
    }

    /** Applies improving cycles (see the class documentation) until there are none. */
    private fun cancelCycles(c: Costs, groups: Int, items: IntArray, lower: IntArray, upper: IntArray, assigned: IntArray, count: IntArray) {
        val slack = groups // the extra node through which groups grow or shrink
        val nodes = groups + 1
        val weight = DoubleArray(nodes * nodes)
        val mover = IntArray(groups * groups)
        val dist = DoubleArray(nodes)
        val pred = IntArray(nodes)
        val onCycle = BooleanArray(nodes)
        val cycle = IntArray(nodes)
        val maxRounds = 20 * items.size + 100
        repeat(maxRounds) {
            // The cheapest single move between every ordered pair of groups.
            weight.fill(Double.POSITIVE_INFINITY)
            mover.fill(-1)
            for (i in items) {
                val a = assigned[i]
                val own = c[i, a]
                for (b in 0 until groups) {
                    if (b == a) continue
                    val delta = c[i, b] - own
                    if (delta < weight[a * nodes + b]) {
                        weight[a * nodes + b] = delta
                        mover[a * groups + b] = i
                    }
                }
            }
            for (g in 0 until groups) {
                if (count[g] < upper[g]) weight[g * nodes + slack] = 0.0 // g may take one more
                if (count[g] > lower[g]) weight[slack * nodes + g] = 0.0 // g may give one up
            }

            // Bellman-Ford from a virtual source connected to every node; a relaxation in the last
            // pass means a negative cycle.
            dist.fill(0.0)
            pred.fill(-1)
            var updated = -1
            for (pass in 0 until nodes) {
                updated = -1
                for (u in 0 until nodes) {
                    for (v in 0 until nodes) {
                        val w = weight[u * nodes + v]
                        if (w == Double.POSITIVE_INFINITY) continue
                        if (dist[u] + w < dist[v] - EPSILON) {
                            dist[v] = dist[u] + w
                            pred[v] = u
                            updated = v
                        }
                    }
                }
                if (updated < 0) return // no negative cycle: optimal
            }
            // Walk back into the cycle, then collect it (edges pred[v] -> v).
            var x = updated
            repeat(nodes) { if (x >= 0) x = pred[x] }
            if (x < 0) return // cannot happen after a relaxation in the last pass; stop rather than loop
            onCycle.fill(false)
            var length = 0
            var v = x
            while (v >= 0 && !onCycle[v]) {
                onCycle[v] = true
                cycle[length++] = v
                v = pred[v]
            }
            if (v != x) return // not a closed cycle (cannot happen, see above)
            var total = 0.0
            for (k in 0 until length) total += weight[pred[cycle[k]] * nodes + cycle[k]]
            if (!(total < -EPSILON)) return
            // Each group starts at most one edge of the cycle, so the moved items are distinct.
            for (k in 0 until length) {
                val to = cycle[k]
                val from = pred[to]
                if (from == slack || to == slack) continue
                val item = mover[from * groups + to]
                assigned[item] = to
                count[from]--
                count[to]++
            }
        }
    }
}
