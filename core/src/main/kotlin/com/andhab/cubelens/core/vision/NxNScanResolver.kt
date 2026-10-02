package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNError
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNValidator

/**
 * [ScanResolver.resolve] for N×N cubes other than 3x3 (whose reviewed 3x3 path the public function
 * keeps using). See that function for the contract; the steps are those of the 3x3 resolver:
 *
 *  1. Joint clustering of all `6 * n * n` stickers into six colors of exactly `n * n` each, under the
 *     per-scan lighting model ([LightingModel]); the assignment is [GroupAssignment] (hundreds of
 *     stickers). Odd sizes pin the fixed centers to their scans' clusters and seed from them, then
 *     undo swapped center pairs as the 3x3 resolver does. Even sizes start from several seeds
 *     ([JointClustering.clusterFromScratch]) and try the best few clusterings in turn.
 *  2. Naming by [PaletteLabeler.labelAll] and the palette by [PaletteEstimator], as for 3x3.
 *  3. Placement: odd sizes by center color in [ColorScheme.STANDARD], else by scan positions; even
 *     sizes by scan positions (default: the guided order F, R, B, L, U, D).
 *  4. Orientation by [NxNOrientationFixer]; if no face rotations give a valid cube, the exchange of
 *     two close calls ([JointClustering.repairBySwap]) and, from 4x4 on, the correction of one
 *     confidently misread sticker ([repairMisread]) are tried.
 *  5. Uncertain stickers: close calls, stickers that match no color well ([poorFits]), repaired
 *     stickers, orientation ambiguity, and the stickers that differ in another valid reading that
 *     explains the photos about as well ([alternativeReadings], and for even sizes another
 *     clustering, [withRivalReadings]).
 */
internal object NxNScanResolver {

    const val MIN_SIZE = NxNGeometry.MIN_SIZE
    const val MAX_SIZE = NxNGeometry.MAX_SIZE

