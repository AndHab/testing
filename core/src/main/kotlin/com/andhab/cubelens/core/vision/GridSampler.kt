package com.andhab.cubelens.core.vision

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 *     splits it into sticker / plastic with Otsu's threshold. If the "plastic" class is not dark
 *     (stickerless cubes, whose colored tiles touch, and white-bodied cubes), there are no dark gaps
 *     to lock onto, and step 2a replaces steps 2 and 3.
 *  2a. Faces without dark gaps: finds the global shift and scale of the 3x3 grid at which the nine
 *     sampling windows are most uniform in color (least sRGB variance, from summed-area tables), so
 *     that no window reaches into the white plastic or a neighbouring tile. Every grid within a
 *     tolerance of the best counts as equally good, and of those the one closest to the guide as
 *     placed is used: the guide itself whenever it is good enough (always on a face of one color),
 *     otherwise the grid that moves it least.
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
 * Scratch buffers (about 300 KB) are kept per thread and reused, so a camera analyzer calling this
 * for every frame allocates little more than the returned samples.
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
    private const val LOCAL_SIDE = 2 * LOCAL_SHIFT + 1
    private const val FEATURE_LEVELS = 511 // 2 * max - min ranges over 0..510
    private const val SAMPLES_PER_AXIS = 9
    private const val SAMPLE_COUNT = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS
    private const val GLARE_LIGHTNESS = 12f // L* above the median that marks a highlight ...
    private const val GLARE_CHROMA_RATIO = 0.5f // ... if its chroma is also below this fraction of the median
    private const val GAP_DARKNESS = 0.5 // gaps are dark: their median brightness is below this fraction of the stickers'
    private const val UNIFORM_WINDOW = 10 // side of the uniformity window, lattice points (0.625 cell, more than SAMPLE_FRACTION)
    private const val UNIFORM_SHIFT = 6 // +- lattice steps searched for the grid offset of a face without dark gaps
    private val UNIFORM_SCALES = doubleArrayOf(0.94, 1.0, 1.06)
    private val UNIFORM_GRIDS = UNIFORM_SCALES.size * (2 * UNIFORM_SHIFT + 1) * (2 * UNIFORM_SHIFT + 1)
    private const val UNIFORM_TRIMMED = 2 // cells left out of a grid's score (e.g. specular highlights)
    private const val UNIFORM_TOLERANCE = 0.1 // a grid is as good as the best within this fraction of the cells' own variance ...
    private const val UNIFORM_FLOOR = 7 * 3 * 4.0 // ... plus the variance of sensor noise of 2 sRGB levels in 7 windows x 3 channels

    /**
     * Samples the 3x3 stickers inside [region] of [source].
     *
     * [rotationDegrees] (0, 90, 180 or 270) is the clockwise rotation that makes [source] upright as
     * the user sees it (CameraX `ImageInfo.rotationDegrees`). The returned nine samples are
     * row-major as the user sees the face on screen.
     */
    fun sample(source: PixelSource, region: GridRegion, rotationDegrees: Int = 0): List<StickerSample> {
        val view = UprightView(source, region, rotationDegrees)
        val work = workspaces.get()
        val centers = locateStickers(view, work)
        return List(9) { aggregate(view, centers[2 * it], centers[2 * it + 1], work) }
    }

    /**
     * Where [sample] found the nine sticker centers, as (u, v) pairs in cell units of the upright
     * region (0..3 on each axis, u to the right, v down), row-major.
     */
    internal fun stickerCenters(source: PixelSource, region: GridRegion, rotationDegrees: Int = 0): DoubleArray =
        locateStickers(UprightView(source, region, rotationDegrees), workspaces.get()).copyOf()

    /**
     * Scratch buffers for one [sample] call. Each thread gets its own, reused for every call on that
     * thread (a camera analyzer runs on one thread, so this is a single set of buffers in practice).
     * Nothing in here outlives a call: every buffer is fully rewritten before it is read.
     */
    private class Workspace {
        val feature = IntArray(N * N)
        val brightness = IntArray(N * N)
        val below = IntArray(9 * STEPS * STEPS)
        val above = IntArray(9 * STEPS * STEPS)
        val histogram = IntArray(FEATURE_LEVELS)
        val rgb = IntArray(N * N)
        val uniformity = UniformityMap()
        val uniformVariance = DoubleArray(9 * UNIFORM_GRIDS)
        val uniformCellBest = DoubleArray(9)
        val uniformExcess = DoubleArray(9)
        val uniformScores = DoubleArray(UNIFORM_GRIDS)
        val map = StickerMap()
        val scores = DoubleArray(LOCAL_SIDE * LOCAL_SIDE)
        val found = DoubleArray(27)
        val retry = DoubleArray(3)
        val predicted = DoubleArray(18)
        val centers = DoubleArray(18)
        val normal = DoubleArray(9)
        val rhsX = DoubleArray(3)
        val rhsY = DoubleArray(3)
        val coefX = DoubleArray(3)
        val coefY = DoubleArray(3)
        val red = IntArray(SAMPLE_COUNT)
        val green = IntArray(SAMPLE_COUNT)
        val blue = IntArray(SAMPLE_COUNT)
        val lightness = FloatArray(SAMPLE_COUNT)
        val chroma = FloatArray(SAMPLE_COUNT)
        val keep = BooleanArray(SAMPLE_COUNT)
        val noGlare = BooleanArray(SAMPLE_COUNT)
        val intScratch = IntArray(SAMPLE_COUNT)
        val floatScratch = FloatArray(SAMPLE_COUNT)
        val lab = FloatArray(3)
    }

    private val workspaces = object : ThreadLocal<Workspace>() {
        override fun initialValue() = Workspace()
    }

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
    private class StickerMap {
        private val stride = N + 1
        private val sat = IntArray(stride * stride) // row 0 and column 0 stay zero

        /** Rebuilds the map for a new lattice: points whose [feature] exceeds [threshold] are sticker. */
        fun reset(feature: IntArray, threshold: Int) {
            for (j in 0 until N) {
                var rowSum = 0
                for (i in 0 until N) {
                    if (feature[j * N + i] > threshold) rowSum++
                    sat[(j + 1) * stride + i + 1] = sat[j * stride + i + 1] + rowSum
                }
            }
            memo.fill(Double.NaN)
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
        private val memo = DoubleArray(memoStride * memoStride)

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

    /**
     * Returns the located sticker centers as (u, v) pairs in cell units, row-major. The result is
     * [Workspace.centers], valid until the next call on this thread.
     */
    private fun locateStickers(view: UprightView, work: Workspace): DoubleArray {
        val feature = work.feature
        val brightness = work.brightness
        val rgb = work.rgb
        for (j in 0 until N) {
            val v = latticeToCell(j.toDouble())
            for (i in 0 until N) {
                val c = view.argb(latticeToCell(i.toDouble()), v)
                rgb[j * N + i] = c
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val brightest = max(r, max(g, b))
                feature[j * N + i] = 2 * brightest - min(r, min(g, b))
                brightness[j * N + i] = brightest
            }
        }
        val threshold = otsuThreshold(feature, work.histogram)
        if (!hasDarkGaps(feature, brightness, threshold, work)) return locateByUniformity(work)
        val map = work.map
        map.reset(feature, threshold)

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
        val found = work.found // x, y (lattice coordinates) and score per cell
        for (cell in 0 until 9) {
            refine(map, cellCenter(cell % 3, bestScale, bestDx.toDouble()), cellCenter(cell / 3, bestScale, bestDy.toDouble()), found, cell, work.scores)
        }

        // Second chance for cells the shift-and-scale grid placed badly (rotation, perspective): search
        // again around where the confidently found stickers say they should be.
        if (predictAffine(found, work)) {
            val predicted = work.predicted
            val retry = work.retry
            for (cell in 0 until 9) {
                val dx = predicted[2 * cell] - found[3 * cell]
                val dy = predicted[2 * cell + 1] - found[3 * cell + 1]
                if (dx * dx + dy * dy < RETRY_DISTANCE * RETRY_DISTANCE) continue
                refine(map, predicted[2 * cell], predicted[2 * cell + 1], retry, 0, work.scores)
                if (retry[2] > found[3 * cell + 2] + PLATEAU_TOLERANCE) retry.copyInto(found, 3 * cell)
            }
        }

        val centers = work.centers
        for (cell in 0 until 9) {
            centers[2 * cell] = latticeToCell(found[3 * cell])
            centers[2 * cell + 1] = latticeToCell(found[3 * cell + 1])
        }
        return centers
    }

    /**
     * Whether the face has dark plastic between its stickers, as stickered cubes with a black body do:
     * the typical brightness (brightest channel) of the lattice points below the Otsu [threshold]
     * inside the region is well below that of the points above it. Without dark gaps (stickerless
     * cubes, whose colored tiles touch, and white-bodied cubes) the two classes are just two groups
     * of sticker colors, and fitting the grid to them would lock onto color blobs instead of stickers.
     */
    private fun hasDarkGaps(feature: IntArray, brightness: IntArray, threshold: Int, work: Workspace): Boolean {
        val below = work.below
        val above = work.above
        var nBelow = 0
        var nAbove = 0
        for (j in MARGIN until MARGIN + 3 * STEPS) {
            for (i in MARGIN until MARGIN + 3 * STEPS) {
                val k = j * N + i
                if (feature[k] > threshold) above[nAbove++] = brightness[k] else below[nBelow++] = brightness[k]
            }
        }
        if (nBelow == 0 || nAbove == 0) return true
        return select(below, nBelow, nBelow / 2) < GAP_DARKNESS * select(above, nAbove, nAbove / 2)
    }

    /**
     * Step 2a, for faces without dark gaps: the shift and scale of the 3x3 grid that keeps the
     * windows of [UNIFORM_WINDOW] points inside uniformly colored areas (see the class documentation),
     * as sticker centers in [Workspace.centers].
     *
     * Each grid is scored by how much more varied each cell's window is there than at that cell's
     * best grid, summed over the cells except the [UNIFORM_TRIMMED] worst: a specular highlight makes
     * one window varied wherever it covers the highlight, while a misaligned grid moves every window
     * off its sticker.
     */
    private fun locateByUniformity(work: Workspace): DoubleArray {
        val map = work.uniformity
        map.reset(work.rgb)
        val variance = work.uniformVariance
        val grids = UNIFORM_GRIDS
        var g = 0
        for (scale in UNIFORM_SCALES) {
            for (dy in -UNIFORM_SHIFT..UNIFORM_SHIFT) {
                for (dx in -UNIFORM_SHIFT..UNIFORM_SHIFT) {
                    for (cell in 0 until 9) {
                        variance[cell * grids + g] = map.variance(cellCenter(cell % 3, scale, dx.toDouble()), cellCenter(cell / 3, scale, dy.toDouble()))
                    }
                    g++
                }
            }
        }
        val cellBest = work.uniformCellBest
        var noise = 0.0
        for (cell in 0 until 9) {
            var min = Double.POSITIVE_INFINITY
            for (k in 0 until grids) min = minOf(min, variance[cell * grids + k])
            cellBest[cell] = min
            noise += min
        }
        val scores = work.uniformScores
        val excess = work.uniformExcess
        var best = Double.POSITIVE_INFINITY
        for (k in 0 until grids) {
            for (cell in 0 until 9) excess[cell] = variance[cell * grids + k] - cellBest[cell]
            excess.sort()
            var total = 0.0
            for (cell in 0 until 9 - UNIFORM_TRIMMED) total += excess[cell]
            scores[k] = total
            if (total < best) best = total
        }

        // Of all grids about as uniform as the best one, the one closest to the guide as placed.
        val limit = best + UNIFORM_TOLERANCE * noise + UNIFORM_FLOOR
        var closest = Double.POSITIVE_INFINITY
        var scale = 1.0
        var dx = 0.0
        var dy = 0.0
        g = 0
        for (candidateScale in UNIFORM_SCALES) {
            for (y in -UNIFORM_SHIFT..UNIFORM_SHIFT) {
                for (x in -UNIFORM_SHIFT..UNIFORM_SHIFT) {
                    val score = scores[g++]
                    if (score > limit) continue
                    // Distance in lattice steps (a scale step moves the outer cells by about one step);
                    // ties go to the more uniform grid.
                    val scaleSteps = (candidateScale - 1.0) * STEPS
                    val distance = (x * x + y * y).toDouble() + scaleSteps * scaleSteps + 1e-3 * score / limit
                    if (distance < closest) {
                        closest = distance
                        scale = candidateScale
                        dx = x.toDouble()
                        dy = y.toDouble()
                    }
                }
            }
        }
        val centers = work.centers
        for (cell in 0 until 9) {
            centers[2 * cell] = latticeToCell(cellCenter(cell % 3, scale, dx))
            centers[2 * cell + 1] = latticeToCell(cellCenter(cell / 3, scale, dy))
        }
        return centers
    }

    /** Summed-area tables of the lattice colors, for the color variance of square windows. */
    private class UniformityMap {
        private val stride = N + 1
        private val sumR = IntArray(stride * stride) // row 0 and column 0 stay zero
        private val sumG = IntArray(stride * stride)
        private val sumB = IntArray(stride * stride)
        private val sumSquares = IntArray(stride * stride) // at most N * N * 3 * 255^2, about 1e9: fits

        /** Rebuilds the tables for the lattice colors [rgb] (0xAARRGGBB, alpha ignored). */
        fun reset(rgb: IntArray) {
            for (j in 0 until N) {
                var r = 0
                var g = 0
                var b = 0
                var squares = 0
                for (i in 0 until N) {
                    val c = rgb[j * N + i]
                    val cr = (c shr 16) and 0xFF
                    val cg = (c shr 8) and 0xFF
                    val cb = c and 0xFF
                    r += cr
                    g += cg
                    b += cb
                    squares += cr * cr + cg * cg + cb * cb
                    val k = (j + 1) * stride + i + 1
                    val above = j * stride + i + 1
                    sumR[k] = sumR[above] + r
                    sumG[k] = sumG[above] + g
                    sumB[k] = sumB[above] + b
                    sumSquares[k] = sumSquares[above] + squares
                }
            }
        }

        /**
         * Color variance (summed over the three channels, in squared sRGB levels) of the
         * [UNIFORM_WINDOW]-point square window centered at lattice ([cx], [cy]), clipped to the lattice.
         */
        fun variance(cx: Double, cy: Double): Double {
            // The window has an even side, so its center snaps to round(c - 0.5) + 0.5 as in StickerMap.
            val x0 = (cx - 0.5).roundToInt() - (UNIFORM_WINDOW - 2) / 2
            val y0 = (cy - 0.5).roundToInt() - (UNIFORM_WINDOW - 2) / 2
            val l = x0.coerceIn(0, N)
            val t = y0.coerceIn(0, N)
            val r = (x0 + UNIFORM_WINDOW).coerceIn(0, N)
            val b = (y0 + UNIFORM_WINDOW).coerceIn(0, N)
            val n = ((r - l) * (b - t)).toDouble()
            if (n <= 0.0) return 0.0
            fun sum(table: IntArray): Double =
                (table[b * stride + r] - table[t * stride + r] - table[b * stride + l] + table[t * stride + l]).toDouble()
            val mr = sum(sumR) / n
            val mg = sum(sumG) / n
            val mb = sum(sumB) / n
            return sum(sumSquares) / n - mr * mr - mg * mg - mb * mb
        }
    }

    /**
     * Searches +-[LOCAL_SHIFT] lattice steps around ([baseX], [baseY]) and writes the center of the
     * best-scoring plateau and its score to `out[3 * slot]`, `out[3 * slot + 1]` and `out[3 * slot + 2]`.
     * If nothing scores at least [MIN_SCORE], the base position is kept. [scores] is scratch space.
     */
    private fun refine(map: StickerMap, baseX: Double, baseY: Double, out: DoubleArray, slot: Int, scores: DoubleArray) {
        val side = LOCAL_SIDE
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
     * by how far its score exceeds [MIN_SCORE], and writes the predicted center of every cell as
     * (x, y) pairs to [Workspace.predicted]. Returns false if too few cells were found confidently.
     */
    private fun predictAffine(found: DoubleArray, work: Workspace): Boolean {
        // Weighted normal equations for the basis [1, column - 1, row - 1].
        val m = work.normal
        val bx = work.rhsX
        val by = work.rhsY
        m.fill(0.0)
        bx.fill(0.0)
        by.fill(0.0)
        var confident = 0
        for (cell in 0 until 9) {
            val w = found[3 * cell + 2] - MIN_SCORE
            if (w <= 0.0) continue
            confident++
            for (a in 0 until 3) {
                val fa = basis(cell, a)
                for (b in 0 until 3) m[a * 3 + b] += w * fa * basis(cell, b)
                bx[a] += w * fa * found[3 * cell]
                by[a] += w * fa * found[3 * cell + 1]
            }
        }
        if (confident < MIN_CONFIDENT_CELLS) return false
        val ax = work.coefX
        val ay = work.coefY
        if (!solve3(m, bx, ax) || !solve3(m, by, ay)) return false
        for (cell in 0 until 9) {
            val c = (cell % 3 - 1).toDouble()
            val r = (cell / 3 - 1).toDouble()
            work.predicted[2 * cell] = ax[0] + ax[1] * c + ax[2] * r
            work.predicted[2 * cell + 1] = ay[0] + ay[1] * c + ay[2] * r
        }
        return true
    }

    /** Basis function [k] of the affine fit ([1, column - 1, row - 1]) at [cell]. */
    private fun basis(cell: Int, k: Int): Double = when (k) {
        0 -> 1.0
        1 -> (cell % 3 - 1).toDouble()
        else -> (cell / 3 - 1).toDouble()
    }

    /**
     * Solves the 3x3 system [m] x = [b] ([m] row-major) by Cramer's rule into [x]; returns false if
     * [m] is (nearly) singular.
     */
    private fun solve3(m: DoubleArray, b: DoubleArray, x: DoubleArray): Boolean {
        // Determinant of the matrix whose columns are (p0, p1, p2), (q0, q1, q2), (r0, r1, r2).
        fun det(p0: Double, p1: Double, p2: Double, q0: Double, q1: Double, q2: Double, r0: Double, r1: Double, r2: Double): Double =
            p0 * (q1 * r2 - q2 * r1) - q0 * (p1 * r2 - p2 * r1) + r0 * (p1 * q2 - p2 * q1)
        val d = det(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])
        if (abs(d) < 1e-9) return false
        x[0] = det(b[0], b[1], b[2], m[1], m[4], m[7], m[2], m[5], m[8]) / d
        x[1] = det(m[0], m[3], m[6], b[0], b[1], b[2], m[2], m[5], m[8]) / d
        x[2] = det(m[0], m[3], m[6], m[1], m[4], m[7], b[0], b[1], b[2]) / d
        return true
    }

    /** Otsu's threshold over the lattice points inside the region (features are 0..510). */
    private fun otsuThreshold(feature: IntArray, hist: IntArray): Int {
        hist.fill(0)
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
    private fun aggregate(view: UprightView, cu: Double, cv: Double, work: Workspace): StickerSample {
        val n = SAMPLES_PER_AXIS
        val count = SAMPLE_COUNT
        val red = work.red
        val green = work.green
        val blue = work.blue
        val lightness = work.lightness
        val chroma = work.chroma
        val lab = work.lab
        for (k in 0 until count) {
            val u = cu + SAMPLE_FRACTION * ((k % n + 0.5) / n - 0.5)
            val v = cv + SAMPLE_FRACTION * ((k / n + 0.5) / n - 0.5)
            val c = view.argb(u, v)
            red[k] = (c shr 16) and 0xFF
            green[k] = (c shr 8) and 0xFF
            blue[k] = c and 0xFF
            ColorMath.srgbToLab(red[k], green[k], blue[k], lab)
            lightness[k] = lab[0]
            chroma[k] = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        }

        // Drop the darkest quarter: black plastic at the edges, shadows, dark print.
        val keep = work.keep
        keep.fill(true)
        val darkCut = kthSmallest(lightness, keep, count / 4, work.floatScratch)
        for (k in 0 until count) keep[k] = lightness[k] >= darkCut

        // Drop specular highlights: much brighter than the sticker body and washed out.
        val medianL = median(lightness, keep, work.floatScratch)
        val medianC = median(chroma, keep, work.floatScratch)
        val noGlare = work.noGlare
        var anyLeft = false
        for (k in 0 until count) {
            noGlare[k] = keep[k] && !(lightness[k] > medianL + GLARE_LIGHTNESS && chroma[k] < GLARE_CHROMA_RATIO * medianC)
            anyLeft = anyLeft || noGlare[k]
        }
        val use = if (anyLeft) noGlare else keep
        val scratch = work.intScratch
        return StickerSample.of(median(red, use, scratch), median(green, use, scratch), median(blue, use, scratch))
    }

    /** Upper median of the [values] selected by [mask] (at least one must be). */
    private fun median(values: FloatArray, mask: BooleanArray, scratch: FloatArray): Float =
        kthSmallest(values, mask, mask.count { it } / 2, scratch)

    /** Upper median of the [values] selected by [mask] (at least one must be). */
    private fun median(values: IntArray, mask: BooleanArray, scratch: IntArray): Int {
        var n = 0
        for (i in values.indices) if (mask[i]) scratch[n++] = values[i]
        return select(scratch, n, n / 2)
    }

    /** The [k]-th smallest (0-based) of the [values] selected by [mask], using [scratch] as work space. */
    private fun kthSmallest(values: FloatArray, mask: BooleanArray, k: Int, scratch: FloatArray): Float {
        var n = 0
        for (i in values.indices) if (mask[i]) scratch[n++] = values[i]
        return select(scratch, n, k)
    }

    /** Quickselect (Hoare partition): the [k]-th smallest of `a[0 until n]`, which it reorders. */
    private fun select(a: IntArray, n: Int, k: Int): Int {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    val t = a[i]
                    a[i] = a[j]
                    a[j] = t
                    i++
                    j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[k]
    }

    /** Quickselect (Hoare partition): the [k]-th smallest of `a[0 until n]`, which it reorders. */
    private fun select(a: FloatArray, n: Int, k: Int): Float {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    val t = a[i]
                    a[i] = a[j]
                    a[j] = t
                    i++
                    j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[k]
    }
}
