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
 *  6. One face photographed twice ([duplicateEvidence], from [LookAlikeScans]) is reported first
 *     when the reading is invalid; on 2x2 cubes, whose corners alone are too weak a check, a valid
 *     reading is also rejected for it, or for resting on a clearly misread sticker ([checkTwoByTwo]).
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

    /**
     * [duplicateEvidence]: a reading forces stickers when their negative margins (how much better the
     * color a sticker shows best fits it than the color it was given, CIEDE2000) add up to more than
     * [FORCING], more than a few close calls the other way round; a sticker counts as forced when its
     * own margin is below -[FORCED_STICKER]. On solved and nearly solved cubes of very pale colors the
     * lighting model can hardly tell a cast from a color, and many stickers are slight close calls:
     * lower thresholds took one such 2x2 session in 60 for a duplicate, higher ones let duplicates of
     * pale faces through.
     */
    private const val FORCING = ScanResolver.UNCERTAIN_MARGIN.toDouble()
    private const val FORCED_STICKER = 2.0

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
        // Needed for every 2x2 reading (see checkTwoByTwo), otherwise only to explain an invalid one.
        val lookAlikes = lazy { LookAlikeScans.find(n, linear, distinctive = n == 2) }
        return if (layout.center >= 0) resolveOdd(layout, linear, positions, lookAlikes) else resolveEven(layout, samples, linear, positions, lookAlikes)
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
    private fun resolveOdd(layout: Layout, linear: Array<DoubleArray>, positions: List<Face>?, lookAlikes: Lazy<List<LookAlike>>): NxNScanAnalysis {
        val clusters = clusterWithCenters(layout, linear)
        val named = Named(clusters)
        val byCenters = named.labels.map { scheme.faceOf(it) }
        val centerPlaced = Placed(clusters.classification(named.labels, byCenters, layout.center), byCenters, lookAlikes)
        if (!named.plausible) return implausible(layout, centerPlaced, named.palette, Placement.CENTER_COLORS)
        val first = analyze(layout, centerPlaced, Placement.CENTER_COLORS, named.palette)
        if (first.isValid || positions == null || positions == byCenters) return first
        val second = analyze(layout, Placed(clusters.classification(named.labels, positions, layout.center), positions, lookAlikes), Placement.SCAN_ORDER, named.palette)
        return if (second.isValid) second else first
    }

    /**
     * Even sizes: clusters from scratch (best few tried in turn), placed by scan position. A 2x2
     * reading rejected as a duplicate scan or for a confidently misread sticker ([checkTwoByTwo]) ends
     * the search: the scans, not the clustering, are at fault.
     */
    private fun resolveEven(
        layout: Layout,
        samples: List<StickerSample>,
        linear: Array<DoubleArray>,
        positions: List<Face>?,
        lookAlikes: Lazy<List<LookAlike>>,
    ): NxNScanAnalysis {
        val faces = positions ?: GUIDED_ORDER
        val quota = IntArray(6) { layout.perFace }
        val candidates = JointClustering.clusterFromScratch(samples, linear, 6, layout.perFace, quota, quota).take(CLUSTERINGS_TRIED)
        var first: NxNScanAnalysis? = null
        for ((k, clusters) in candidates.withIndex()) {
            val named = Named(clusters)
            val placed = Placed(clusters.classification(named.labels, faces, -1), faces, lookAlikes)
            val analysis = if (named.plausible) {
                analyze(layout, placed, Placement.SCAN_ORDER, named.palette)
            } else {
                implausible(layout, placed, named.palette, Placement.SCAN_ORDER)
            }
            if (analysis.isValid) return withRivalReadings(layout, analysis, clusters, candidates.filterIndexed { i, _ -> i != k }, faces)
            if (layout.n == 2 && analysis.problems.firstOrNull()?.message in REJECTIONS) return analysis
            if (first == null) first = analysis
        }
        return first!!
    }

    /** A classification with the faces its scans were placed on (scan k on `faces[k]`), and the look-alike scans of the session. */
    private class Placed(val classification: Classification, val faces: List<Face>, val lookAlikes: Lazy<List<LookAlike>>)

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
     * correction of one confidently misread sticker ([repairMisread]). A valid 2x2 reading must also
     * pass [checkTwoByTwo]; an invalid reading of scans that look like one face scanned twice reports
     * that first ([duplicateEvidence]).
     */
    private fun analyze(layout: Layout, placed: Placed, placement: Placement, palette: CubePaletteEstimate): NxNScanAnalysis {
        val classification = placed.classification
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
            val duplicate = duplicateEvidence(layout, placed, raw)
            if (duplicate.level == Duplication.CLEAR) uncertainRaw += secondScanOf(layout, placed, duplicate.pair!!)
            return NxNScanAnalysis(n, raw, raw, NO_ROTATIONS, false, uncertainRaw.toSortedSet(), palette, placement, duplicate.explain(layout, placed) + problems)
        }
        val duplicate = if (n == 2) duplicateEvidence(layout, placed, raw) else null
        if (duplicate != null) {
            val rejection = checkTwoByTwo(layout, placed, raw, duplicate)
            if (rejection != null) {
                uncertainRaw += rejection.stickers
                return NxNScanAnalysis(n, raw, raw, NO_ROTATIONS, false, uncertainRaw.toSortedSet(), palette, placement, listOf(rejection) + problemsOf(n, raw))
            }
        }
        val rotations = oriented.rotations
        val uncertain = uncertainRaw.mapTo(sortedSetOf()) { i ->
            NxNOrientationFixer.destinationOf(n, i, rotations.getValue(Face.entries[i / layout.perFace]))
        }
        uncertain += oriented.ambiguous
        uncertain += alternativeReadings(classification, raw, oriented, orient)
        // A possible duplicate: no sticker of a reading that may rest on the wrong photo can be trusted.
        if (duplicate?.level == Duplication.POSSIBLE) uncertain += 0 until layout.count
        return NxNScanAnalysis(n, raw, oriented.colors, rotations, true, uncertain, palette, placement, emptyList())
    }

    /**
     * Rejects a valid 2x2 reading [raw] of [placed] (the colors as classified, or as repaired by
     * [JointClustering.repairBySwap]) that rests on stickers read as colors they clearly don't show,
     * returning the problem to report, or null to keep it ([duplicate]: the evidence for a duplicate
     * scan). A 2x2 cube has only its corners for validation to check, and a wrong reading passes for a
     * valid cube far too easily: a third of rendered sessions with one face scanned twice did, most of
     * them with wrong stickers unflagged, often after exchanging two clearly read stickers. It is
     * rejected when
     *  - two scans look like the same face ([Duplication.CLEAR]): the later one is to be retaken;
     *  - the reading gives a sticker another color than it shows, by more than
     *    [ScanResolver.UNCERTAIN_MARGIN]: a misread, or an exchange of two clearly read stickers,
     *    which validation cannot tell from the right reading on a 2x2 cube, or a duplicate scan that
     *    the look-alike test missed.
     * On rendered scans of real 2x2 cubes (scrambled, solved and nearly solved, vivid and pastel, in
     * any light) about one session in a hundred is rejected, nearly all of them solved or nearly
     * solved cubes of very pale colors in a color cast.
     */
    private fun checkTwoByTwo(layout: Layout, placed: Placed, raw: List<CubeColor>, duplicate: DuplicateEvidence): NxNError? {
        if (duplicate.level == Duplication.CLEAR) return duplicate.explain(layout, placed).single()
        val fit = Fit(placed.classification, raw)
        val misread = fit.movable.filter { fit.margin[it] < -ScanResolver.UNCERTAIN_MARGIN }
        if (misread.isEmpty()) return null
        return NxNError(MISREAD_MESSAGE, misread.toSortedSet())
    }

    /** How strongly the scans look like one face scanned twice ([duplicateEvidence]). */
    private enum class Duplication {
        /** No sign of a duplicate. */
        NONE,

        /**
         * Two scans look alike in a distinctive way but are read as different faces: possibly a
         * duplicate whose colors the lighting model explained away as another face's under an
         * unusual cast.
         */
        POSSIBLE,

        /** Two scans look alike and the reading needed stickers forced into colors they don't show. */
        CLEAR,
    }

    /** The [level] of evidence for a duplicate scan, and the [pair] of scans it rests on. */
    private class DuplicateEvidence(val level: Duplication, val pair: LookAlike?) {
        /** The problem to report for a [Duplication.CLEAR] duplicate (the later scan's stickers highlighted), else nothing. */
        fun explain(layout: Layout, placed: Placed): List<NxNError> =
            if (level == Duplication.CLEAR) listOf(NxNError(DUPLICATE_MESSAGE, secondScanOf(layout, placed, pair!!))) else emptyList()
    }

    /** The stickers (indices in the placed colors) of the later scan of [pair]. */
    private fun secondScanOf(layout: Layout, placed: Placed, pair: LookAlike): Set<Int> {
        val start = placed.faces[pair.second].ordinal * layout.perFace
        return (start until start + layout.perFace).toSortedSet()
    }

    /**
     * Whether some two scans look like the same face ([LookAlike]) and the reading [colors] of
     * [placed] (as classified, or repaired) shows the signs of a duplicate. A face scanned twice, in
     * place of another face, puts one face's colors on the cube twice and leaves out another's, but
     * every color must appear `n * n` times, so the joint classification has to give stickers colors
     * they don't show (negative margins). It is [Duplication.CLEAR] when two look-alike scans are read alike while stickers had
     * to be forced (more than [FORCING] in all), or are read differently only where stickers were
     * forced ([FORCED_STICKER]). Two different faces that look alike (a 2x2 cube often has two faces
     * with the same pattern; a solved pale cube has uniform faces that look alike under different
     * casts) are read as what they show and need nothing forced. Of several such pairs, the one that
     * matches most closely is taken for the duplicate (two photos of one face differ least).
     *
     * Pairs read differently without forcing are [Duplication.POSSIBLE] if their match is
     * [LookAlike.distinctive]: the lighting model can also explain a duplicate of a pale face as
     * another face under an unusual cast, with nothing forced.
     */
    private fun duplicateEvidence(layout: Layout, placed: Placed, colors: List<CubeColor>): DuplicateEvidence {
        val lookAlikes = placed.lookAlikes.value
        if (lookAlikes.isEmpty()) return DuplicateEvidence(Duplication.NONE, null)
        val fit = Fit(placed.classification, colors)
        val forced = fit.movable.sumOf { minOf(0.0, fit.margin[it]) } < -FORCING
        var possible: LookAlike? = null
        for (pair in lookAlikes) {
            val a = placed.faces[pair.first].ordinal * layout.perFace
            val b = placed.faces[pair.second].ordinal * layout.perFace
            var alike = true
            var onlyForced = true
            for (p in 0 until layout.perFace) {
                val i = a + p
                val j = b + pair.matching[p]
                if (colors[i] == colors[j]) continue
                alike = false
                if (!(fit.margin[i] < -FORCED_STICKER || fit.margin[j] < -FORCED_STICKER)) onlyForced = false
            }
            if ((alike && forced) || (!alike && onlyForced)) return DuplicateEvidence(Duplication.CLEAR, pair)
            if (!alike && pair.distinctive && possible == null) possible = pair
        }
        return DuplicateEvidence(if (possible != null) Duplication.POSSIBLE else Duplication.NONE, possible)
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
    private fun implausible(layout: Layout, placed: Placed, palette: CubePaletteEstimate, placement: Placement): NxNScanAnalysis {
        val raw = placed.classification.colors
        val problems = duplicateEvidence(layout, placed, raw).explain(layout, placed) + IMPLAUSIBLE + problemsOf(layout.n, raw)
        return NxNScanAnalysis(layout.n, raw, raw, NO_ROTATIONS, false, (0 until layout.count).toSortedSet(), palette, placement, problems)
    }

    /** The problem reported when two scans look like the same face. */
    const val DUPLICATE_MESSAGE = "Two photos seem to show the same side of the cube. Retake the highlighted side."

    /** The problem reported when a 2x2 reading needs stickers to be colors they clearly don't show. */
    const val MISREAD_MESSAGE = "These stickers don't look like the colors the cube still needs. Retake the sides they are on."

    /** Problems that reject the scans themselves, so that no other clustering is tried. */
    private val REJECTIONS = setOf(DUPLICATE_MESSAGE, MISREAD_MESSAGE)

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