    /** The app's guided scan order: front, right, back, left (turning the cube), then up and down. */
    val GUIDED_ORDER: List<Face> = listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D)

    /** Even sizes: how many of the best distinct clusterings are tried for a valid cube. */
    private const val CLUSTERINGS_TRIED = 3

    /**
     * [repairMisread] runs from this size on: bigger cubes have many more pieces that validation
     * checks, so a single wrong correction does not pass for a valid cube, and many more stickers
     * that the sampler can misread.
     */
    private const val MISREAD_REPAIR_MIN_SIZE = 4

    /** [misreadCorrections]: suspects considered (the smallest margins first); [repairMisread]: corrections tried at most. */
    private const val MISREAD_FORCED = 4
    private const val MISREAD_TRIALS = 300

    /**
     * [closeCallExchanges]: close calls considered (the closest first) and the largest cost of
     * exchanging two of them (deltaE); [alternativeReadings]: readings tried at most per set of colors.
     */
    private const val ALTERNATIVE_CANDIDATES = 8
    private const val ALTERNATIVE_MAX_COST = 2.0 * ScanResolver.UNCERTAIN_MARGIN
    private const val ALTERNATIVE_TRIALS = 300

    /** [poorFits]: distance (CIEDE2000) to the nearest color beyond which a sticker matches no color well. */
    private const val POOR_FIT = 10.0

    /** [withRivalReadings]: rival clusterings costing up to this many times the chosen one count as about as good. */
    private const val RIVAL_COST_RATIO = 1.25

    private val scheme = ColorScheme.STANDARD

    /** Cube layout for one size. */
    private class Layout(val n: Int) {
        val perFace = n * n
        val count = 6 * perFace

        /** Position of the fixed center within a face (odd sizes), or -1. */
        val center = if (n % 2 == 1) (n / 2) * n + n / 2 else -1
    }

    fun resolve(n: Int, scans: List<List<StickerSample>>, scanPositions: List<Face>?): NxNScanAnalysis {
        val layout = Layout(n)
        if (scans.size != 6 || scans.any { it.size != layout.perFace }) return malformed(layout, scans)
        val samples = scans.flatten()
        val linear = JointClustering.linearOf(scans)
        val positions = scanPositions?.takeIf { it.size == 6 && it.toSet().size == 6 }
        return if (layout.center >= 0) resolveOdd(layout, linear, positions) else resolveEven(layout, samples, linear, positions)
    }

    /**
     * Steps 1 to 3 for well-formed scans, as [ScanResolver.classify] does for 3x3: the joint
     * classification (for even sizes the clustering that explains the stickers best) placed by
     * center color (odd sizes) or by [scanPositions] (even sizes; default: the guided order), before
     * orientation fixing and repair.
     */
    fun classify(n: Int, scans: List<List<StickerSample>>, scanPositions: List<Face>? = null): Classification {
        val layout = Layout(n)
        require(scans.size == 6 && scans.all { it.size == layout.perFace }) { "Need six scans of ${layout.perFace} samples" }
        val linear = JointClustering.linearOf(scans)
        if (layout.center >= 0) {
            val clusters = clusterWithCenters(layout, linear)
            val labels = Named(clusters).labels
            return clusters.classification(labels, labels.map { scheme.faceOf(it) }, layout.center)
        }
        val quota = IntArray(6) { layout.perFace }
        val clusters = JointClustering.clusterFromScratch(scans.flatten(), linear, 6, layout.perFace, quota, quota).first()
        val faces = scanPositions?.takeIf { it.size == 6 && it.toSet().size == 6 } ?: GUIDED_ORDER
        return clusters.classification(Named(clusters).labels, faces, -1)
    }

    /** Odd sizes: clusters seeded from the fixed centers, placed by center color or else by scan position. */
    private fun resolveOdd(layout: Layout, linear: Array<DoubleArray>, positions: List<Face>?): NxNScanAnalysis {
        val clusters = clusterWithCenters(layout, linear)
        val named = Named(clusters)
        val byCenters = named.labels.map { scheme.faceOf(it) }
        if (!named.plausible) return implausible(layout, clusters.classification(named.labels, byCenters, layout.center), named.palette, Placement.CENTER_COLORS)
        val first = analyze(layout, clusters.classification(named.labels, byCenters, layout.center), Placement.CENTER_COLORS, named.palette)
        if (first.isValid || positions == null || positions == byCenters) return first
        val second = analyze(layout, clusters.classification(named.labels, positions, layout.center), Placement.SCAN_ORDER, named.palette)
        return if (second.isValid) second else first
    }

    /** Even sizes: clusters from scratch (best few tried in turn), placed by scan position. */
    private fun resolveEven(layout: Layout, samples: List<StickerSample>, linear: Array<DoubleArray>, positions: List<Face>?): NxNScanAnalysis {
        val faces = positions ?: GUIDED_ORDER
        val quota = IntArray(6) { layout.perFace }
        val candidates = JointClustering.clusterFromScratch(samples, linear, 6, layout.perFace, quota, quota).take(CLUSTERINGS_TRIED)
        var first: NxNScanAnalysis? = null
        for ((k, clusters) in candidates.withIndex()) {
            val named = Named(clusters)
            val classification = clusters.classification(named.labels, faces, -1)
            val analysis = if (named.plausible) {
                analyze(layout, classification, Placement.SCAN_ORDER, named.palette)
            } else {
                implausible(layout, classification, named.palette, Placement.SCAN_ORDER)
            }
            if (analysis.isValid) return withRivalReadings(layout, analysis, clusters, candidates.filterIndexed { i, _ -> i != k }, faces)
            if (first == null) first = analysis
        }
        return first!!
    }

    /**
     * Even sizes: [analysis] (valid, from the clustering [chosen]) with every sticker flagged that a
     * rival clustering reads differently, if the rival explains the stickers about as well
     * ([RIVAL_COST_RATIO]) and its colors also form a valid cube as captured or with faces turned.
     * Without fixed centers to anchor them, clusterings can tie when the per-photo lighting model can
     * explain the photos in more than one way: on a 2x2 cube, four stickers per photo, with very pale
     * colors. Bigger cubes have far too many pieces for a wrong clustering to pass validation.
     */
    private fun withRivalReadings(layout: Layout, analysis: NxNScanAnalysis, chosen: Clusters, rivals: List<Clusters>, faces: List<Face>): NxNScanAnalysis {
        val flagged = sortedSetOf<Int>()
        for (rival in rivals) {
            if (rival.totalCost() > RIVAL_COST_RATIO * chosen.totalCost()) continue
            val named = Named(rival)
            if (!named.plausible) continue
            val reading = NxNOrientationFixer.orient(layout.n, rival.classification(named.labels, faces, -1).colors) ?: continue
            for (k in reading.colors.indices) if (reading.colors[k] != analysis.colors[k]) flagged += k
            flagged += reading.ambiguous
        }
        if (flagged.isEmpty()) return analysis
        return analysis.copy(uncertain = (analysis.uncertain + flagged).toSortedSet())
    }

    /** The clusters named, their palette, and whether they look like the six colors of a cube. */
    private class Named(clusters: Clusters) {
        val measured = clusters.robustColors()
        val labels = PaletteLabeler.labelAll(measured)
        private val byColor = labels.indices.associate { labels[it] to measured[it] }
        val palette = PaletteEstimator.estimate(byColor)
        val plausible = JointClustering.areDistinct(measured) && JointClustering.isColored(byColor)
    }

    /**
     * Odd sizes: the stickers clustered with each fixed center pinned to its scan's cluster (cluster k
     * is the color of scan k's center), seeded from the centers; then pairs of centers that each fit
     * the other's cluster better are undone, as in the 3x3 resolver.
     */
    private fun clusterWithCenters(layout: Layout, linear: Array<DoubleArray>): Clusters {
        val perFace = layout.perFace
        val movable = (0 until layout.count).filter { it % perFace != layout.center }.toIntArray()
        val quota = IntArray(6) { perFace - 1 }
        val model = LightingModel(linear, 6, perFace)
        val assigned = IntArray(layout.count) { -1 }
        for (k in 0 until 6) assigned[k * perFace + layout.center] = k
        model.seedFromPosition(layout.center)
        val start = JointClustering.converge(model, assigned, movable, quota, quota)
        return JointClustering.undoSwappedCenters(start, layout.center, movable) { trial, swapped ->
            JointClustering.converge(trial, swapped, movable, quota, quota)
        }
    }

    /**
     * Orientation fixing, repair and uncertainty for one placement of the scans: the colors as
     * classified if some face rotations make them a valid cube; else the cheapest exchange of two
     * close calls that does ([JointClustering.repairBySwap]); else, from 4x4 on, the cheapest
     * correction of one confidently misread sticker ([repairMisread]).
     */
    private fun analyze(layout: Layout, classification: Classification, placement: Placement, palette: CubePaletteEstimate): NxNScanAnalysis {
        val n = layout.n
        val orient = { colors: List<CubeColor> -> NxNOrientationFixer.orient(n, colors) }
        val uncertainRaw = classification.uncertain().toMutableSet()
        uncertainRaw += poorFits(classification)
        var raw = classification.colors
        var oriented = orient(raw)
        if (oriented == null) {
            val repaired = JointClustering.repairBySwap(classification, orient)
                ?: if (n >= MISREAD_REPAIR_MIN_SIZE) repairMisread(classification, orient) else null
            if (repaired != null) {
                raw = repaired.first
                oriented = repaired.second
                uncertainRaw += repaired.third
            }
        }
        if (oriented == null) {
            // The validator always finds a problem here (the search includes the colors as captured); the
            // fallback only guards against the two ever disagreeing.
            val problems = problemsOf(n, raw).ifEmpty { listOf(NxNError("These colors don't form a ${n}×$n cube.")) }
            return NxNScanAnalysis(n, raw, raw, NO_ROTATIONS, false, uncertainRaw.toSortedSet(), palette, placement, problems)
        }
        val rotations = oriented.rotations
        val uncertain = uncertainRaw.mapTo(sortedSetOf()) { i ->
            NxNOrientationFixer.destinationOf(n, i, rotations.getValue(Face.entries[i / layout.perFace]))
        }
        uncertain += oriented.ambiguous
        uncertain += alternativeReadings(classification, raw, oriented, orient)
        return NxNScanAnalysis(n, raw, oriented.colors, rotations, true, uncertain, palette, placement, emptyList())
    }

    /**
     * Stickers that match none of the cube's colors well: even their best color is more than
     * [POOR_FIT] away (after lighting compensation), as for a sample taken partly off its sticker.
     * On rendered scans, stickers read right are within 8 of their color in all but about one case
     * in a thousand (the median is 1).
     */
    private fun poorFits(classification: Classification): List<Int> {
        val fit = Fit(classification, classification.colors)
        return fit.movable.filter { fit.cost(it, fit.shown[it]) > POOR_FIT }
    }

    /**
     * Repairs one confidently misread sticker, e.g. one that the sampler read partly off its tile, so
     * that it shows a neighbour's color: the cheapest of the [misreadCorrections] (at most
     * [MISREAD_TRIALS]) that gives a valid cube. Another sticker can sometimes be corrected instead
     * with an equally valid result (e.g. a center of the same orbit): [alternativeReadings] reports
     * those. Returns the repaired colors, their orientation and the corrected stickers, or null.
     */
    private fun <T : Any> repairMisread(classification: Classification, orient: (List<CubeColor>) -> T?): Triple<List<CubeColor>, T, Set<Int>>? {
        val raw = classification.colors
        for (correction in misreadCorrections(classification, raw).take(MISREAD_TRIALS)) {
            val candidate = correction.applyTo(raw)
            val oriented = orient(candidate) ?: continue
            return Triple(candidate, oriented, correction.stickers.toSet())
        }
        return null
    }

    /**
     * Stickers whose color differs in another valid reading of the scans that explains the photos
     * about as well; indices refer to the oriented colors of [chosen], the preferred valid reading.
     * Other readings are the [closeCallExchanges] and [misreadCorrections] of the colors as classified
     * and, if a repair changed them, also of the repaired [raw] colors (the repair may have picked the
     * wrong one). A confident misread forces stickers of the color it shows into other colors, and
     * the result can be valid whichever sticker showing that color is taken for the misread one: two
     * wings with the same second color can exchange their first colors, two centers of one orbit
     * their colors, and on a 2x2 cube, with only its corners to check, a misread together with a
     * face taken as held at another angle can pass. At most [ALTERNATIVE_TRIALS] readings are tried
     * per set of colors, the cheapest first.
     */
    private fun alternativeReadings(
        classification: Classification,
        raw: List<CubeColor>,
        chosen: NxNOrientationFixer.Orientation,
        orient: (List<CubeColor>) -> NxNOrientationFixer.Orientation?,
    ): Set<Int> {
        val result = sortedSetOf<Int>()
        val bases = if (raw == classification.colors) listOf(raw) else listOf(classification.colors, raw)
        for (base in bases) {
            val corrections = (closeCallExchanges(classification, base) + misreadCorrections(classification, base))
                .distinctBy { it.key }
                .sortedBy { it.cost }
                .take(ALTERNATIVE_TRIALS)
            for (correction in corrections) {
                val other = orient(correction.applyTo(base)) ?: continue
                for (k in base.indices) if (other.colors[k] != chosen.colors[k]) result += k
                result += other.ambiguous
            }
        }
        return result
    }

    /** New colors (by ordinal) for some [stickers], and how much more the classification costs with them. */
    private class Correction(val stickers: IntArray, val colors: IntArray, val cost: Double) {
        /** Identifies the change, whatever order it lists the stickers in. */
        val key: List<Long> = stickers.indices.map { stickers[it].toLong() * JointClustering.COLORS + colors[it] }.sorted()

        fun applyTo(base: List<CubeColor>): List<CubeColor> =
            base.toMutableList().also { for (k in stickers.indices) it[stickers[k]] = CubeColor.entries[colors[k]] }
    }

    /** How well each sticker fits the colors [colors] give it, from the classification's costs. */
    private class Fit(classification: Classification, colors: List<CubeColor>) {
        private val cost = classification.cost
        private val count = JointClustering.COLORS

        /** The color (ordinal) each sticker shows: its cheapest. */
        val shown = IntArray(colors.size)

        /** The cheapest color (ordinal) of each sticker other than the one [colors] give it. */
        val rival = IntArray(colors.size)

        /** Cost of [rival] minus the cost of the sticker's color in [colors]; negative if it shows another color. */
        val margin = DoubleArray(colors.size)

        /** Stickers whose colors may be corrected (not pinned centers). */
        val movable: List<Int> = colors.indices.filter { !classification.isPinned(it) }

        init {
            for (i in colors.indices) {
                val own = colors[i].ordinal
                var best = own
                var second = -1
                for (c in 0 until count) {
                    if (cost(i, c) < cost(i, best)) best = c
                    if (c != own && (second < 0 || cost(i, c) < cost(i, second))) second = c
                }
                shown[i] = best
                rival[i] = second
                margin[i] = cost(i, second) - cost(i, own)
            }
        }

        fun cost(i: Int, color: Int): Double = cost[i * count + color]
    }

    /**
     * Exchanges of the colors of two close calls of [colors] ([ScanResolver.UNCERTAIN_MARGIN], the
     * [ALTERNATIVE_CANDIDATES] closest) that could each be the other's color (the exchange costs at
     * most [ALTERNATIVE_MAX_COST]). With the faces held differently such a reading can differ in more
     * places than the two stickers (on a 2x2 cube, which has only its corners to check).
     */
    private fun closeCallExchanges(classification: Classification, colors: List<CubeColor>): List<Correction> {
        val fit = Fit(classification, colors)
        val closeCalls = fit.movable.filter { fit.margin[it] < ScanResolver.UNCERTAIN_MARGIN }.sortedBy { fit.margin[it] }.take(ALTERNATIVE_CANDIDATES)
        val result = ArrayList<Correction>()
        for (x in closeCalls.indices) {
            for (y in x + 1 until closeCalls.size) {
                val i = closeCalls[x]
                val j = closeCalls[y]
                val ci = colors[i].ordinal
                val cj = colors[j].ordinal
                if (ci == cj) continue
                val cost = fit.cost(i, cj) + fit.cost(j, ci) - fit.cost(i, ci) - fit.cost(j, cj)
                if (cost <= ALTERNATIVE_MAX_COST) result += Correction(intArrayOf(i, j), intArrayOf(cj, ci), cost)
            }
        }
        return result
    }

    /**
     * Corrections of one confidently misread sticker of [colors], cheapest first.
     *
     * Every color must appear `n * n` times, so a sticker misread as color S leaves one sticker too
     * many showing S and one too few showing its true color D, and the joint classification had to
     * give stickers colors they don't show: one showing S, or a chain such as S given to a sticker
     * showing W, and W to one showing D, whichever was cheapest. Such forced stickers fit the color
     * they were given worse than another one (negative margin), or, when the per-photo lighting
     * model has partly absorbed the misfit, hardly better (a close call). A correction gives a chain
     * of these suspects (the [MISREAD_FORCED] with the smallest margins together, or each one alone)
     * their next best colors instead; when that leaves one color S too many and one color D too few,
     * it also recolors one sticker of color S as D: any one, since the misread one looks just like
     * the others. Validation singles it out.
     */
    private fun misreadCorrections(classification: Classification, colors: List<CubeColor>): List<Correction> {
        val fit = Fit(classification, colors)
        val suspects = fit.movable.filter { fit.margin[it] < ScanResolver.UNCERTAIN_MARGIN }.sortedBy { fit.margin[it] }.take(MISREAD_FORCED)
        val chains = (if (suspects.size > 1) listOf(suspects) else emptyList()) + suspects.map { listOf(it) }
        val result = ArrayList<Correction>()
        for (chain in chains) {
            val net = IntArray(JointClustering.COLORS)
            var chainCost = 0.0
            for (f in chain) {
                net[fit.rival[f]]++
                net[colors[f].ordinal]--
                chainCost += fit.margin[f]
            }
            val surplus = net.indices.filter { net[it] > 0 }
            val deficit = net.indices.filter { net[it] < 0 }
            if (surplus.size != 1 || deficit.size != 1 || net[surplus[0]] != 1 || net[deficit[0]] != -1) continue
            val s = surplus[0]
            val d = deficit[0]
            for (m in fit.movable) {
                if (colors[m].ordinal != s || m in chain) continue
                val stickers = IntArray(chain.size + 1) { if (it < chain.size) chain[it] else m }
                val newColors = IntArray(chain.size + 1) { if (it < chain.size) fit.rival[chain[it]] else d }
                result += Correction(stickers, newColors, chainCost + fit.cost(m, d) - fit.cost(m, s))
            }
        }
        result.sortBy { it.cost }
        return result
    }

    /** The problem reported for scans whose clusters are not six clearly different sticker colors. */
    val IMPLAUSIBLE = NxNError("These scans don't show six different sticker colors.")

    /** Clusters that are not six clearly different colors (one face scanned six times, grey surfaces): no cube, all uncertain. */
    private fun implausible(layout: Layout, classification: Classification, palette: CubePaletteEstimate, placement: Placement): NxNScanAnalysis {
        val raw = classification.colors
        val problems = listOf(IMPLAUSIBLE) + problemsOf(layout.n, raw)
        return NxNScanAnalysis(layout.n, raw, raw, NO_ROTATIONS, false, (0 until layout.count).toSortedSet(), palette, placement, problems)
    }

    /** Best-effort result for scans that are not six of n × n samples: per-sticker guesses, all uncertain. */
    private fun malformed(layout: Layout, scans: List<List<StickerSample>>): NxNScanAnalysis {
        val n = layout.n
        val raw = List(layout.count) { i ->
            val sample = scans.getOrNull(i / layout.perFace)?.getOrNull(i % layout.perFace)
            if (sample == null) {
                scheme.colorOf(Face.entries[i / layout.perFace])
            } else {
                LiveClassifier.classify(StickerSample.of(sample.r.coerceIn(0, 255), sample.g.coerceIn(0, 255), sample.b.coerceIn(0, 255)))
            }
        }
        val placement = if (layout.center >= 0) Placement.CENTER_COLORS else Placement.SCAN_ORDER
        val problems = listOf(malformedProblem(n, scans)) + problemsOf(n, raw)
        return NxNScanAnalysis(n, raw, raw, NO_ROTATIONS, false, (0 until layout.count).toSortedSet(), CubePaletteEstimate.STANDARD, placement, problems)
    }

    /** The problem reported for scans that are not six scans of [n] × [n] samples. */
    fun malformedProblem(n: Int, scans: List<List<StickerSample>>): NxNError {
        val sizes = scans.map { it.size }.distinct().sorted()
        val got = if (scans.isEmpty()) "no scans" else "${scans.size} scans of ${sizes.joinToString(" or ")} stickers"
        return NxNError("Six scans of $n×$n stickers are needed; got $got.")
    }

    /** What [NxNValidator] finds wrong with [colors] as an [n]x[n] cube (empty if they are a valid cube); never throws. */
    fun problemsOf(n: Int, colors: List<CubeColor>): List<NxNError> {
        val validation = runCatching { NxNValidator.validate(NxNCube.of(n, colors)) }.getOrNull()
            ?: return listOf(NxNError("These colors don't form a ${n}×$n cube."))
        if (validation.isValid || validation.errors.isNotEmpty()) return validation.errors
        return listOf(NxNError("The color arrangement of this cube can't be worked out from its corners."))
    }

    private val NO_ROTATIONS: Map<Face, Int> = Face.entries.associateWith { 0 }
}
