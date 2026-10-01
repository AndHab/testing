package com.andhab.cubelens.core.vision

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Samples the nine stickers of a face from an image.
 *
 * The caller passes the square the user was asked to fit the face into (the on-screen guide). Real
 * scans are never perfectly aligned: the face is a little smaller or larger than the guide, shifted,
 * slightly rotated or in perspective. So instead of blindly averaging fixed cells, the sampler:
 *
 *  1. Reads a coarse lattice (16 points per cell, plus margin around the guide) of a "sticker-ness"
 *     feature, `2 * max(r, g, b) - min(r, g, b)`, which is low only for the dark, unsaturated
 *     plastic between stickers (a shaded saturated sticker such as dark blue still scores high), and
 *     splits it into sticker / plastic with Otsu's threshold.
 *  2. Scores a candidate sticker position as the sticker fraction of a half-cell window there minus
 *     the sticker fraction of a thin square ring at the distance of the gaps between stickers. A
 *     real sticker is a bright patch surrounded by dark plastic, so this is high only on stickers,
 *     not on a bright background or across a gap. All window sums come from a summed-area table.
 *  3. Finds the global shift and scale of the 3x3 grid with the best total score, then re-centers each
 *     cell on its own sticker (within a quarter cell). Cells that still disagree with an affine fit
 *     through the confidently found stickers are searched again around the fit's prediction, which
 *     absorbs rotation and perspective.
 *  4. Reads a 9x9 grid of points over the central [SAMPLE_FRACTION] of each located sticker, drops the
 *     darkest quarter by L* (plastic, shadowed edges, dark logo print) and specular highlights
 *     (bright, washed-out outliers), and takes the per-channel median.
 *
 * Cost is independent of the image size: about 6k pixel reads, under a millisecond on the JVM.
 */
object GridSampler {

    /** Fraction of a cell (per axis) covered by the final color sampling window. */
    const val SAMPLE_FRACTION = 0.55

    private const val STEPS = 16 // lattice points per cell
    private const val MARGIN = 12 // lattice points outside the region on each side
    private const val N = 3 * STEPS + 2 * MARGIN
    private const val WINDOW = 8 // side of the sticker window, lattice points (half a cell)
    private const val RING_OUTER = 18 // outer side of the gap ring (+-0.56 cell)
    private const val RING_INNER = 14 // inner side of the gap ring (+-0.44 cell)
    private const val MEMO_PAD = 8 // memoized score positions outside the lattice on each side
    private const val GLOBAL_SHIFT = 6 // +- lattice steps searched for the global grid offset
    private val GLOBAL_SCALES = doubleArrayOf(0.88, 0.94, 1.0, 1.06, 1.12)
    private const val GLOBAL_PENALTY = 0.15 // score units at the edge of the global search range
    private const val LOCAL_SHIFT = 4 // +- lattice steps searched per cell
    private const val LOCAL_PENALTY = 0.1 // score units at the edge of the local search range
    private const val PLATEAU_TOLERANCE = 0.05 // scores within this of the best count as equally good
    private const val MIN_SCORE = 0.3 // below this a cell keeps the grid position
    private const val RETRY_DISTANCE = 1.5 // lattice steps off the affine prediction that trigger a retry
    private const val MIN_CONFIDENT_CELLS = 5 // needed for the affine fit
    private const val SAMPLES_PER_AXIS = 9
    private const val GLARE_LIGHTNESS = 12f // L* above the median that marks a highlight ...
    private const val GLARE_CHROMA_RATIO = 0.5f // ... if its chroma is also below this fraction of the median

    /**
     * Samples the 3x3 stickers inside [region] of [source].
     *
     * [rotationDegrees] (0, 90, 180 or 270) is the clockwise rotation that makes [source] upright as
     * the user sees it (CameraX `ImageInfo.rotationDegrees`). The returned nine samples are
     * row-major as the user sees the face on screen.
     */
    fun sample(source: PixelSource, region: GridRegion, rotationDegrees: Int = 0): List<StickerSample> {
        val view = UprightView(source, region, rotationDegrees)
        val centers = locateStickers(view)
        return List(9) { aggregate(view, centers[2 * it], centers[2 * it + 1]) }
    }

