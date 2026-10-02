package com.andhab.cubelens.core.vision

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Samples the stickers of an N×N face (N = 2..10; 3 by default) from an image.
 *
 * The caller passes the square the user was asked to fit the face into (the on-screen guide). Real
 * scans are never perfectly aligned: the face is a little smaller or larger than the guide, shifted,
 * slightly rotated or in perspective. So instead of blindly averaging fixed cells, the sampler works
 * in cell units (one cell is one sticker with its share of the gaps around it, `region.size / n`
 * pixels) and:
 *
 *  1. Reads a coarse lattice (16 points per cell, plus a margin of 0.75 cell around the guide) of a
 *     "sticker-ness" feature, `2 * max(r, g, b) - min(r, g, b)`, which is low only for the dark,
 *     unsaturated plastic between stickers (a shaded saturated sticker such as dark blue still scores
 *     high), and splits it into sticker / plastic with Otsu's threshold. If the "plastic" class is not
 *     dark (stickerless cubes, whose colored tiles touch, and white-bodied cubes), there are no dark
 *     gaps to lock onto, and step 2a replaces steps 2 and 3. On faces of 4x4 and up the class must also
 *     run across the whole face as gaps do: on a white body under a color cast, the darkest stickers
 *     alone can be dark enough to pass for gaps.
 *  2a. Faces without dark gaps: finds the global shift and scale of the N×N grid at which the sampling
 *     windows are most uniform in color (least sRGB variance, from summed-area tables), so that no
 *     window reaches into the white plastic or a neighbouring tile. Every grid within a tolerance of
 *     the best counts as equally good, and of those the one closest to the guide as placed is used:
 *     the guide itself whenever it is good enough (always on a face of one color), otherwise the grid
 *     that moves it least. On faces of 4x4 and up, each cell is then re-centered on its own plateau of
 *     most uniform windows, as step 3 does on stickers (see "Larger faces").
 *  2. Scores a candidate sticker position as the sticker fraction of a half-cell window there minus
 *     the sticker fraction of a thin square ring at the distance of the gaps between stickers. A
 *     real sticker is a bright patch surrounded by dark plastic, so this is high only on stickers,
 *     not on a bright background or across a gap. All window sums come from a summed-area table.
 *  3. Finds the global shift and scale of the grid with the best total score, then re-centers each
 *     cell on its own sticker (within a quarter cell). Cells that still disagree with a fit through
 *     the confidently found stickers are searched again around the fit's prediction, which absorbs
 *     rotation and perspective.
 *  4. Reads a 9x9 grid of points over the central [SAMPLE_FRACTION] of each located sticker, drops the
 *     darkest quarter by L* (plastic, shadowed edges, dark logo print) and specular highlights
 *     (bright, washed-out outliers), and takes the per-channel median.
 *
 * **Larger faces.** All windows and search ranges are in cell units, so they scale with the stickers:
 * on a 7×7 face the grid may be off by about a third of a cell (5% of the face) and still lock on. A
 * face is periodic, so a misalignment of half a cell or more would be ambiguous anyway; scan screens
 * for big cubes should draw the grid lines of the guide. The outer cells of a big face lie far from
 * its center, so a few degrees of rotation or a slightly tilted phone move them by up to half a cell
 * off any shift-and-scale grid. On faces of 4x4 and up the fit through the found stickers is therefore
 * a homography (which models perspective exactly), made robust: stickers far off the fit are dropped
 * and the fit is redone, and it is used only if the stickers it rests on span most of the face and
 * lie close to it, and if it predicts a regular grid (neighbouring cells about one cell apart, in
 * about the row and column directions). Two rounds of fitting and searching again around the fit's
 * predictions follow.
 * 3x3 and 2x2 faces keep the affine fit. When the cells are too small in the image for the gaps to
 * be resolved (under [MIN_LOCK_CELL_PIXELS] pixels per cell), steps 1 to 3 are skipped and the
 * stickers are read where the guide puts them.
 *
 * Measured on rendered faces with 22 pixels per cell (see the tests), in poses as handheld scanning
 * gives them (guide off by up to 3% of the face, 3 degrees of rotation, perspective) under neutral,
 * warm, cool and mixed light: every sticker of black-bodied and white-bodied faces, vivid and pastel,
 * read right at every size up to 7x7. Stickerless faces from 5x5 on are the hardest case, because
 * neighbouring tiles of one color merge into one patch: about one face in 200 has one to three
 * stickers near a corner of the face read partly off their tile. In poses off by more (4% of the
 * face, 4 degrees, stronger perspective) that becomes a few faces in 100, and about one white-bodied
 * 7x7 face in 80 is read off-grid altogether (its outer row lies beyond the search range). From 4x4
 * on, [ScanResolver] corrects a single misread sticker and reports more as an invalid cube.
 *
 * Cost grows with the number of stickers, not with the image size: about 6k pixel reads for a 3×3
 * face (under a millisecond on the JVM) and 23k for a 7×7 face (2 to 3 milliseconds). Scratch buffers
 * (about 0.3 MB for a 3×3 face, 1.5 MB for 7×7) are kept per thread and per size and reused, so a
 * camera analyzer calling this for every frame allocates little more than the returned samples.
 */
object GridSampler {

    /** Fraction of a cell (per axis) covered by the final color sampling window. */
    const val SAMPLE_FRACTION = 0.55

    /**
     * Below this many image pixels per cell the sticker gaps (about a fifth of a cell) are too thin to
     * lock onto reliably, and the stickers are read at the guide's grid instead.
     */
    const val MIN_LOCK_CELL_PIXELS = 6.0

