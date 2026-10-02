package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.nxn.NxNValidator

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
 *     converge. Nothing in this step depends on which color is which. If two centers ended up each
 *     fitting the other's cluster better than their own (they looked alike in their photos, e.g. a
 *     white center under a cool cast and a light blue one under a warm cast), the two clusters'
 *     members are exchanged and refitted, and the better explanation of the stickers is kept.
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
 * it yields a result with `isValid == false`. So do scans whose clusters are not six clearly
 * different colors, five of them (besides white) mostly colored: e.g. one face scanned twice, or six
 * grey surfaces.
 *
 * **Other sizes.** `resolve(n, scans, scanPositions)` does the same for N×N cubes (N = 2..10; 2..7 are
 * the sizes the app scans) and returns an [NxNScanAnalysis]; see that function. For N = 3 it gives
 * exactly the result of the 3x3 [resolve].
 */
object ScanResolver {

    /** A sticker is uncertain when its second-best color costs less than this much more (deltaE units). */
    const val UNCERTAIN_MARGIN = 6f

    private const val MAX_ROUNDS = 10

    /** Position of the center sticker in a 3x3 scan. */
    private const val CENTER = 4

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
        val byColor = labels.indices.associate { labels[it] to measured[it] }
        val palette = PaletteEstimator.estimate(byColor)
        val byCenters = facesByCenterColor(labels)
        if (!JointClustering.areDistinct(measured) || !JointClustering.isColored(byColor)) {
            // E.g. the same face scanned twice, or something grey: the clusters are arbitrary, and so
            // would be any cube built from them.
            val raw = clusters.classification(labels, byCenters, CENTER).colors
            return ScanAnalysis(raw, raw, Face.entries.associateWith { 0 }, false, (0 until Facelets.COUNT).toSet(), palette)
        }

