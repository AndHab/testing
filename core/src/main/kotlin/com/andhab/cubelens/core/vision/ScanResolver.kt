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
 * photographed separately, under its own exposure and white balance, and one cube's orange can look
 * like another cube's red. The resolver instead uses what is known about the whole cube:
 *
 *  1. The six centers are matched to the six colors with an optimal assignment (Hungarian) against
 *     [LiveClassifier.reference], so even an unusual center reading cannot produce duplicate faces.
 *  2. Each scan is placed on the face whose standard color ([ColorScheme.STANDARD]) its center has.
 *  3. All 48 non-center stickers are classified jointly, seeded from the center samples: each color
 *     gets exactly nine stickers (balanced optimal assignment, 8 slots per color besides the center).
 *     Between assignments the resolver fits a lighting model, linear RGB of a sticker = per-face
 *     channel gains (exposure and white balance of that photo, von Kries) x per-color cluster mean,
 *     by alternating least squares, and compares colors after undoing each face's gains. A few
 *     rounds converge.
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
    private const val GAIN_PRIOR = 0.02 // weight pulling face gains towards 1, in squared linear units
    private const val MIN_GAIN = 0.2
    private const val MAX_GAIN = 5.0
    private const val SWAP_CANDIDATES = 12
    private const val MAX_SWAP_COST = 40.0

    private val scheme = ColorScheme.STANDARD
    private val colors = CubeColor.entries

    /**
     * [scans] are the six faces in any order, each nine samples row-major as seen on screen, each
     * captured at any rotation. Centers decide which face each scan is (standard color scheme: white
     * up, green front, red right); all 54 stickers are then classified jointly (each color exactly
     * nine times) and face rotations are fixed automatically when the scan doesn't form a valid cube.
     * Faces scanned in the reference orientation (see the class documentation) are never re-rotated
     * when the reading is ambiguous; stickers whose color depends on the orientation are uncertain.
     */
    fun resolve(scans: List<List<StickerSample>>): ScanAnalysis {
        if (scans.size != 6 || scans.any { it.size != 9 }) return malformed(scans)
        val classification = classify(scans)
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
            ScanAnalysis(raw, raw, Face.entries.associateWith { 0 }, false, uncertainRaw)
        } else {
            val rotations = oriented.rotations
            val uncertain = uncertainRaw.mapTo(sortedSetOf()) { OrientationFixer.destinationOf(it, rotations.getValue(Facelets.faceOf(it))) }
            uncertain += oriented.ambiguous
            ScanAnalysis(raw, oriented.colors, rotations, true, uncertain)
        }
    }

    /** Outcome of the joint classification, before orientation fixing and repair. */
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
     * Steps 1 to 3: places six well-formed scans on their faces and classifies all stickers jointly
     * (nine per color) under a per-face lighting model.
     */
    internal fun classify(scans: List<List<StickerSample>>): Classification {
        require(scans.size == 6 && scans.all { it.size == 9 }) { "Need six scans of nine samples" }
        val linear = Array(Facelets.COUNT) { DoubleArray(3) }
        placeScans(scans, linear)

        val model = LightingModel(linear)
        val assigned = IntArray(Facelets.COUNT) { -1 }
        for (f in Face.entries) assigned[Facelets.center(f)] = scheme.colorOf(f).ordinal
        model.seedFromCenters()
        var cost = model.costs()
        repeat(MAX_ROUNDS) {
            val changed = assignBalanced(cost, assigned)
            if (!changed) return Classification(List(Facelets.COUNT) { colors[assigned[it]] }, cost)
            model.fit(assigned)
            cost = model.costs()
        }
        return Classification(List(Facelets.COUNT) { colors[assigned[it]] }, cost)
    }

    /** Matches centers to colors and fills [linear] (facelet order) with each scan on its face. */
    private fun placeScans(scans: List<List<StickerSample>>, linear: Array<DoubleArray>) {
        val centerCost = DoubleArray(36)
        for (k in 0 until 6) {
            val center = scans[k][4]
            val lab = ColorMath.srgbToLab(center.r, center.g, center.b)
            for (c in 0 until 6) centerCost[k * 6 + c] = ColorMath.deltaE(lab, LiveClassifier.reference.getValue(colors[c])).toDouble()
        }
        val colorOfScan = Assignment.solve(centerCost, 6, 6)
        for (k in 0 until 6) {
            val f = scheme.faceOf(colors[colorOfScan[k]]).ordinal
            for (p in 0 until 9) {
                val s = scans[k][p]
                val target = linear[f * 9 + p]
                target[0] = ColorMath.srgbToLinear(s.r)
                target[1] = ColorMath.srgbToLinear(s.g)
                target[2] = ColorMath.srgbToLinear(s.b)
            }
        }
    }

    /**
     * Balanced assignment of the 48 non-center stickers to colors, 8 per color (9 with the center).
     * Updates [assigned] and returns whether anything changed.
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
            val color = columns[r] / 8
            if (assigned[stickers[r]] != color) {
                assigned[stickers[r]] = color
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
            if (sample == null) scheme.colorOf(Facelets.faceOf(i)) else LiveClassifier.classify(StickerSample.of(sample.r, sample.g, sample.b))
        }
        return ScanAnalysis(raw, raw, Face.entries.associateWith { 0 }, false, (0 until Facelets.COUNT).toSet())
    }

    private val NON_CENTERS: IntArray = (0 until Facelets.COUNT).filter { it % 9 != 4 }.toIntArray()

    /**
     * Linear RGB of sticker i on face f with color c is modeled as gain[f] * mean[c] per channel.
     */
    private class LightingModel(private val linear: Array<DoubleArray>) {
        val gain = Array(6) { DoubleArray(3) { 1.0 } }
        val mean = Array(6) { DoubleArray(3) }

        /** Each color's mean starts as the center sticker of its face, with neutral gains. */
        fun seedFromCenters() {
            for (f in Face.entries) {
                linear[Facelets.center(f)].copyInto(mean[scheme.colorOf(f).ordinal])
            }
        }

        /** Alternating least squares for gains and means given the color of every sticker. */
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
                for (f in 0 until 6) {
                    for (ch in 0 until 3) {
                        var num = GAIN_PRIOR
                        var den = GAIN_PRIOR
                        for (p in 0 until 9) {
                            val i = f * 9 + p
                            val m = mean[assigned[i]][ch]
                            num += m * linear[i][ch]
                            den += m * m
                        }
                        gain[f][ch] = (num / den).coerceIn(MIN_GAIN, MAX_GAIN)
                    }
                }
                // Fix the gauge: per channel, the gains' geometric mean is 1.
                for (ch in 0 until 3) {
                    var logSum = 0.0
                    for (f in 0 until 6) logSum += ln(gain[f][ch])
                    val norm = exp(logSum / 6.0)
                    for (f in 0 until 6) gain[f][ch] /= norm
                }
            }
        }

        /** cost[i * 6 + c]: distance of sticker i (with its face's gains undone) to color c's mean. */
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