    /**
     * Where [sample] found the nine sticker centers, as (u, v) pairs in cell units of the upright
     * region (0..3 on each axis, u to the right, v down), row-major.
     */
    internal fun stickerCenters(source: PixelSource, region: GridRegion, rotationDegrees: Int = 0): DoubleArray =
        locateStickers(UprightView(source, region, rotationDegrees))

    /**
     * The region as the user sees it: (u, v) in cell units, u to the right and v down on screen,
     * (0, 0) at the region's top-left and (3, 3) at its bottom-right.
     */
    private class UprightView(private val source: PixelSource, region: GridRegion, rotationDegrees: Int) {
        private val left = region.left.toDouble()
        private val top = region.top.toDouble()
        private val size = region.size.toDouble()

        // Normalized buffer coordinates inside the region: xn = ax*un + bx*vn + cx, yn = ay*un + by*vn + cy.
        private val ax: Double
        private val bx: Double
        private val cx: Double
        private val ay: Double
        private val by: Double
        private val cy: Double

        init {
            require(source.width > 0 && source.height > 0) { "Empty image" }
            require(region.size > 0) { "Region must not be empty: $region" }
            val rotation = Math.floorMod(rotationDegrees, 360)
            require(rotation % 90 == 0) { "rotationDegrees must be a multiple of 90, got $rotationDegrees" }
            // The buffer must be turned clockwise by `rotation` to look upright, so an upright point maps
            // back to the buffer by the opposite (counter-clockwise) turn.
            when (rotation) {
                0 -> { ax = 1.0; bx = 0.0; cx = 0.0; ay = 0.0; by = 1.0; cy = 0.0 }
                90 -> { ax = 0.0; bx = 1.0; cx = 0.0; ay = -1.0; by = 0.0; cy = 1.0 }
                180 -> { ax = -1.0; bx = 0.0; cx = 1.0; ay = 0.0; by = -1.0; cy = 1.0 }
                else -> { ax = 0.0; bx = -1.0; cx = 1.0; ay = 1.0; by = 0.0; cy = 0.0 }
            }
        }

        private val maxX = source.width - 1
        private val maxY = source.height - 1

        fun argb(u: Double, v: Double): Int {
            val un = u / 3.0
            val vn = v / 3.0
            val x = left + size * (ax * un + bx * vn + cx)
            val y = top + size * (ay * un + by * vn + cy)
            return source.argb(floor(x).toInt().coerceIn(0, maxX), floor(y).toInt().coerceIn(0, maxY))
        }
    }

    /** Upright coordinate (cell units) of lattice index [i]. */
    private fun latticeToCell(i: Double): Double = (i - MARGIN + 0.5) / STEPS

    /** Lattice coordinate of the center of cell [index] (0..2) under a grid [scale] and [shift]. */
    private fun cellCenter(index: Int, scale: Double, shift: Double): Double =
        (1.5 + (index - 1) * scale) * STEPS + MARGIN - 0.5 + shift

    /** Sticker indicator of the lattice as a summed-area table, with window queries. */
    private class StickerMap(feature: IntArray, threshold: Int) {
        private val stride = N + 1
        private val sat = IntArray(stride * stride)

        init {
            for (j in 0 until N) {
                var rowSum = 0
                for (i in 0 until N) {
                    if (feature[j * N + i] > threshold) rowSum++
                    sat[(j + 1) * stride + i + 1] = sat[j * stride + i + 1] + rowSum
                }
            }
        }

        /** Sticker count and area of the [side] x [side] square starting at lattice ([x0], [y0]), clipped. */
        private fun square(x0: Int, y0: Int, side: Int, out: IntArray) {
            val l = x0.coerceIn(0, N)
            val t = y0.coerceIn(0, N)
            val r = (x0 + side).coerceIn(0, N)
            val b = (y0 + side).coerceIn(0, N)
            out[0] = sat[b * stride + r] - sat[t * stride + r] - sat[b * stride + l] + sat[t * stride + l]
            out[1] = (r - l) * (b - t)
        }

        private val window = IntArray(2)
        private val outer = IntArray(2)
        private val inner = IntArray(2)

