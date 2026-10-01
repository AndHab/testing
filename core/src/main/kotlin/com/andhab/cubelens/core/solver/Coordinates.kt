package com.andhab.cubelens.core.solver

/**
 * Integer coordinates of the two-phase algorithm and their inverses.
 *
 * Every coordinate is 0 for the solved cube. They work directly on the `cp/co/ep/eo` arrays of
 * [com.andhab.cubelens.core.cube.CubieCube] (Kociemba piece order), so the search never needs to
 * allocate cubie objects.
 *
 * Phase 1 (reduce to the subgroup H = <U, D, R2, L2, F2, B2>):
 *  - [twist]: orientation of corners 0..6 (the 8th follows), 0 until [N_TWIST].
 *  - [flip]: orientation of edges 0..10 (the 12th follows), 0 until [N_FLIP].
 *  - [sliceSorted]: which positions the four UD-slice edges (FR, FL, BL, BR) occupy *and* their
 *    order, 0 until [N_SLICE_SORTED]. `sliceSorted / 24` is the plain slice coordinate
 *    (0 until [N_SLICE], 0 exactly when all four sit in the slice) and `sliceSorted % 24` their
 *    order, which becomes the phase 2 [slicePerm] once the cube is in H.
 *
 * Phase 2 (solve within H):
 *  - [cornerPerm]: permutation of the 8 corners, 0 until [N_PERM8].
 *  - [udEdgePerm]: permutation of the 8 U/D-layer edges (positions 0..7), 0 until [N_PERM8].
 *  - [slicePerm]: permutation of the 4 slice edges within the slice, 0 until [N_SLICE_PERM].
 */
internal object Coordinates {
    const val N_TWIST = 2187 // 3^7
    const val N_FLIP = 2048 // 2^11
    const val N_SLICE = 495 // C(12, 4)
    const val N_SLICE_PERM = 24 // 4!
    const val N_SLICE_SORTED = N_SLICE * N_SLICE_PERM // 11880
    const val N_PERM8 = 40320 // 8!

    /** First slice edge (FR) in Kociemba edge order; FR, FL, BL, BR are 8..11. */
    private const val FIRST_SLICE_EDGE = 8

    /** Binomial coefficients C(n, k) for n < 12, k <= 4. */
    private val binomial: Array<IntArray> = Array(12) { n -> IntArray(5) { k -> choose(n, k) } }

    private fun choose(n: Int, k: Int): Int {
        if (k < 0 || k > n) return 0
        var r = 1L
        for (i in 0 until k) r = r * (n - i) / (i + 1)
        return r.toInt()
    }

    // ---- Phase 1 -------------------------------------------------------------------------------

    fun twist(co: IntArray): Int {
        var t = 0
        for (i in 0 until 7) t = t * 3 + co[i]
        return t
    }

    /** Writes the corner orientations for twist [t] into [co] (8 entries). */
    fun setTwist(co: IntArray, t: Int) {
        var rest = t
        var sum = 0
        for (i in 6 downTo 0) {
            co[i] = rest % 3
            sum += co[i]
            rest /= 3
        }
        co[7] = (3 - sum % 3) % 3
    }

    fun flip(eo: IntArray): Int {
        var f = 0
        for (i in 0 until 11) f = f * 2 + eo[i]
        return f
    }

    /** Writes the edge orientations for flip [f] into [eo] (12 entries). */
    fun setFlip(eo: IntArray, f: Int) {
        var rest = f
        var sum = 0
        for (i in 10 downTo 0) {
            eo[i] = rest and 1
            sum += eo[i]
            rest = rest shr 1
        }
        eo[11] = sum and 1
    }

    /**
     * Combined slice coordinate: `24 * positions + order`, where `positions` ranks the set of
     * positions holding slice edges (combinatorial number system, 0 when they are in the slice) and
     * `order` ranks the sequence of slice edges met when reading positions in increasing order.
     */
    fun sliceSorted(ep: IntArray): Int {
        var positions = 0
        var found = 0
        val order = IntArray(4)
        for (j in 11 downTo 0) {
            val e = ep[j]
            if (e >= FIRST_SLICE_EDGE) {
                found++
                positions += binomial[11 - j][found]
                order[4 - found] = e - FIRST_SLICE_EDGE
            }
        }
        return positions * N_SLICE_PERM + rankPermutation(order, 0, 4)
    }