        val first = analyze(clusters.classification(labels, byCenters, CENTER), Placement.CENTER_COLORS, palette)
        if (first.isValid) return first
        val positions = scanPositions?.takeIf { it.size == 6 && it.toSet().size == 6 } ?: return first
        if (positions == byCenters) return first
        val second = analyze(clusters.classification(labels, positions, CENTER), Placement.SCAN_ORDER, palette)
        return if (second.isValid) second else first
    }

    /**
     * Resolves an [n]x[n] cube (n in 2..10) from its six scanned faces.
     *
     * [scans] are the six faces, each `n * n` samples row-major as seen on screen
     * ([GridSampler.sample] with the same [n]), each captured at any rotation (0 to 3 quarter turns).
     * All `6 * n * n` stickers are clustered jointly into six colors of exactly `n * n` stickers each,
     * under the per-photo lighting model of the 3x3 resolver, and the colors are named by how they
     * relate to each other ([PaletteLabeler]), so pastel and other unusual palettes work too.
     *
     *  - **Odd sizes** have fixed centers. They seed the clusters, and the scans are placed by their
     *    center colors in the standard scheme, or else by [scanPositions] (as the 3x3 [resolve]
     *    does): scans may come in any order for standard cubes, and in the order of [scanPositions]
     *    for other color arrangements.
     *  - **Even sizes** have no fixed centers, so neither the colors nor the faces can be anchored on
     *    a center. The clustering starts from several seeds (the live classifier's reading of every
     *    sticker, and reproducible k-means++ starts) and keeps the clustering that explains the
     *    stickers best; if that doesn't give a valid cube, the next best ones are tried. Scan `i` is
     *    placed on face [scanPositions]`[i]`; when [scanPositions] is null (or not six different
     *    faces), the scans are taken to be in the app's guided order F, R, B, L, U, D. The color
     *    arrangement need not be standard: it is read from the corners ([NxNValidator], which holds
     *    the cube with the corner at DBL home).
     *
     * Face rotations are then fixed for any size ([NxNOrientationFixer]: every combination of quarter
     * turns of the six faces is searched, pruned piece by piece, preferring the fewest rotated faces
     * and then the fewest quarter turns). If no rotation gives a valid cube, swapping the colors of
     * two low-confidence stickers is tried, and from 4x4 on also correcting one confidently misread
     * sticker (with hundreds of stickers per cube, one read partly off its tile is the commonest
     * reason for an invalid scan). [NxNScanAnalysis.uncertain] holds the close calls, the corrected
     * stickers and every sticker whose color differs in another valid reading, as for 3x3 cubes; the
     * other readings considered also include those with two close calls exchanged, or with the
     * misread being another sticker of the same color (from 4x4 on, two movable center stickers of
     * one orbit with their colors exchanged still form a valid cube, as do two wings with the same
     * second color with their first colors exchanged, so validation alone cannot tell which one was
     * misread).
     *
     * **One face photographed twice** (the user forgot to turn the cube) puts one face's colors on the
     * cube twice and leaves another out. When two scans look alike sticker for sticker (under some
     * quarter turn, allowing for each photo's exposure and white balance) and the reading had to force
     * stickers into colors they don't show, the first problem says so and highlights the later of the
     * two scans, to be retaken. Bigger cubes never pass validation that way; a 2x2 cube, which has only
     * its corners to check, often did, so a valid 2x2 reading is rejected in that case, and also when
     * it rests on any sticker read as a color it clearly doesn't show (a misread cannot be told from
     * the right reading on a 2x2 cube); a 2x2 reading that may rest on a duplicate in a less clear way
     * has every sticker flagged. A wrong scan order (e.g. the side faces in mirrored order, or up and
     * down swapped) is not detectable in general: on rendered 2x2 cubes with up and down, or front
     * and back, swapped, about one session in 40 still gave a valid (wrong) cube, so scan screens
     * should guide the order clearly.
     *
     * For n = 3 this returns exactly what the 3x3 [resolve] returns for the same arguments. Malformed
     * scans (not six scans of `n * n`), garbage such as grey surfaces or one face scanned six times,
     * never throw: they give a result with `isValid == false`.
     *
     * @throws IllegalArgumentException if [n] is not a supported size (2..10).
     */
    fun resolve(n: Int, scans: List<List<StickerSample>>, scanPositions: List<Face>? = null): NxNScanAnalysis {
        require(n in NxNScanResolver.MIN_SIZE..NxNScanResolver.MAX_SIZE) { "Cube size must be in ${NxNScanResolver.MIN_SIZE}..${NxNScanResolver.MAX_SIZE}, got $n" }
        if (n != 3) return NxNScanResolver.resolve(n, scans, scanPositions)
        val analysis = resolve(scans, scanPositions)
        val problems = when {
            analysis.isValid -> emptyList()
            scans.size != 6 || scans.any { it.size != 9 } -> listOf(NxNScanResolver.malformedProblem(3, scans)) + NxNScanResolver.problemsOf(3, analysis.colors)
            // Colors that validate but were rejected: the clusters were not six clearly different colors.
            else -> NxNScanResolver.problemsOf(3, analysis.colors).ifEmpty { listOf(NxNScanResolver.IMPLAUSIBLE) }
        }
        return NxNScanAnalysis(
            n = 3,
            rawColors = analysis.rawColors,
            colors = analysis.colors,
            faceRotations = analysis.faceRotations,
            isValid = analysis.isValid,
            uncertain = analysis.uncertain,
            palette = analysis.palette,
            placement = analysis.placement,
            problems = problems,
        )
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

    /** The face of each scan when placed by its center's color in the standard scheme. */
    private fun facesByCenterColor(labels: List<CubeColor>): List<Face> = labels.map { scheme.faceOf(it) }

    /**
     * Steps 1 to 3 for well-formed scans with the center-color placement: classifies all stickers
     * jointly (nine per color) under a per-scan lighting model, names the colors and places each scan
     * on the face of its center color.
     */
    internal fun classify(scans: List<List<StickerSample>>): Classification {
        require(scans.size == 6 && scans.all { it.size == 9 }) { "Need six scans of nine samples" }
        val clusters = cluster(scans)
        val labels = PaletteLabeler.labelAll(clusters.robustColors())
        return clusters.classification(labels, facesByCenterColor(labels), CENTER)
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
        val model = LightingModel(linear, 6, 9)
        val assigned = IntArray(Facelets.COUNT) { -1 }
        for (k in 0 until 6) assigned[k * 9 + CENTER] = k
        model.seedFromPosition(CENTER)
        val start = converge(model, assigned, model.costs())
        // Repair an unlucky start: two centers that look alike in their photos can each end up with the
        // other's stickers (see JointClustering.undoSwappedCenters).
        return JointClustering.undoSwappedCenters(start, CENTER, NON_CENTERS) { trial, swapped -> converge(trial, swapped, trial.costs()) }
    }

    /** Alternates balanced assignment and lighting fits from [assigned] and [cost] until nothing changes. */
    private fun converge(model: LightingModel, assigned: IntArray, cost: DoubleArray): Clusters {
        var current = cost
        for (round in 0 until MAX_ROUNDS) {
            if (!assignBalanced(current, assigned)) break
            model.fit(assigned)
            current = model.costs()
        }
        return Clusters(model.linear, assigned, current, model.gain, 9)
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
    private fun repairBySwap(classification: Classification): Triple<List<CubeColor>, OrientationFixer.Orientation, Set<Int>>? =
        JointClustering.repairBySwap(classification) { OrientationFixer.orient(it) }

    /** Best-effort result for input that is not six scans of nine: per-sticker guesses, all uncertain. */
    private fun malformed(scans: List<List<StickerSample>>): ScanAnalysis {
        val raw = List(Facelets.COUNT) { i ->
            val sample = scans.getOrNull(i / 9)?.getOrNull(i % 9)
            if (sample == null) scheme.colorOf(Facelets.faceOf(i)) else LiveClassifier.classify(StickerSample.of(sample.r.coerceIn(0, 255), sample.g.coerceIn(0, 255), sample.b.coerceIn(0, 255)))
        }
        return ScanAnalysis(raw, raw, Face.entries.associateWith { 0 }, false, (0 until Facelets.COUNT).toSet())
    }

    private val NON_CENTERS: IntArray = (0 until Facelets.COUNT).filter { it % 9 != CENTER }.toIntArray()
}