        // All squares have even sides, so a center at lattice coordinate c snaps to the same point
        // (round(c - 0.5)) for each of them; scores are memoized per snapped point.
        private val memoStride = N + 2 * MEMO_PAD
        private val memo = DoubleArray(memoStride * memoStride) { Double.NaN }

        /** Sticker fraction of the window minus sticker fraction of the surrounding gap ring. */
        fun score(cx: Double, cy: Double): Double {
            val mx = (cx - 0.5).roundToInt()
            val my = (cy - 0.5).roundToInt()
            val px = mx + MEMO_PAD
            val py = my + MEMO_PAD
            val cached = px in 0 until memoStride && py in 0 until memoStride
            if (cached) {
                val m = memo[py * memoStride + px]
                if (!m.isNaN()) return m
            }
            square(mx - (WINDOW - 2) / 2, my - (WINDOW - 2) / 2, WINDOW, window)
            square(mx - (RING_OUTER - 2) / 2, my - (RING_OUTER - 2) / 2, RING_OUTER, outer)
            square(mx - (RING_INNER - 2) / 2, my - (RING_INNER - 2) / 2, RING_INNER, inner)
            val stickers = if (window[1] > 0) window[0].toDouble() / window[1] else 0.0
            val ringArea = outer[1] - inner[1]
            val ring = if (ringArea > 0) (outer[0] - inner[0]).toDouble() / ringArea else 1.0
            val score = stickers - ring
            if (cached) memo[py * memoStride + px] = score
            return score
        }
    }

