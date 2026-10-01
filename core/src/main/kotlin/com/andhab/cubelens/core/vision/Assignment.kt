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
