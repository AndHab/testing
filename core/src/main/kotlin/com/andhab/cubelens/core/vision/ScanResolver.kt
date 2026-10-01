package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import kotlin.math.exp
import kotlin.math.ln

/**
 * Turns all scanned faces into a validated cube.
 *
 * Classifying stickers one by one against fixed reference colors is fragile: every face is
 * photographed separately, under its own exposure and white balance, one cube's orange can look
 * like another cube's red, and knock-off cubes come in pastel or otherwise unusual shades. The
 * resolver instead uses what is known about the whole cube:
 *
 *  1. All 54 stickers are clustered jointly into six colors, one per scan center (the six centers of
 *     a cube are six different colors): each color gets exactly nine stickers (balanced optimal
 *     assignment, 8 slots per color besides the center), seeded from the center samples. Between
 *     assignments the resolver fits a lighting model, linear RGB of a sticker = per-scan channel
 *     gains (exposure and white balance of that photo, von Kries) x per-color cluster mean, by
 *     alternating least squares, and compares colors after undoing each scan's gains. A few rounds
 *     converge. Nothing in this step depends on which color is which.
 *  2. The six clusters are named by [PaletteLabeler] from their lighting-compensated colors, by how
 *     they relate to each other (the most white-like one is white, the others follow the order of
 *     the color wheel), not by distance to fixed reference colors, so pastel and other unusual
 *     palettes are named correctly too. Their colors also give [ScanAnalysis.palette].
 *  3. Placement: each scan goes on the face that its center color has in [ColorScheme.STANDARD]
 *     (any scan order works). If that doesn't give a valid cube and the scan flow said which face
 *     each scan was meant to be (`scanPositions`), each scan goes there instead and the centers'
 *     colors define the scheme: that resolves cubes with other color arrangements, such as the
 *     Japanese scheme ([ScanAnalysis.placement] tells which placement was used).
 *  4. If the colors don't form a valid cube, [OrientationFixer] searches for face rotations; if that
 *     fails too, swapping the colors of two low-confidence stickers is tried (cheapest swap first).
 *  5. Stickers whose best and second-best color are close are reported as [ScanAnalysis.uncertain],
 *     and so are stickers whose color depends on how a face was held: when another combination of
 *     face rotations also gives a valid cube, every sticker that differs between the two readings.
 *
 * Face orientation: when several rotations are valid, the reading with the fewest rotated faces is
 * returned, which is correct when each face was scanned in its reference orientation (side faces
 * with white on top, white with blue on top, yellow with green on top; see [OrientationFixer]). Scan
 * flows should instruct users to hold the cube that way; faces held otherwise still resolve
 * automatically whenever the reading is unambiguous, and are flagged as uncertain when it is not.
 *
 * Only the samples' sRGB values are used (their Lab is recomputed), and malformed input never throws:
 * it yields a result with `isValid == false`.
 */
object ScanResolver {

    /** A sticker is uncertain when its second-best color costs less than this much more (deltaE units). */
    const val UNCERTAIN_MARGIN = 6f

    private const val MAX_ROUNDS = 10
    private const val MODEL_ROUNDS = 4
    private const val GAIN_PRIOR = 0.02 // weight pulling scan gains towards 1, in squared linear units
    private const val MIN_GAIN = 0.2
    private const val MAX_GAIN = 5.0
    private const val SWAP_CANDIDATES = 12
    private const val MAX_SWAP_COST = 40.0
    private const val MIN_COLOR_SEPARATION = 4f // deltaE between the cluster colors of a real cube

    private val scheme = ColorScheme.STANDARD