    /**
     * Writes an edge permutation with slice coordinate [s] into [ep]. Slice edges are placed as the
     * coordinate demands; the other eight edges fill the remaining positions in increasing order.
     */
    fun setSliceSorted(ep: IntArray, s: Int) {
        var positions = s / N_SLICE_PERM
        val order = IntArray(4)
        unrankPermutation(s % N_SLICE_PERM, order, 0, 4)
        val occupied = BooleanArray(12)
        // Decode the combinatorial number system: values v = 11 - j, largest first.
        for (k in 4 downTo 1) {
            var v = k - 1
            while (v + 1 < 12 && binomial[v + 1][k] <= positions) v++
            positions -= binomial[v][k]
            occupied[11 - v] = true
        }
        var nextSlice = 0
        var nextOther = 0
        for (j in 0 until 12) {
            if (occupied[j]) {
                ep[j] = FIRST_SLICE_EDGE + order[nextSlice++]
            } else {
                ep[j] = nextOther++
            }
        }
    }

    // ---- Phase 2 -------------------------------------------------------------------------------

    fun cornerPerm(cp: IntArray): Int = rankPermutation(cp, 0, 8)

    fun setCornerPerm(cp: IntArray, c: Int) = unrankPermutation(c, cp, 0, 8)

    /** Only meaningful when edges 0..7 sit in positions 0..7 (i.e. the cube is in H). */
    fun udEdgePerm(ep: IntArray): Int = rankPermutation(ep, 0, 8)

    /** Writes U/D edges for [c] into positions 0..7 and the slice edges, solved, into 8..11. */
    fun setUdEdgePerm(ep: IntArray, c: Int) {
        unrankPermutation(c, ep, 0, 8)
        for (j in 8 until 12) ep[j] = j
    }

    /** Only meaningful when the slice edges sit in positions 8..11. */
    fun slicePerm(ep: IntArray): Int {
        val order = IntArray(4) { ep[8 + it] - FIRST_SLICE_EDGE }
        return rankPermutation(order, 0, 4)
    }

    /** Writes slice edges for [c] into positions 8..11 and solved U/D edges into 0..7. */
    fun setSlicePerm(ep: IntArray, c: Int) {
        val order = IntArray(4)
        unrankPermutation(c, order, 0, 4)
        for (j in 0 until 8) ep[j] = j
        for (j in 0 until 4) ep[8 + j] = FIRST_SLICE_EDGE + order[j]
    }

    // ---- Permutation ranking -------------------------------------------------------------------

    /**
     * Lehmer rank of `p[from until from + n]`, whose values must be a permutation of
     * `base until base + n` for some base (only their relative order matters). Identity ranks 0.
     */
    fun rankPermutation(p: IntArray, from: Int, n: Int): Int {
        var r = 0
        for (i in 0 until n) {
            val v = p[from + i]
            var smaller = 0
            for (j in i + 1 until n) if (p[from + j] < v) smaller++
            r = r * (n - i) + smaller
        }
        return r
    }

    /** Inverse of [rankPermutation]: writes the permutation of `0 until n` with rank [rank]. */
    fun unrankPermutation(rank: Int, out: IntArray, from: Int, n: Int) {
        val digits = IntArray(n)
        var rest = rank
        for (i in n - 1 downTo 0) {
            digits[i] = rest % (n - i)
            rest /= (n - i)
        }
        val used = BooleanArray(n)
        for (i in 0 until n) {
            var skip = digits[i]
            var v = 0
            while (true) {
                if (!used[v]) {
                    if (skip == 0) break
                    skip--
                }
                v++
            }
            used[v] = true
            out[from + i] = v
        }
    }
}