    /** Returns the located sticker centers as (u, v) pairs in cell units, row-major. */
    private fun locateStickers(view: UprightView): DoubleArray {
        val feature = IntArray(N * N)
        for (j in 0 until N) {
            val v = latticeToCell(j.toDouble())
            for (i in 0 until N) {
                val c = view.argb(latticeToCell(i.toDouble()), v)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                feature[j * N + i] = 2 * max(r, max(g, b)) - min(r, min(g, b))
            }
        }
        val map = StickerMap(feature, otsuThreshold(feature))

        // Global grid fit: shift and scale about the region center, preferring the guide as placed.
        var bestScore = Double.NEGATIVE_INFINITY
        var bestScale = 1.0
        var bestDx = 0
        var bestDy = 0
        val maxScaleOffset = GLOBAL_SCALES.maxOf { abs(it - 1.0) }
        for (scale in GLOBAL_SCALES) {
            val scalePenalty = GLOBAL_PENALTY * ((scale - 1.0) / maxScaleOffset).let { it * it }
            for (dy in -GLOBAL_SHIFT..GLOBAL_SHIFT) {
                for (dx in -GLOBAL_SHIFT..GLOBAL_SHIFT) {
                    var total = 0.0
                    for (cell in 0 until 9) {
                        total += map.score(cellCenter(cell % 3, scale, dx.toDouble()), cellCenter(cell / 3, scale, dy.toDouble()))
                    }
                    val shiftPenalty = GLOBAL_PENALTY * (dx * dx + dy * dy).toDouble() / (2 * GLOBAL_SHIFT * GLOBAL_SHIFT)
                    val score = total - shiftPenalty - scalePenalty
                    if (score > bestScore) {
                        bestScore = score
                        bestScale = scale
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }

        // Per-cell refinement around the global grid.
        val found = DoubleArray(27) // x, y (lattice coordinates) and score per cell
        for (cell in 0 until 9) {
            refine(map, cellCenter(cell % 3, bestScale, bestDx.toDouble()), cellCenter(cell / 3, bestScale, bestDy.toDouble()), found, cell)
        }

        // Second chance for cells the shift-and-scale grid placed badly (rotation, perspective): search
        // again around where the confidently found stickers say they should be.
        val predicted = predictAffine(found)
        if (predicted != null) {
            val retry = DoubleArray(3)
            for (cell in 0 until 9) {
                val dx = predicted[2 * cell] - found[3 * cell]
                val dy = predicted[2 * cell + 1] - found[3 * cell + 1]
                if (dx * dx + dy * dy < RETRY_DISTANCE * RETRY_DISTANCE) continue
                refine(map, predicted[2 * cell], predicted[2 * cell + 1], retry, 0)
                if (retry[2] > found[3 * cell + 2] + PLATEAU_TOLERANCE) retry.copyInto(found, 3 * cell)
            }
        }

        val centers = DoubleArray(18)
        for (cell in 0 until 9) {
            centers[2 * cell] = latticeToCell(found[3 * cell])
            centers[2 * cell + 1] = latticeToCell(found[3 * cell + 1])
        }
        return centers
    }

    /**
     * Searches +-[LOCAL_SHIFT] lattice steps around ([baseX], [baseY]) and writes the center of the
     * best-scoring plateau and its score to `out[3 * slot]`, `out[3 * slot + 1]` and `out[3 * slot + 2]`.
     * If nothing scores at least [MIN_SCORE], the base position is kept.
     */
    private fun refine(map: StickerMap, baseX: Double, baseY: Double, out: DoubleArray, slot: Int) {
        val side = 2 * LOCAL_SHIFT + 1
        val scores = DoubleArray(side * side)
        var best = Double.NEGATIVE_INFINITY
        for (ey in -LOCAL_SHIFT..LOCAL_SHIFT) {
            for (ex in -LOCAL_SHIFT..LOCAL_SHIFT) {
                val penalty = LOCAL_PENALTY * (ex * ex + ey * ey).toDouble() / (2 * LOCAL_SHIFT * LOCAL_SHIFT)
                val s = map.score(baseX + ex, baseY + ey) - penalty
                scores[(ey + LOCAL_SHIFT) * side + ex + LOCAL_SHIFT] = s
                if (s > best) best = s
            }
        }
        var offX = 0.0
        var offY = 0.0
        if (best >= MIN_SCORE) {
            var count = 0
            var sumX = 0
            var sumY = 0
            for (ey in -LOCAL_SHIFT..LOCAL_SHIFT) {
                for (ex in -LOCAL_SHIFT..LOCAL_SHIFT) {
                    if (scores[(ey + LOCAL_SHIFT) * side + ex + LOCAL_SHIFT] >= best - PLATEAU_TOLERANCE) {
                        count++
                        sumX += ex
                        sumY += ey
                    }
                }
            }
            offX = sumX.toDouble() / count
            offY = sumY.toDouble() / count
        }
        out[3 * slot] = baseX + offX
        out[3 * slot + 1] = baseY + offY
        out[3 * slot + 2] = best
    }

    /**
     * Fits an affine map from grid position (column, row) to the found centers, weighting each cell
     * by how far its score exceeds [MIN_SCORE], and returns the predicted center of every cell as
     * (x, y) pairs, or null if too few cells were found confidently.
     */
    private fun predictAffine(found: DoubleArray): DoubleArray? {
        // Weighted normal equations for the basis [1, column - 1, row - 1].
        val m = DoubleArray(9)
        val bx = DoubleArray(3)
        val by = DoubleArray(3)
        val f = DoubleArray(3)
        var confident = 0
        for (cell in 0 until 9) {
            val w = found[3 * cell + 2] - MIN_SCORE
            if (w <= 0.0) continue
            confident++
            f[0] = 1.0
            f[1] = (cell % 3 - 1).toDouble()
            f[2] = (cell / 3 - 1).toDouble()
            for (a in 0 until 3) {
                for (b in 0 until 3) m[a * 3 + b] += w * f[a] * f[b]
                bx[a] += w * f[a] * found[3 * cell]
                by[a] += w * f[a] * found[3 * cell + 1]
            }
        }
        if (confident < MIN_CONFIDENT_CELLS) return null
        val ax = solve3(m, bx) ?: return null
        val ay = solve3(m, by) ?: return null
        return DoubleArray(18) { k ->
            val cell = k / 2
            val a = if (k % 2 == 0) ax else ay
            a[0] + a[1] * (cell % 3 - 1) + a[2] * (cell / 3 - 1)
        }
    }

    /** Solves the 3x3 system [m] x = [b] ([m] row-major) by Cramer's rule; null if (nearly) singular. */
    private fun solve3(m: DoubleArray, b: DoubleArray): DoubleArray? {
        fun det(c0: DoubleArray, c1: DoubleArray, c2: DoubleArray): Double =
            c0[0] * (c1[1] * c2[2] - c1[2] * c2[1]) -
                c1[0] * (c0[1] * c2[2] - c0[2] * c2[1]) +
                c2[0] * (c0[1] * c1[2] - c0[2] * c1[1])
        val col0 = doubleArrayOf(m[0], m[3], m[6])
        val col1 = doubleArrayOf(m[1], m[4], m[7])
        val col2 = doubleArrayOf(m[2], m[5], m[8])
        val d = det(col0, col1, col2)
        if (abs(d) < 1e-9) return null
        return doubleArrayOf(det(b, col1, col2) / d, det(col0, b, col2) / d, det(col0, col1, b) / d)
    }

    /** Otsu's threshold over the lattice points inside the region (features are 0..510). */
    private fun otsuThreshold(feature: IntArray): Int {
        val hist = IntArray(511)
        var total = 0
        for (j in MARGIN until MARGIN + 3 * STEPS) {
            for (i in MARGIN until MARGIN + 3 * STEPS) {
                hist[feature[j * N + i]]++
                total++
            }
        }
        var sumAll = 0.0
        for (t in hist.indices) sumAll += t.toDouble() * hist[t]
        var weightBelow = 0
        var sumBelow = 0.0
        var bestVariance = -1.0
        var best = 0
        for (t in hist.indices) {
            weightBelow += hist[t]
            if (weightBelow == 0) continue
            val weightAbove = total - weightBelow
            if (weightAbove == 0) break
            sumBelow += t.toDouble() * hist[t]
            val meanBelow = sumBelow / weightBelow
            val meanAbove = (sumAll - sumBelow) / weightAbove
            val between = weightBelow.toDouble() * weightAbove * (meanBelow - meanAbove) * (meanBelow - meanAbove)
            if (between > bestVariance) {
                bestVariance = between
                best = t
            }
        }
        return best
    }

    /** Robust color of the sticker centered at ([cu], [cv]) (cell units). */
    private fun aggregate(view: UprightView, cu: Double, cv: Double): StickerSample {
        val n = SAMPLES_PER_AXIS
        val count = n * n
        val red = IntArray(count)
        val green = IntArray(count)
        val blue = IntArray(count)
        val lightness = FloatArray(count)
        val chroma = FloatArray(count)
        for (k in 0 until count) {
            val u = cu + SAMPLE_FRACTION * ((k % n + 0.5) / n - 0.5)
            val v = cv + SAMPLE_FRACTION * ((k / n + 0.5) / n - 0.5)
            val c = view.argb(u, v)
            red[k] = (c shr 16) and 0xFF
            green[k] = (c shr 8) and 0xFF
            blue[k] = c and 0xFF
            val lab = ColorMath.srgbToLab(red[k], green[k], blue[k])
            lightness[k] = lab.l
            chroma[k] = lab.chroma
        }

        // Drop the darkest quarter: black plastic at the edges, shadows, dark print.
        val darkCut = lightness.sortedArray()[count / 4]
        val keep = BooleanArray(count) { lightness[it] >= darkCut }

        // Drop specular highlights: much brighter than the sticker body and washed out.
        val medianL = median(lightness, keep)
        val medianC = median(chroma, keep)
        val noGlare = BooleanArray(count) {
            keep[it] && !(lightness[it] > medianL + GLARE_LIGHTNESS && chroma[it] < GLARE_CHROMA_RATIO * medianC)
        }
        val use = if (noGlare.any { it }) noGlare else keep
        return StickerSample.of(median(red, use), median(green, use), median(blue, use))
    }

    private fun median(values: FloatArray, mask: BooleanArray): Float {
        val selected = values.filterIndexed { i, _ -> mask[i] }.toFloatArray()
        selected.sort()
        return selected[selected.size / 2]
    }

    private fun median(values: IntArray, mask: BooleanArray): Int {
        val selected = values.filterIndexed { i, _ -> mask[i] }.toIntArray()
        selected.sort()
        return selected[selected.size / 2]
    }
}
