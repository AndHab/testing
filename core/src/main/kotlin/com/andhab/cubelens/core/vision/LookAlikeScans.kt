package com.andhab.cubelens.core.vision

import kotlin.math.cbrt

/**
 * Two scans of one scanning session that look like the same face: sticker for sticker alike, with the
 * [second] scan turned by some quarter turns, once the two photos' exposure and white balance are
 * matched. Scanning a face twice (forgetting to turn the cube between two photos) is the commonest
 * mistake in a guided scan.
 *
 * Two different faces of a real cube can look alike too: on a 2x2 cube two faces often show the same
 * colors in the same pattern, and on a solved or nearly solved cube with pale colors one face of few
 * colors can look like another under a different white balance. A look-alike pair is evidence of a
 * duplicate only together with what the joint classification made of it (see [NxNScanResolver]).
 */
internal class LookAlike(
    /** The earlier of the two scans (index in scan order). */
    val first: Int,
    /** The later of the two scans: the one to retake if it is a duplicate. */
    val second: Int,
    /** `matching[p]`: the position within [second] of the sticker that matches position p of [first]. */
    val matching: IntArray,
    /** How much matched stickers differ on average (CIEDE2000, after matching the photos): the smaller, the more alike. */
    val difference: Double,
    /**
     * Whether the match is also distinctive: both scans show clearly different colors (they are not
     * of one or two similar colors) and match closely, so that two different faces would hardly look
     * alike this way under any light. Only worked out on request (see [LookAlikeScans.find]).
     */
    val distinctive: Boolean,
)

/** Finds [LookAlike] scans. */
internal object LookAlikeScans {

    /** Two photos of one face differ in exposure by at most this factor either way (auto exposure, how the face is held). */
    private const val MAX_EXPOSURE_RATIO = 2.5

    /**
     * Two photos of one face differ in white balance by at most this factor either way, per channel
     * relative to the exposure: two different mild casts, as in mixed light.
     */
    private const val MAX_WHITE_BALANCE_RATIO = 1.67

    /**
     * Matched stickers of a look-alike pair differ (CIEDE2000, after matching the photos) by at most
     * this much on average and [MAX_DIFFERENCE] each. Two photos of one face differ by sensor noise,
     * uneven light and glare: 0.6 to 4.5 on average on rendered photos; different faces with different
     * colors by far more.
     */
    private const val MEAN_DIFFERENCE = 5.0
    private const val MAX_DIFFERENCE = 15.0

    /** A [LookAlike.distinctive] match: at most this much on average and [DISTINCTIVE_MAX] each ... */
    private const val DISTINCTIVE_MEAN = 4.0
    private const val DISTINCTIVE_MAX = 8.0

    /** ... between scans each of whose most different stickers are at least this far apart (CIEDE2000). */
    private const val DISTINCTIVE_SPREAD = 25.0

    /**
     * The look-alike pairs among the six scans of [n] x [n] stickers whose samples (linear RGB, scan by
     * scan) are [linear]: for each pair of scans, every quarter turn of the later one under which they
     * match, the closest matches first. Whether a match is [LookAlike.distinctive] is worked out only
     * if [distinctive] is set (it compares every two stickers of a scan, which is cheap only for small
     * faces).
     */
    fun find(n: Int, linear: Array<DoubleArray>, distinctive: Boolean): List<LookAlike> {
        val perFace = n * n
        val labs = Array(linear.size) { ColorMath.linearToLab(linear[it][0], linear[it][1], linear[it][2]) }
        val spread = DoubleArray(JointClustering.SCANS) { k -> if (distinctive) spreadOf(labs, k * perFace, perFace) else 0.0 }
        val turns = quarterTurns(n)
        val gain = DoubleArray(3)
        val result = ArrayList<LookAlike>()
        for (a in 0 until JointClustering.SCANS) {
            for (b in a + 1 until JointClustering.SCANS) {
                for (matching in turns) {
                    if (!matchPhotos(linear, a * perFace, b * perFace, matching, gain)) continue
                    var sum = 0.0
                    var worst = 0.0
                    for (p in 0 until perFace) {
                        val other = linear[b * perFace + matching[p]]
                        val d = ColorMath.deltaE(labs[a * perFace + p], ColorMath.linearToLab(other[0] * gain[0], other[1] * gain[1], other[2] * gain[2])).toDouble()
                        sum += d
                        if (d > worst) worst = d
                        if (worst > MAX_DIFFERENCE) break
                    }
                    if (worst > MAX_DIFFERENCE || sum / perFace > MEAN_DIFFERENCE) continue
                    val tight = sum / perFace <= DISTINCTIVE_MEAN && worst <= DISTINCTIVE_MAX
                    val varied = minOf(spread[a], spread[b]) >= DISTINCTIVE_SPREAD
                    result += LookAlike(a, b, matching, sum / perFace, distinctive && tight && varied)
                }
            }
        }
        result.sortBy { it.difference }
        return result
    }

    /**
     * The per-channel gains (into [gain]) that match the photo of the scan starting at [b] (stickers
     * taken in the order [matching]) to the one starting at [a], as the ratio of their channel sums
     * (dominated by the bright stickers, whose ratios are reliable); false if the gains are not ones
     * that two photos of one face could differ by.
     */
    private fun matchPhotos(linear: Array<DoubleArray>, a: Int, b: Int, matching: IntArray, gain: DoubleArray): Boolean {
        for (ch in 0 until 3) {
            var sumA = 0.0
            var sumB = 0.0
            for (p in matching.indices) {
                sumA += linear[a + p][ch]
                sumB += linear[b + matching[p]][ch]
            }
            if (!(sumA > 0.0 && sumB > 0.0)) return false
            gain[ch] = sumA / sumB
        }
        val exposure = cbrt(gain[0] * gain[1] * gain[2])
        if (exposure !in 1.0 / MAX_EXPOSURE_RATIO..MAX_EXPOSURE_RATIO) return false
        return gain.all { it / exposure in 1.0 / MAX_WHITE_BALANCE_RATIO..MAX_WHITE_BALANCE_RATIO }
    }

    /** The largest difference (CIEDE2000) between two of the [count] stickers starting at [start]. */
    private fun spreadOf(labs: Array<Lab>, start: Int, count: Int): Double {
        var most = 0.0
        for (i in start until start + count) {
            for (j in i + 1 until start + count) most = maxOf(most, ColorMath.deltaE(labs[i], labs[j]).toDouble())
        }
        return most
    }

    /**
     * For each of the four quarter turns (0 to 3 clockwise) of an [n] x [n] grid, the position each
     * position comes from: `turns[r][p]` is the position of the sticker that a grid turned clockwise
     * by r quarter turns shows at p (row-major).
     */
    private fun quarterTurns(n: Int): List<IntArray> {
        val once = IntArray(n * n) { p -> (n - 1 - p % n) * n + p / n }
        val turns = ArrayList<IntArray>(4)
        var current = IntArray(n * n) { it }
        repeat(4) {
            turns += current
            val previous = current
            current = IntArray(n * n) { p -> previous[once[p]] }
        }
        return turns
    }
}