    /**
     * [scans] are the six faces in any order, each nine samples row-major as seen on screen, each
     * captured at any rotation. All 54 stickers are classified jointly (each color exactly nine
     * times), and the colors are named by how they relate to each other, so cubes with pastel or
     * other unusual sticker colors work too.
     *
     * Scans are placed on faces by their center colors (standard color scheme: white up, green front,
     * red right). When that doesn't give a valid cube and [scanPositions] is given, scan `i` is placed
     * on face `scanPositions[i]` instead (the face the scan flow asked for at that step), which
     * resolves cubes whose colors are arranged differently; [ScanAnalysis.placement] says which
     * placement the result uses. [scanPositions] must name six different faces; otherwise it is ignored.
     *
     * Face rotations are fixed automatically when the scan doesn't form a valid cube. Faces scanned in
     * the reference orientation (see the class documentation) are never re-rotated when the reading is
     * ambiguous; stickers whose color depends on the orientation are uncertain. When no placement
     * gives a valid cube, the result is the center-color placement with `isValid == false`.
     */
    fun resolve(scans: List<List<StickerSample>>, scanPositions: List<Face>? = null): ScanAnalysis {
        if (scans.size != 6 || scans.any { it.size != 9 }) return malformed(scans)
        val clusters = cluster(scans)
        val measured = clusters.robustColors()
        val labels = PaletteLabeler.labelAll(measured)
        val palette = PaletteEstimator.estimate(labels.indices.associate { labels[it] to measured[it] })
        val byCenters = facesByCenterColor(labels)
        if (!areDistinct(measured)) {
            // E.g. the same face scanned twice: the clusters are arbitrary, so is any cube built from them.
            val raw = clusters.classification(labels, byCenters).colors
            return ScanAnalysis(raw, raw, Face.entries.associateWith { 0 }, false, (0 until Facelets.COUNT).toSet(), palette)
        }

        val first = analyze(clusters.classification(labels, byCenters), Placement.CENTER_COLORS, palette)
        if (first.isValid) return first
        val positions = scanPositions?.takeIf { it.size == 6 && it.toSet().size == 6 } ?: return first
        if (positions == byCenters) return first
        val second = analyze(clusters.classification(labels, positions), Placement.SCAN_ORDER, palette)
        return if (second.isValid) second else first
    }

    /** Orientation fixing, repair and uncertainty for one placement of the scans. */
    private fun analyze(classification: Classification, placement: Placement, palette: CubePaletteEstimate): ScanAnalysis {
        val uncertainRaw = classification.uncertain().toMutableSet()
        var raw = classification.colors
        var oriented = OrientationFixer.orient(raw)
        if (oriented == null) {
            val repaired = repairBySwap(classification)
            if (repaired != null) {
                raw = repaired.first
                oriented = repaired.second
                uncertainRaw += repaired.third
            }
        }
        return if (oriented == null) {
            ScanAnalysis(raw, raw, Face.entries.associateWith { 0 }, false, uncertainRaw, palette, placement)
        } else {
            val rotations = oriented.rotations
            val uncertain = uncertainRaw.mapTo(sortedSetOf()) { OrientationFixer.destinationOf(it, rotations.getValue(Facelets.faceOf(it))) }
            uncertain += oriented.ambiguous
            ScanAnalysis(raw, oriented.colors, rotations, true, uncertain, palette, placement)
        }
    }

    /**
     * Whether the six cluster colors (linear RGB) are clearly different from each other, as the six
     * colors of a real cube are (at least about 15 deltaE apart even on pastel cubes).
     */
    private fun areDistinct(colors: List<DoubleArray>): Boolean {
        val labs = colors.map { ColorMath.linearToLab(it[0], it[1], it[2]) }
        for (i in labs.indices) {
            for (j in i + 1 until labs.size) {
                if (!(ColorMath.deltaE(labs[i], labs[j]) >= MIN_COLOR_SEPARATION)) return false
            }
        }
        return true
    }

    /** The face of each scan when placed by its center's color in the standard scheme. */
    private fun facesByCenterColor(labels: List<CubeColor>): List<Face> = labels.map { scheme.faceOf(it) }

    /** Outcome of the joint classification for one placement, before orientation fixing and repair. */
    internal class Classification(
        /** Color of every facelet, scans placed on their faces as captured. */
        val colors: List<CubeColor>,
        /** cost[i * 6 + c]: distance of facelet i to color c (by ordinal) after lighting compensation. */
        val cost: DoubleArray,
    ) {
        /** Second-best cost minus own cost per facelet (infinite for centers); negative if forced by the quotas. */
        val margin: DoubleArray = DoubleArray(Facelets.COUNT) { i ->
            if (i % 9 == 4) {
                Double.POSITIVE_INFINITY
            } else {
                val own = colors[i].ordinal
                var second = Double.POSITIVE_INFINITY
                for (c in 0 until 6) if (c != own) second = minOf(second, cost[i * 6 + c])
                second - cost[i * 6 + own]
            }
        }

        fun uncertain(): Set<Int> = (0 until Facelets.COUNT).filter { margin[it] < UNCERTAIN_MARGIN }.toSet()
    }