    /** Smallest and largest supported face size (stickers per row). */
    private const val MIN_N = 2
    private const val MAX_N = 10

    private const val STEPS = 16 // lattice points per cell
    private const val MARGIN = 12 // lattice points outside the region on each side
    private const val WINDOW = 8 // side of the sticker window, lattice points (half a cell)
    private const val RING_OUTER = 18 // outer side of the gap ring (+-0.56 cell)
    private const val RING_INNER = 14 // inner side of the gap ring (+-0.44 cell)
    private const val MEMO_PAD = 8 // memoized score positions outside the lattice on each side
    private const val GLOBAL_SHIFT = 6 // +- lattice steps searched for the global grid offset
    private val GLOBAL_SCALES = doubleArrayOf(0.88, 0.94, 1.0, 1.06, 1.12)
    private const val GLOBAL_PENALTY = 0.15 // score units (per nine cells) at the edge of the global search range
    private const val LOCAL_SHIFT = 4 // +- lattice steps searched per cell
    private const val LOCAL_PENALTY = 0.1 // score units at the edge of the local search range
    private const val PLATEAU_TOLERANCE = 0.05 // scores within this of the best count as equally good
    private const val MIN_SCORE = 0.3 // below this a cell keeps the grid position
    private const val RETRY_DISTANCE = 1.5 // lattice steps off the fit's prediction that trigger a retry
    private const val MIN_CONFIDENT_CELLS = 5 // needed for the affine fit (of nine; scaled for other sizes)
    private const val LOCAL_SIDE = 2 * LOCAL_SHIFT + 1
    private const val FEATURE_LEVELS = 511 // 2 * max - min ranges over 0..510
    private const val SAMPLES_PER_AXIS = 9
    private const val SAMPLE_COUNT = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS
    private const val GLARE_LIGHTNESS = 12f // L* above the median that marks a highlight ...
    private const val GLARE_CHROMA_RATIO = 0.5f // ... if its chroma is also below this fraction of the median
    private const val GAP_DARKNESS = 0.5 // gaps are dark: their median brightness is below this fraction of the stickers'
    private const val GAP_LINES_MIN_SIZE = 4 // faces at least this big also require the dark class to cross every line of the face ...
    private const val GAP_LINE_SHARE = 0.08 // ... i.e. this share of a lattice row's or column's points inside the region ...
    private const val GAP_LINES = 0.85 // ... on at least this share of the rows and of the columns
    private const val UNIFORM_WINDOW = 10 // side of the uniformity window, lattice points (0.625 cell, more than SAMPLE_FRACTION)
    private const val UNIFORM_SHIFT = 6 // +- lattice steps searched for the grid offset of a face without dark gaps
    private val UNIFORM_SCALES = doubleArrayOf(0.94, 1.0, 1.06)
    private val UNIFORM_GRIDS = UNIFORM_SCALES.size * (2 * UNIFORM_SHIFT + 1) * (2 * UNIFORM_SHIFT + 1)
    private const val UNIFORM_TOLERANCE = 0.1 // a grid is as good as the best within this fraction of the cells' own variance ...
    private const val UNIFORM_NOISE = 3 * 4.0 // ... plus the variance of sensor noise of 2 sRGB levels per window (3 channels)
    private const val UNIFORM_REFINE_MIN_SIZE = 4 // faces at least this big also re-center each cell without dark gaps
    private const val UNIFORM_LOCAL_SHIFT = 6 // +- lattice steps searched per cell on faces without dark gaps
    private const val UNIFORM_PLATEAU = 0.25 // windows within this fraction of the least variance (plus noise) are a cell's plateau
    private const val UNIFORM_CONTRAST = 300.0 // a located cell's surroundings vary this much more (sRGB levels squared, 3 channels: 10 levels)
    private const val UNIFORM_RETRY_DISTANCE = 2.0 // lattice steps off the fit's prediction that overrule a located cell
    private const val AFFINE_MAX_SIZE = 3 // faces up to this size predict cells with an affine fit; larger ones with a homography
    private const val FIT_ROUNDS = 2 // fit-and-search-again rounds on larger faces
    private const val MIN_PROJECTIVE_CELLS = 8 // confident cells needed for the homography (8 unknowns)
    private const val MIN_PROJECTIVE_DENOMINATOR = 0.2 // a homography that folds the grid (denominator near zero) is rejected
    private const val FIT_PASSES = 4 // fits on larger faces: drop outliers and refit up to this often
    private const val OUTLIER_DISTANCE = 3.0 // lattice steps (0.19 cell) off the fit that make a cell an outlier
    private const val MAX_FIT_RMS = 1.5 // lattice steps: the fit is used only if its inliers lie this close (root mean square)
    private const val MIN_FIT_SPAN = 0.6 // fraction of the grid's width and height that the fitted cells must span
    private const val MIN_FIT_SPACING = 0.7 // cells: a fitted grid's neighbouring cells are at least this far apart ...
    private const val MAX_FIT_SPACING = 1.5 // ... and at most this far (a tilted phone gives 0.85 to 1.3) ...
    private const val MAX_FIT_SKEW = 0.2 // ... and off the row or column direction by at most this fraction of their distance (0.1)

    /**
     * Samples the [n]x[n] stickers inside [region] of [source] ([n] in 2..10; 3 for a standard cube).
     *
     * [rotationDegrees] (0, 90, 180 or 270) is the clockwise rotation that makes [source] upright as
     * the user sees it (CameraX `ImageInfo.rotationDegrees`). The returned `n * n` samples are
     * row-major as the user sees the face on screen.
     *
     * @throws IllegalArgumentException for an empty image or region, a rotation that is not a multiple
     *   of 90 degrees, or an unsupported [n].
     */
    fun sample(source: PixelSource, region: GridRegion, rotationDegrees: Int = 0, n: Int = 3): List<StickerSample> {
        require(n in MIN_N..MAX_N) { "Face size must be in $MIN_N..$MAX_N, got $n" }
        val view = UprightView(source, region, rotationDegrees, n)
        val work = workspace(n)
        val centers = locateStickers(view, region, work)
        return List(n * n) { aggregate(view, centers[2 * it], centers[2 * it + 1], work) }
    }

    /**
     * Where [sample] found the sticker centers, as (u, v) pairs in cell units of the upright region
     * (0..[n] on each axis, u to the right, v down), row-major.
     */
    internal fun stickerCenters(source: PixelSource, region: GridRegion, rotationDegrees: Int = 0, n: Int = 3): DoubleArray {
        require(n in MIN_N..MAX_N) { "Face size must be in $MIN_N..$MAX_N, got $n" }
        return locateStickers(UprightView(source, region, rotationDegrees, n), region, workspace(n)).copyOf()
    }

    /**
     * Scratch buffers for one [sample] call on an [n]x[n] face. Each thread gets its own per size,
     * reused for every call on that thread (a camera analyzer runs on one thread and scans one size at
     * a time, so this is a single set of buffers in practice). Nothing in here outlives a call: every
     * buffer is fully rewritten before it is read.
     */
    private class Workspace(val n: Int) {
        /** Stickers on the face. */
        val cells = n * n

        /** Side of the lattice, in points. */
        val side = n * STEPS + 2 * MARGIN

        /**
         * Penalties of the global grid search scale with the number of cells, so that they weigh the
         * same against the summed cell scores at every size (exactly the nine-cell values for 3x3).
         */
        val globalPenalty = GLOBAL_PENALTY * (cells / 9.0)

        /** Cells left out of a uniformity score (e.g. specular highlights): two of nine, scaled. */
        val uniformTrimmed = max(1, (2 * cells + 4) / 9)

        /** Sensor-noise floor of a uniformity score: [UNIFORM_NOISE] per window that counts. */
        val uniformFloor = (cells - uniformTrimmed) * UNIFORM_NOISE

        /** Confidently found cells needed for the affine fit: five of nine, scaled; all four of a 2x2 face. */
        val minConfident = if (cells <= 4) cells else max(MIN_CONFIDENT_CELLS, (MIN_CONFIDENT_CELLS * cells + 8) / 9)

        val feature = IntArray(side * side)
        val brightness = IntArray(side * side)
        val below = IntArray(cells * STEPS * STEPS)
        val above = IntArray(cells * STEPS * STEPS)
        val darkPerRow = IntArray(n * STEPS)
        val darkPerColumn = IntArray(n * STEPS)
        val histogram = IntArray(FEATURE_LEVELS)
        val rgb = IntArray(side * side)
        val uniformity = UniformityMap(side)
        val uniformVariance = DoubleArray(cells * UNIFORM_GRIDS)
        val uniformCellBest = DoubleArray(cells)
        val uniformExcess = DoubleArray(cells)
        val uniformScores = DoubleArray(UNIFORM_GRIDS)
        val map = StickerMap(side)
        val scores = DoubleArray(LOCAL_SIDE * LOCAL_SIDE)
        val uniformScratch = DoubleArray((2 * UNIFORM_LOCAL_SHIFT + 1) * (2 * UNIFORM_LOCAL_SHIFT + 1))
        val found = DoubleArray(3 * cells)
        val fitInput = DoubleArray(3 * cells)
        val retry = DoubleArray(3)
        val predicted = DoubleArray(2 * cells)
        val fitted = DoubleArray(2 * cells)
        val centers = DoubleArray(2 * cells)
        val normal = DoubleArray(9)
        val rhsX = DoubleArray(3)
        val rhsY = DoubleArray(3)
        val coefX = DoubleArray(3)
        val coefY = DoubleArray(3)
        val normal8 = DoubleArray(64)
        val rhs8 = DoubleArray(8)
        val coef8 = DoubleArray(8)
        val row8 = DoubleArray(8)
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

        /** Grid coordinate of [index] (0 until n) relative to the face center, in cells: -(n-1)/2..(n-1)/2. */
        fun offset(index: Int): Double = index - (n - 1) / 2.0

        /** Lattice coordinate of the center of cell [index] (0 until n) under a grid [scale] and [shift]. */
        fun cellCenter(index: Int, scale: Double, shift: Double): Double =
            (n / 2.0 + offset(index) * scale) * STEPS + MARGIN - 0.5 + shift
    }

    /** Per thread, the workspace of each face size used on that thread (indexed by size). */
    private val workspaces = object : ThreadLocal<Array<Workspace?>>() {
        override fun initialValue() = arrayOfNulls<Workspace>(MAX_N + 1)
    }

    private fun workspace(n: Int): Workspace {
        val slots = workspaces.get()
        return slots[n] ?: Workspace(n).also { slots[n] = it }
    }