    /**
     * Steps 1 to 3 for well-formed scans with the center-color placement: classifies all stickers
     * jointly (nine per color) under a per-scan lighting model, names the colors and places each scan
     * on the face of its center color.
     */
    internal fun classify(scans: List<List<StickerSample>>): Classification {
        require(scans.size == 6 && scans.all { it.size == 9 }) { "Need six scans of nine samples" }
        val clusters = cluster(scans)
        val labels = PaletteLabeler.labelAll(clusters.robustColors())
        return clusters.classification(labels, facesByCenterColor(labels))
    }

    /**
     * Step 1: clusters the stickers of six well-formed scans into six colors, cluster k being the color
     * of scan k's center.
     */
    private fun cluster(scans: List<List<StickerSample>>): Clusters {
        val linear = Array(Facelets.COUNT) { i ->
            val s = scans[i / 9][i % 9]
            doubleArrayOf(ColorMath.srgbToLinear(s.r), ColorMath.srgbToLinear(s.g), ColorMath.srgbToLinear(s.b))
        }
        val model = LightingModel(linear)
        val assigned = IntArray(Facelets.COUNT) { -1 }
        for (k in 0 until 6) assigned[k * 9 + 4] = k
        model.seedFromCenters()
        var cost = model.costs()
        for (round in 0 until MAX_ROUNDS) {
            if (!assignBalanced(cost, assigned)) break
            model.fit(assigned)
            cost = model.costs()
        }
        return Clusters(linear, assigned, cost, model.gain)
    }

    /**
     * The joint classification in scan order: sticker index `k * 9 + p` is sticker p of scan k, and
     * cluster k is the color of scan k's center.
     */
    private class Clusters(
        /** Linear RGB of every sticker. */
        val linear: Array<DoubleArray>,
        /** Cluster of every sticker. */
        val cluster: IntArray,
        /** cost[i * 6 + k]: distance of sticker i to cluster k after lighting compensation. */
        val cost: DoubleArray,
        /** Per-scan channel gains of the lighting model. */
        val gain: Array<DoubleArray>,
    ) {
        /**
         * Each cluster's color in the common light of the lighting model: the per-channel median of
         * its stickers with their scan's gains undone (robust to a highlight or a misread sticker).
         */
        fun robustColors(): List<DoubleArray> = List(6) { k ->
            val members = (0 until Facelets.COUNT).filter { cluster[it] == k }
            DoubleArray(3) { ch ->
                val values = members.map { linear[it][ch] / gain[it / 9][ch] }.sorted()
                if (values.isEmpty()) 0.0 else values[values.size / 2]
            }
        }

        /** Facelet-order colors and costs with cluster k named [labels]`[k]` and scan k placed on [faces]`[k]`. */
        fun classification(labels: List<CubeColor>, faces: List<Face>): Classification {
            val clusterOfColor = IntArray(6)
            for (k in 0 until 6) clusterOfColor[labels[k].ordinal] = k
            val placed = arrayOfNulls<CubeColor>(Facelets.COUNT)
            val placedCost = DoubleArray(Facelets.COUNT * 6)
            for (k in 0 until 6) {
                val f = faces[k].ordinal
                for (p in 0 until 9) {
                    val i = k * 9 + p
                    val j = f * 9 + p
                    placed[j] = labels[cluster[i]]
                    for (c in 0 until 6) placedCost[j * 6 + c] = cost[i * 6 + clusterOfColor[c]]
                }
            }
            return Classification(placed.map { it!! }, placedCost)
        }
    }

    /**
     * Balanced assignment of the 48 non-center stickers to clusters, 8 per cluster (9 with the
     * center). Updates [assigned] and returns whether anything changed.
     */
    private fun assignBalanced(cost: DoubleArray, assigned: IntArray): Boolean {
        val stickers = NON_CENTERS
        val n = stickers.size
        val matrix = DoubleArray(n * n)
        for (r in 0 until n) {
            val i = stickers[r]
            for (col in 0 until n) matrix[r * n + col] = cost[i * 6 + col / 8]
        }
        val columns = Assignment.solve(matrix, n, n)
        var changed = false
        for (r in 0 until n) {
            val cluster = columns[r] / 8
            if (assigned[stickers[r]] != cluster) {
                assigned[stickers[r]] = cluster
                changed = true
            }
        }
        return changed
    }

    /**
     * Tries exchanging the colors of two low-margin stickers (keeps nine of each color), cheapest
     * first. Returns the new raw colors, their oriented version and the swapped facelets.
     */
    private fun repairBySwap(classification: Classification): Triple<List<CubeColor>, OrientationFixer.Orientation, Set<Int>>? {
        val raw = classification.colors
        val cost = classification.cost
        val candidates = NON_CENTERS.sortedBy { classification.margin[it] }.take(SWAP_CANDIDATES)
        val swaps = mutableListOf<Triple<Int, Int, Double>>()
        for (x in candidates.indices) {
            for (y in x + 1 until candidates.size) {
                val i = candidates[x]
                val j = candidates[y]
                val ci = raw[i].ordinal
                val cj = raw[j].ordinal
                if (ci == cj) continue
                val delta = cost[i * 6 + cj] + cost[j * 6 + ci] - cost[i * 6 + ci] - cost[j * 6 + cj]
                if (delta <= MAX_SWAP_COST) swaps += Triple(i, j, delta)
            }
        }
        swaps.sortBy { it.third }
        for ((i, j, _) in swaps) {
            val candidate = raw.toMutableList()
            candidate[i] = raw[j]
            candidate[j] = raw[i]
            val oriented = OrientationFixer.orient(candidate) ?: continue
            return Triple(candidate, oriented, setOf(i, j))
        }
        return null
    }

    /** Best-effort result for input that is not six scans of nine: per-sticker guesses, all uncertain. */
    private fun malformed(scans: List<List<StickerSample>>): ScanAnalysis {
        val raw = List(Facelets.COUNT) { i ->
            val sample = scans.getOrNull(i / 9)?.getOrNull(i % 9)
            if (sample == null) scheme.colorOf(Facelets.faceOf(i)) else LiveClassifier.classify(StickerSample.of(sample.r.coerceIn(0, 255), sample.g.coerceIn(0, 255), sample.b.coerceIn(0, 255)))
        }
        return ScanAnalysis(raw, raw, Face.entries.associateWith { 0 }, false, (0 until Facelets.COUNT).toSet())
    }

    private val NON_CENTERS: IntArray = (0 until Facelets.COUNT).filter { it % 9 != 4 }.toIntArray()

    /**
     * Linear RGB of sticker i of scan k with color (cluster) c is modeled as gain[k] * mean[c] per channel.
     */
    private class LightingModel(private val linear: Array<DoubleArray>) {
        val gain = Array(6) { DoubleArray(3) { 1.0 } }
        val mean = Array(6) { DoubleArray(3) }

        /** Each cluster's mean starts as its scan's center sticker, with neutral gains. */
        fun seedFromCenters() {
            for (k in 0 until 6) linear[k * 9 + 4].copyInto(mean[k])
        }

        /** Alternating least squares for gains and means given the cluster of every sticker. */
        fun fit(assigned: IntArray) {
            repeat(MODEL_ROUNDS) {
                for (c in 0 until 6) {
                    for (ch in 0 until 3) {
                        var num = 0.0
                        var den = 0.0
                        for (i in 0 until Facelets.COUNT) {
                            if (assigned[i] != c) continue
                            val g = gain[i / 9][ch]
                            num += g * linear[i][ch]
                            den += g * g
                        }
                        if (den > 0.0) mean[c][ch] = num / den
                    }
                }
                for (k in 0 until 6) {
                    for (ch in 0 until 3) {
                        var num = GAIN_PRIOR
                        var den = GAIN_PRIOR
                        for (p in 0 until 9) {
                            val i = k * 9 + p
                            val m = mean[assigned[i]][ch]
                            num += m * linear[i][ch]
                            den += m * m
                        }
                        gain[k][ch] = (num / den).coerceIn(MIN_GAIN, MAX_GAIN)
                    }
                }
                // Fix the gauge: per channel, the gains' geometric mean is 1.
                for (ch in 0 until 3) {
                    var logSum = 0.0
                    for (k in 0 until 6) logSum += ln(gain[k][ch])
                    val norm = exp(logSum / 6.0)
                    for (k in 0 until 6) gain[k][ch] /= norm
                }
            }
        }

        /** cost[i * 6 + c]: distance of sticker i (with its scan's gains undone) to cluster c's mean. */
        fun costs(): DoubleArray {
            val means = Array(6) { c -> ColorMath.linearToLab(mean[c][0], mean[c][1], mean[c][2]) }
            val cost = DoubleArray(Facelets.COUNT * 6)
            for (i in 0 until Facelets.COUNT) {
                val g = gain[i / 9]
                val lab = ColorMath.linearToLab(linear[i][0] / g[0], linear[i][1] / g[1], linear[i][2] / g[2])
                for (c in 0 until 6) cost[i * 6 + c] = ColorMath.deltaE(lab, means[c]).toDouble()
            }
            return cost
        }
    }
}