    /**
     * The region as the user sees it: (u, v) in cell units, u to the right and v down on screen,
     * (0, 0) at the region's top-left and (n, n) at its bottom-right.
     */
    private class UprightView(private val source: PixelSource, region: GridRegion, rotationDegrees: Int, n: Int) {
        private val left = region.left.toDouble()
        private val top = region.top.toDouble()
        private val size = region.size.toDouble()
        private val cellsPerSide = n.toDouble()

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
            val un = u / cellsPerSide
            val vn = v / cellsPerSide
            val x = left + size * (ax * un + bx * vn + cx)
            val y = top + size * (ay * un + by * vn + cy)
            return source.argb(floor(x).toInt().coerceIn(0, maxX), floor(y).toInt().coerceIn(0, maxY))
        }
    }

    /** Upright coordinate (cell units) of lattice index [i]. */
    private fun latticeToCell(i: Double): Double = (i - MARGIN + 0.5) / STEPS

    /** Sticker indicator of the lattice as a summed-area table, with window queries. */
    private class StickerMap(private val side: Int) {
        private val stride = side + 1
        private val sat = IntArray(stride * stride) // row 0 and column 0 stay zero

        /** Rebuilds the map for a new lattice: points whose [feature] exceeds [threshold] are sticker. */
        fun reset(feature: IntArray, threshold: Int) {
            for (j in 0 until side) {
                var rowSum = 0
                for (i in 0 until side) {
                    if (feature[j * side + i] > threshold) rowSum++
                    sat[(j + 1) * stride + i + 1] = sat[j * stride + i + 1] + rowSum
                }
            }
            memo.fill(Double.NaN)
        }

        /** Sticker count and area of the [length] x [length] square starting at lattice ([x0], [y0]), clipped. */
        private fun square(x0: Int, y0: Int, length: Int, out: IntArray) {
            val l = x0.coerceIn(0, side)
            val t = y0.coerceIn(0, side)
            val r = (x0 + length).coerceIn(0, side)
            val b = (y0 + length).coerceIn(0, side)
            out[0] = sat[b * stride + r] - sat[t * stride + r] - sat[b * stride + l] + sat[t * stride + l]
            out[1] = (r - l) * (b - t)
        }

        private val window = IntArray(2)
        private val outer = IntArray(2)
        private val inner = IntArray(2)

        // All squares have even sides, so a center at lattice coordinate c snaps to the same point
        // (round(c - 0.5)) for each of them; scores are memoized per snapped point.
        private val memoStride = side + 2 * MEMO_PAD
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
    private fun locateStickers(view: UprightView, region: GridRegion, work: Workspace): DoubleArray {
        val n = work.n
        val cells = work.cells
        if (region.size.toDouble() / n < MIN_LOCK_CELL_PIXELS) return guideCenters(work)
        val side = work.side
        val feature = work.feature
        val brightness = work.brightness
        val rgb = work.rgb
        for (j in 0 until side) {
            val v = latticeToCell(j.toDouble())
            for (i in 0 until side) {
                val c = view.argb(latticeToCell(i.toDouble()), v)
                rgb[j * side + i] = c
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val brightest = max(r, max(g, b))
                feature[j * side + i] = 2 * brightest - min(r, min(g, b))
                brightness[j * side + i] = brightest
            }
        }
        val threshold = otsuThreshold(feature, work)
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
            val scalePenalty = work.globalPenalty * ((scale - 1.0) / maxScaleOffset).let { it * it }
            for (dy in -GLOBAL_SHIFT..GLOBAL_SHIFT) {
                for (dx in -GLOBAL_SHIFT..GLOBAL_SHIFT) {
                    var total = 0.0
                    for (cell in 0 until cells) {
                        total += map.score(work.cellCenter(cell % n, scale, dx.toDouble()), work.cellCenter(cell / n, scale, dy.toDouble()))
                    }
                    val shiftPenalty = work.globalPenalty * (dx * dx + dy * dy).toDouble() / (2 * GLOBAL_SHIFT * GLOBAL_SHIFT)
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
        for (cell in 0 until cells) {
            refine(map, work.cellCenter(cell % n, bestScale, bestDx.toDouble()), work.cellCenter(cell / n, bestScale, bestDy.toDouble()), found, cell, work.scores)
        }

        // Second chance for cells the shift-and-scale grid placed badly (rotation, perspective): search
        // again around where the confidently found stickers say they should be. Larger faces get a
        // second round, after refitting with the cells found in the first.
        for (round in 0 until if (n > AFFINE_MAX_SIZE) FIT_ROUNDS else 1) {
            if (!predictGrid(found, work)) break
            val predicted = work.predicted
            val retry = work.retry
            for (cell in 0 until cells) {
                val dx = predicted[2 * cell] - found[3 * cell]
                val dy = predicted[2 * cell + 1] - found[3 * cell + 1]
                if (dx * dx + dy * dy < RETRY_DISTANCE * RETRY_DISTANCE) continue
                refine(map, predicted[2 * cell], predicted[2 * cell + 1], retry, 0, work.scores)
                if (retry[2] > found[3 * cell + 2] + PLATEAU_TOLERANCE) {
                    retry.copyInto(found, 3 * cell)
                } else if (n > AFFINE_MAX_SIZE && found[3 * cell + 2] < MIN_SCORE) {
                    // Not found anywhere (e.g. a dark sticker in dim light): where the fit puts it.
                    found[3 * cell] = predicted[2 * cell]
                    found[3 * cell + 1] = predicted[2 * cell + 1]
                }
            }
        }

        val centers = work.centers
        for (cell in 0 until cells) {
            centers[2 * cell] = latticeToCell(found[3 * cell])
            centers[2 * cell + 1] = latticeToCell(found[3 * cell + 1])
        }
        return centers
    }

    /** The cell centers of the guide as placed, as sticker centers in [Workspace.centers]. */
    private fun guideCenters(work: Workspace): DoubleArray {
        val n = work.n
        val centers = work.centers
        for (cell in 0 until work.cells) {
            centers[2 * cell] = cell % n + 0.5
            centers[2 * cell + 1] = cell / n + 0.5
        }
        return centers
    }

    /**
     * Whether the face has dark plastic between its stickers, as stickered cubes with a black body do:
     * the typical brightness (brightest channel) of the lattice points below the Otsu [threshold]
     * inside the region is well below that of the points above it. Without dark gaps (stickerless
     * cubes, whose colored tiles touch, and white-bodied cubes) the two classes are just two groups
     * of sticker colors, and fitting the grid to them would lock onto color blobs instead of stickers.
     *
     * On a white body the darkest stickers (red, blue) can make up the darker class on their own, and
     * under a cool or warm cast they can be dark enough to pass for gaps; the grid would then lock onto
     * the white plastic. Faces of [GAP_LINES_MIN_SIZE] and more stickers per row therefore also need
     * the darker class to be laid out like gaps ([crossesEveryLine]); 2x2 and 3x3 faces keep the
     * brightness test alone.
     */
    private fun hasDarkGaps(feature: IntArray, brightness: IntArray, threshold: Int, work: Workspace): Boolean {
        val side = work.side
        val inside = work.n * STEPS
        val below = work.below
        val above = work.above
        val darkPerRow = work.darkPerRow
        val darkPerColumn = work.darkPerColumn
        darkPerRow.fill(0)
        darkPerColumn.fill(0)
        var nBelow = 0
        var nAbove = 0
        for (j in MARGIN until MARGIN + inside) {
            for (i in MARGIN until MARGIN + inside) {
                val k = j * side + i
                if (feature[k] > threshold) {
                    above[nAbove++] = brightness[k]
                } else {
                    below[nBelow++] = brightness[k]
                    darkPerRow[j - MARGIN]++
                    darkPerColumn[i - MARGIN]++
                }
            }
        }
        if (nBelow == 0 || nAbove == 0) return true
        if (!(select(below, nBelow, nBelow / 2) < GAP_DARKNESS * select(above, nAbove, nAbove / 2))) return false
        return work.n < GAP_LINES_MIN_SIZE || crossesEveryLine(work)
    }

    /**
     * Whether the darker class of the lattice (counted per row and column by [hasDarkGaps]) is laid out
     * like the gaps of a stickered face: the plastic between the stickers runs across the whole face,
     * so nearly every row and every column of lattice points ([GAP_LINES]) crosses some of it (at least
     * [GAP_LINE_SHARE] of its points; a fifth to a half on black-bodied faces). Dark stickers on a white
     * body are separate patches: the rows and columns along the white plastic between them cross none.
     */
    private fun crossesEveryLine(work: Workspace): Boolean {
        val inside = work.n * STEPS
        val minDark = GAP_LINE_SHARE * inside
        var rows = 0
        var columns = 0
        for (k in 0 until inside) {
            if (work.darkPerRow[k] >= minDark) rows++
            if (work.darkPerColumn[k] >= minDark) columns++
        }
        return rows >= GAP_LINES * inside && columns >= GAP_LINES * inside
    }

    /**
     * Step 2a, for faces without dark gaps: the shift and scale of the grid that keeps the windows of
     * [UNIFORM_WINDOW] points inside uniformly colored areas (see the class documentation), as sticker
     * centers in [Workspace.centers].
     *
     * Each grid is scored by how much more varied each cell's window is there than at that cell's
     * best grid, summed over the cells except the [Workspace.uniformTrimmed] worst: a specular
     * highlight makes one window varied wherever it covers the highlight, while a misaligned grid
     * moves every window off its sticker.
     */
    private fun locateByUniformity(work: Workspace): DoubleArray {
        val n = work.n
        val cells = work.cells
        val map = work.uniformity
        map.reset(work.rgb)
        val variance = work.uniformVariance
        val grids = UNIFORM_GRIDS
        var g = 0
        for (scale in UNIFORM_SCALES) {
            for (dy in -UNIFORM_SHIFT..UNIFORM_SHIFT) {
                for (dx in -UNIFORM_SHIFT..UNIFORM_SHIFT) {
                    for (cell in 0 until cells) {
                        variance[cell * grids + g] = map.variance(work.cellCenter(cell % n, scale, dx.toDouble()), work.cellCenter(cell / n, scale, dy.toDouble()))
                    }
                    g++
                }
            }
        }
        val cellBest = work.uniformCellBest
        var noise = 0.0
        for (cell in 0 until cells) {
            var min = Double.POSITIVE_INFINITY
            for (k in 0 until grids) min = minOf(min, variance[cell * grids + k])
            cellBest[cell] = min
            noise += min
        }
        val scores = work.uniformScores
        val excess = work.uniformExcess
        val counted = cells - work.uniformTrimmed
        var best = Double.POSITIVE_INFINITY
        for (k in 0 until grids) {
            for (cell in 0 until cells) excess[cell] = variance[cell * grids + k] - cellBest[cell]
            excess.sort()
            var total = 0.0
            for (cell in 0 until counted) total += excess[cell]
            scores[k] = total
            if (total < best) best = total
        }

        // Of all grids about as uniform as the best one, the one closest to the guide as placed.
        val limit = best + UNIFORM_TOLERANCE * noise + work.uniformFloor
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
        if (n >= UNIFORM_REFINE_MIN_SIZE) return refineUniformCells(work, scale, dx, dy)
        for (cell in 0 until cells) {
            centers[2 * cell] = latticeToCell(work.cellCenter(cell % n, scale, dx))
            centers[2 * cell + 1] = latticeToCell(work.cellCenter(cell / n, scale, dy))
        }
        return centers
    }

    /**
     * Step 2a continued, on faces of [UNIFORM_REFINE_MIN_SIZE] and more stickers per row, whose outer
     * cells lie far enough from the center that rotation and perspective move them off a shift-and-
     * scale grid by up to a quarter cell: each cell is re-centered on the plateau of most uniform
     * windows within 0.375 cell of the grid ([refineUniform]). Cells whose plateau is bounded
     * (a sticker that stands out from its surroundings) are located; an affine fit through them
     * places the others (a white sticker on a white body, a tile among tiles of its own color) and
     * overrules located cells that disagree with it (e.g. pulled aside by a highlight).
     */
    private fun refineUniformCells(work: Workspace, scale: Double, dx: Double, dy: Double): DoubleArray {
        val n = work.n
        val cells = work.cells
        val found = work.found
        val map = work.uniformity
        for (cell in 0 until cells) {
            refineUniform(map, work.cellCenter(cell % n, scale, dx), work.cellCenter(cell / n, scale, dy), found, cell, work.uniformScratch)
        }
        // Cells not located near the grid (perspective moves the corners of a big face by up to half a
        // cell) are searched again around the fit's prediction, and the fit is redone with them.
        val predicted = work.fitted // the last good prediction (a failed fit may leave work.predicted half-written)
        val retry = work.retry
        var fitted = false
        for (round in 0 until FIT_ROUNDS) {
            if (!predictGrid(found, work)) break
            work.predicted.copyInto(predicted)
            fitted = true
            for (cell in 0 until cells) {
                val px = predicted[2 * cell]
                val py = predicted[2 * cell + 1]
                if (found[3 * cell + 2] > MIN_SCORE && near(found, cell, px, py)) continue
                refineUniform(map, px, py, retry, 0, work.uniformScratch)
                if (retry[2] > MIN_SCORE && near(retry, 0, px, py)) retry.copyInto(found, 3 * cell)
            }
        }
        val centers = work.centers
        for (cell in 0 until cells) {
            var x = found[3 * cell]
            var y = found[3 * cell + 1]
            val located = found[3 * cell + 2] > MIN_SCORE
            if (fitted) {
                val px = predicted[2 * cell]
                val py = predicted[2 * cell + 1]
                if (!located || !near(found, cell, px, py)) {
                    x = px
                    y = py
                }
            } else if (!located) {
                x = work.cellCenter(cell % n, scale, dx)
                y = work.cellCenter(cell / n, scale, dy)
            }
            centers[2 * cell] = latticeToCell(x)
            centers[2 * cell + 1] = latticeToCell(y)
        }
        return centers
    }

    /** Whether the position in `found[3 * slot]`, `found[3 * slot + 1]` is within [UNIFORM_RETRY_DISTANCE] of ([x], [y]). */
    private fun near(found: DoubleArray, slot: Int, x: Double, y: Double): Boolean {
        val dx = found[3 * slot] - x
        val dy = found[3 * slot + 1] - y
        return dx * dx + dy * dy <= UNIFORM_RETRY_DISTANCE * UNIFORM_RETRY_DISTANCE
    }

    /**
     * Searches +-[UNIFORM_LOCAL_SHIFT] lattice steps around ([baseX], [baseY]) for the most uniform windows
     * and writes the center of their plateau (windows within [UNIFORM_PLATEAU] of the least variance,
     * plus the noise floor) to `out[3 * slot]` and `out[3 * slot + 1]`. `out[3 * slot + 2]` is in the
     * score convention of [refine] for [predictAffine]: above [MIN_SCORE] (by up to 1, more for a
     * stronger contrast) if the plateau is bounded inside the search area and the windows around it
     * are clearly less uniform, else below it (the base position is written then).
     */
    private fun refineUniform(map: UniformityMap, baseX: Double, baseY: Double, out: DoubleArray, slot: Int, variances: DoubleArray) {
        val reach = UNIFORM_LOCAL_SHIFT
        val side = 2 * reach + 1
        var least = Double.POSITIVE_INFINITY
        var most = Double.NEGATIVE_INFINITY
        for (ey in -reach..reach) {
            for (ex in -reach..reach) {
                val v = map.variance(baseX + ex, baseY + ey)
                variances[(ey + reach) * side + ex + reach] = v
                if (v < least) least = v
                if (v > most) most = v
            }
        }
        val limit = least * (1.0 + UNIFORM_PLATEAU) + UNIFORM_NOISE
        var count = 0
        var sumX = 0
        var sumY = 0
        var bounded = true
        for (ey in -reach..reach) {
            for (ex in -reach..reach) {
                if (variances[(ey + reach) * side + ex + reach] > limit) continue
                count++
                sumX += ex
                sumY += ey
                if (abs(ex) == reach || abs(ey) == reach) bounded = false
            }
        }
        val contrast = most - limit
        if (bounded && contrast >= UNIFORM_CONTRAST) {
            out[3 * slot] = baseX + sumX.toDouble() / count
            out[3 * slot + 1] = baseY + sumY.toDouble() / count
            out[3 * slot + 2] = MIN_SCORE + min(1.0, contrast / (4 * UNIFORM_CONTRAST))
        } else {
            out[3 * slot] = baseX
            out[3 * slot + 1] = baseY
            out[3 * slot + 2] = MIN_SCORE - 1.0
        }
    }

    /** Summed-area tables of the lattice colors, for the color variance of square windows. */
    private class UniformityMap(private val side: Int) {
        private val stride = side + 1
        private val sumR = IntArray(stride * stride) // row 0 and column 0 stay zero
        private val sumG = IntArray(stride * stride)
        private val sumB = IntArray(stride * stride)

        // At most side^2 * 3 * 255^2: about 1e9 for a 3x3 face, too much for an Int from 4x4 on.
        private val sumSquares = LongArray(stride * stride)

        /** Rebuilds the tables for the lattice colors [rgb] (0xAARRGGBB, alpha ignored). */
        fun reset(rgb: IntArray) {
            for (j in 0 until side) {
                var r = 0
                var g = 0
                var b = 0
                var squares = 0L
                for (i in 0 until side) {
                    val c = rgb[j * side + i]
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
            val l = x0.coerceIn(0, side)
            val t = y0.coerceIn(0, side)
            val r = (x0 + UNIFORM_WINDOW).coerceIn(0, side)
            val b = (y0 + UNIFORM_WINDOW).coerceIn(0, side)
            val n = ((r - l) * (b - t)).toDouble()
            if (n <= 0.0) return 0.0
            fun sum(table: IntArray): Double =
                (table[b * stride + r] - table[t * stride + r] - table[b * stride + l] + table[t * stride + l]).toDouble()
            val squares = (sumSquares[b * stride + r] - sumSquares[t * stride + r] - sumSquares[b * stride + l] + sumSquares[t * stride + l]).toDouble()
            val mr = sum(sumR) / n
            val mg = sum(sumG) / n
            val mb = sum(sumB) / n
            return squares / n - mr * mr - mg * mg - mb * mb
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
     * Predicts every cell's center from the confidently found ones into [Workspace.predicted]: with a
     * projective map (a homography, which models perspective exactly) on faces larger than
     * [AFFINE_MAX_SIZE] when enough cells were found, else with an affine map. Returns false if too
     * few cells were found confidently.
     */
    private fun predictGrid(found: DoubleArray, work: Workspace): Boolean {
        if (work.n <= AFFINE_MAX_SIZE) return predictAffine(found, work)
        // Larger faces: a robust fit. Cells far off the fit (a plateau pulled aside by a highlight or a
        // same-colored neighbour) are dropped and the fit is redone; it is used only if the remaining
        // cells span most of the grid (so that it does not extrapolate) and lie close to it.
        val input = work.fitInput
        found.copyInto(input, 0, 0, 3 * work.cells)
        for (pass in 0 until FIT_PASSES) {
            if (!spansGrid(input, work)) return false
            if (!predictProjective(input, work) && !predictAffine(input, work)) return false
            var dropped = false
            var sum = 0.0
            var weight = 0.0
            for (cell in 0 until work.cells) {
                val w = input[3 * cell + 2] - MIN_SCORE
                if (w <= 0.0) continue
                val dx = work.predicted[2 * cell] - input[3 * cell]
                val dy = work.predicted[2 * cell + 1] - input[3 * cell + 1]
                val d2 = dx * dx + dy * dy
                if (d2 > OUTLIER_DISTANCE * OUTLIER_DISTANCE) {
                    input[3 * cell + 2] = MIN_SCORE - 1.0
                    dropped = true
                } else {
                    sum += w * d2
                    weight += w
                }
            }
            if (!dropped) return weight > 0.0 && sum / weight <= MAX_FIT_RMS * MAX_FIT_RMS && isRegular(work.predicted, work)
        }
        return false
    }

    /**
     * Whether the predicted cell centers [centers] (x, y pairs) form a plausible grid: each cell's right
     * and lower neighbours lie about one cell away ([MIN_FIT_SPACING] to [MAX_FIT_SPACING] cells) and
     * in about the right direction (off it by at most [MAX_FIT_SKEW] of the spacing), as a few
     * degrees of rotation and a tilted phone allow. A homography through cells that were located
     * imprecisely can extrapolate into a folded or squeezed grid.
     */
    private fun isRegular(centers: DoubleArray, work: Workspace): Boolean {
        val n = work.n
        val min = MIN_FIT_SPACING * STEPS
        val max = MAX_FIT_SPACING * STEPS
        for (cell in 0 until work.cells) {
            val x = centers[2 * cell]
            val y = centers[2 * cell + 1]
            if (cell % n < n - 1) {
                val along = centers[2 * cell + 2] - x
                val across = centers[2 * cell + 3] - y
                if (along !in min..max || abs(across) > MAX_FIT_SKEW * along) return false
            }
            if (cell / n < n - 1) {
                val along = centers[2 * (cell + n) + 1] - y
                val across = centers[2 * (cell + n)] - x
                if (along !in min..max || abs(across) > MAX_FIT_SKEW * along) return false
            }
        }
        return true
    }

    /**
     * Whether the confidently found cells of [found] span at least [MIN_FIT_SPAN] of the grid's width
     * and height, so that a fit through them interpolates rather than extrapolates.
     */
    private fun spansGrid(found: DoubleArray, work: Workspace): Boolean {
        val n = work.n
        var minCol = n
        var maxCol = -1
        var minRow = n
        var maxRow = -1
        for (cell in 0 until work.cells) {
            if (found[3 * cell + 2] <= MIN_SCORE) continue
            minCol = min(minCol, cell % n)
            maxCol = max(maxCol, cell % n)
            minRow = min(minRow, cell / n)
            maxRow = max(maxRow, cell / n)
        }
        val needed = MIN_FIT_SPAN * (n - 1)
        return maxCol - minCol >= needed && maxRow - minRow >= needed
    }

    /**
     * Fits a homography from grid position (column and row offsets from the face center) to the
     * found centers (in cells from the lattice center), weighting each cell by how far its score
     * exceeds [MIN_SCORE], by linear least squares on the cross-multiplied equations (the direct
     * linear transform), and writes the predicted center of every cell to [Workspace.predicted].
     * Returns false with fewer than [MIN_PROJECTIVE_CELLS] confident cells or a degenerate fit.
     */
    private fun predictProjective(found: DoubleArray, work: Workspace): Boolean {
        val n = work.n
        val m = work.normal8
        val rhs = work.rhs8
        val h = work.coef8
        val row = work.row8
        m.fill(0.0)
        rhs.fill(0.0)
        val center = n / 2.0 * STEPS + MARGIN - 0.5
        var confident = 0
        for (cell in 0 until work.cells) {
            val w = found[3 * cell + 2] - MIN_SCORE
            if (w <= 0.0) continue
            confident++
            val c = work.offset(cell % n)
            val r = work.offset(cell / n)
            val x = (found[3 * cell] - center) / STEPS
            val y = (found[3 * cell + 1] - center) / STEPS
            // x * (h6 c + h7 r + 1) = h0 c + h1 r + h2, and likewise for y with h3..h5.
            for (axis in 0 until 2) {
                val target = if (axis == 0) x else y
                row.fill(0.0)
                row[3 * axis] = c
                row[3 * axis + 1] = r
                row[3 * axis + 2] = 1.0
                row[6] = -c * target
                row[7] = -r * target
                for (a in 0 until 8) {
                    if (row[a] == 0.0) continue
                    for (b in 0 until 8) m[a * 8 + b] += w * row[a] * row[b]
                    rhs[a] += w * row[a] * target
                }
            }
        }
        if (confident < MIN_PROJECTIVE_CELLS || !solveLinear(m, rhs, h, 8)) return false
        for (cell in 0 until work.cells) {
            val c = work.offset(cell % n)
            val r = work.offset(cell / n)
            val den = h[6] * c + h[7] * r + 1.0
            if (!(den > MIN_PROJECTIVE_DENOMINATOR)) return false
            work.predicted[2 * cell] = (h[0] * c + h[1] * r + h[2]) / den * STEPS + center
            work.predicted[2 * cell + 1] = (h[3] * c + h[4] * r + h[5]) / den * STEPS + center
        }
        return true
    }

    /**
     * Solves the [size] x [size] system [m] x = [b] ([m] row-major, both overwritten) by Gaussian
     * elimination with partial pivoting into [x]; returns false if [m] is (nearly) singular.
     */
    private fun solveLinear(m: DoubleArray, b: DoubleArray, x: DoubleArray, size: Int): Boolean {
        var scale = 0.0
        for (v in m) scale = maxOf(scale, abs(v))
        if (!(scale > 0.0)) return false
        for (col in 0 until size) {
            var pivot = col
            for (r in col + 1 until size) if (abs(m[r * size + col]) > abs(m[pivot * size + col])) pivot = r
            if (abs(m[pivot * size + col]) < 1e-12 * scale) return false
            if (pivot != col) {
                for (k in 0 until size) {
                    val t = m[col * size + k]
                    m[col * size + k] = m[pivot * size + k]
                    m[pivot * size + k] = t
                }
                val t = b[col]
                b[col] = b[pivot]
                b[pivot] = t
            }
            for (r in col + 1 until size) {
                val f = m[r * size + col] / m[col * size + col]
                if (f == 0.0) continue
                for (k in col until size) m[r * size + k] -= f * m[col * size + k]
                b[r] -= f * b[col]
            }
        }
        for (r in size - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until size) s -= m[r * size + k] * x[k]
            x[r] = s / m[r * size + r]
        }
        return x.all { it.isFinite() }
    }

    /**
     * Fits an affine map from grid position (column, row) to the found centers, weighting each cell
     * by how far its score exceeds [MIN_SCORE], and writes the predicted center of every cell as
     * (x, y) pairs to [Workspace.predicted]. Returns false if too few cells were found confidently.
     */
    private fun predictAffine(found: DoubleArray, work: Workspace): Boolean {
        val n = work.n
        // Weighted normal equations for the basis [1, column offset, row offset] (offsets from the face center).
        val m = work.normal
        val bx = work.rhsX
        val by = work.rhsY
        m.fill(0.0)
        bx.fill(0.0)
        by.fill(0.0)
        var confident = 0
        for (cell in 0 until work.cells) {
            val w = found[3 * cell + 2] - MIN_SCORE
            if (w <= 0.0) continue
            confident++
            for (a in 0 until 3) {
                val fa = basis(work, cell, a)
                for (b in 0 until 3) m[a * 3 + b] += w * fa * basis(work, cell, b)
                bx[a] += w * fa * found[3 * cell]
                by[a] += w * fa * found[3 * cell + 1]
            }
        }
        if (confident < work.minConfident) return false
        val ax = work.coefX
        val ay = work.coefY
        if (!solve3(m, bx, ax) || !solve3(m, by, ay)) return false
        for (cell in 0 until work.cells) {
            val c = work.offset(cell % n)
            val r = work.offset(cell / n)
            work.predicted[2 * cell] = ax[0] + ax[1] * c + ax[2] * r
            work.predicted[2 * cell + 1] = ay[0] + ay[1] * c + ay[2] * r
        }
        return true
    }

    /** Basis function [k] of the affine fit ([1, column offset, row offset]) at [cell]. */
    private fun basis(work: Workspace, cell: Int, k: Int): Double = when (k) {
        0 -> 1.0
        1 -> work.offset(cell % work.n)
        else -> work.offset(cell / work.n)
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
    private fun otsuThreshold(feature: IntArray, work: Workspace): Int {
        val hist = work.histogram
        val side = work.side
        val inside = work.n * STEPS
        hist.fill(0)
        var total = 0
        for (j in MARGIN until MARGIN + inside) {
            for (i in MARGIN until MARGIN + inside) {
                hist[feature[j * side + i]]++
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
