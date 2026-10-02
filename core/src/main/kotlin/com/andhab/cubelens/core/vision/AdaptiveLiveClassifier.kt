package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor

/**
 * Live sticker classification that learns the actual colors of the cube being scanned.
 *
 * Knock-off cubes come in every shade, and the default rules of [LiveClassifier] can only guess what
 * a pastel or muted sticker is meant to be. The center sticker of each captured face shows exactly
 * what that face's color looks like on this cube, in this light. So the scanning screen tells the
 * classifier about each captured center, and [classify] then compares stickers with the learned colors:
 *
 *  - A learned color costs its squared distance to the sticker, in units of [LEARNED_SPREAD]. The
 *    distance is CIEDE2000 ([ColorMath.deltaE]) after matching the sticker's exposure to the learned
 *    sample (luminance ratio up to [MAX_EXPOSURE_RATIO]), because faces are captured at different
 *    brightness while their colors stay put.
 *  - A color not learned yet keeps its default cost ([LiveClassifier.costs]) plus [UNLEARNED_PENALTY].
 *    Once white is learned, the default rules see the sticker white-balanced on it: all faces of a
 *    scan are usually taken under the same light, and its cast is what makes pastel stickers hard
 *    to tell from white.
 *  - The cheapest color wins. So a learned color takes the stickers that look like it (within about
 *    two [LEARNED_SPREAD]s), and everything else is left to the default rules: after learning a pink
 *    center as red, a peach sticker is still orange, because it is far from that pink.
 *
 * With all six colors learned this is a nearest-learned-color classifier; with none, it is
 * [LiveClassifier].
 *
 * **Naming the centers.** Which color a captured center is meant to be is itself a guess on unusual
 * cubes: a lavender or a very light baby blue center looks white on its own, a pastel orange looks
 * yellow. Two ways to learn centers:
 *
 *  - [learnCenters] (recommended): after each capture, pass the centers of all faces captured so far
 *    (and their faces). They are named jointly by how they relate to each other ([PaletteLabeler]:
 *    the most white-like one is white and white-balances the others, the rest follow the order of
 *    the color wheel), and earlier names are revised when a later center makes them clearer, e.g.
 *    a light blue first taken for white is renamed blue once the real white is captured. With all
 *    six centers this names knock-off palettes as reliably as [ScanResolver] does.
 *  - [labelForCenter] and [learn]: name one new center among the colors not learned yet and keep the
 *    earlier names fixed. Simple, and six centers always get six different names, but an early
 *    mistake can't be undone and pushes later centers onto wrong names.
 *
 * **Any cube size.** [learnFaces] takes the captured faces of an N×N cube (2x2 to 10x10). Odd sizes
 * have fixed centers and use [learnCenters]. Even sizes have none, so the colors are learned from all
 * captured stickers at once, clustered and named jointly, which on a scrambled cube usually works from
 * the first or second capture (the fourth or fifth on a 2x2, whose faces have four stickers each).
 *
 * Thread-safe: [classify] and [labelForCenter] may run on a camera analyzer thread while [learn],
 * [learnCenters], [learnFaces], [forget] and [reset] are called on another thread. The learned state
 * is an immutable snapshot that writers replace atomically, so readers never block and always see a
 * consistent set of colors.
 */
class AdaptiveLiveClassifier {

    /** One learned color: the center [sample], its CIELAB, linear RGB and luminance. */
    private class Reference(val sample: StickerSample) {
        val linear: DoubleArray = linearOf(sample)
        val lab: Lab = ColorMath.linearToLab(linear[0], linear[1], linear[2])
        val luminance: Double = luminance(linear)
    }

    /** An immutable set of learned colors. */
    private class State(
        /** Learned references indexed by [CubeColor.ordinal]. */
        val references: Array<Reference?>,
    ) {
        val isEmpty: Boolean = references.all { it == null }

        /**
         * Gains that make the learned white neutral, if white is learned and plausibly a white sticker
         * under some cast; null otherwise.
         */
        val whiteBalance: DoubleArray? = references[CubeColor.WHITE.ordinal]?.let { white ->
            val (r, g, b) = white.linear
            if (LiveClassifier.whiteDistance(r, g, b) > MAX_WHITE_DISTANCE) {
                null
            } else {
                PaletteLabeler.neutralizingGains(white.linear).map { it.coerceIn(MIN_BALANCE, MAX_BALANCE) }.toDoubleArray()
            }
        }

        /** [sample] white-balanced with [whiteBalance] (itself if there is none). */
        fun balanced(sample: StickerSample): StickerSample {
            val gains = whiteBalance ?: return sample
            return StickerSample.of(
                ColorMath.linearToSrgb(ColorMath.srgbToLinear(sample.r) * gains[0]),
                ColorMath.linearToSrgb(ColorMath.srgbToLinear(sample.g) * gains[1]),
                ColorMath.linearToSrgb(ColorMath.srgbToLinear(sample.b) * gains[2]),
            )
        }
    }

    @Volatile
    private var state = EMPTY

    private val lock = Any()

    /** CIELAB of each learned color (of the center sample it was learned from). */
    val learned: Map<CubeColor, Lab>
        get() {
            val snapshot = state.references
            return COLORS.mapNotNull { c -> snapshot[c.ordinal]?.let { c to it.lab } }.toMap()
        }

    /** Remembers that [center] (a captured face's center sticker) shows what [color] looks like on this cube. */
    fun learn(color: CubeColor, center: StickerSample) {
        val reference = Reference(center)
        synchronized(lock) {
            state = State(state.references.copyOf().also { it[color.ordinal] = reference })
        }
    }

    /** Forgets what [color] looks like, e.g. when its face is scanned again. */
    fun forget(color: CubeColor) {
        synchronized(lock) {
            if (state.references[color.ordinal] != null) {
                state = State(state.references.copyOf().also { it[color.ordinal] = null })
            }
        }
    }

    /** Forgets all learned colors, e.g. for a new cube. */
    fun reset() {
        synchronized(lock) {
            state = EMPTY
        }
    }

    /** The most likely color of [sample], using the learned colors where there are any. */
    fun classify(sample: StickerSample): CubeColor {
        val snapshot = state
        if (snapshot.isEmpty) return LiveClassifier.classify(sample)
        val references = snapshot.references
        val defaults = if (references.any { it == null }) LiveClassifier.costs(snapshot.balanced(sample)) else null
        val linear = linearOf(sample)
        val luminance = luminance(linear)
        var best = COLORS[0]
        var bestCost = Double.POSITIVE_INFINITY
        for (color in COLORS) {
            val reference = references[color.ordinal]
            val cost = if (reference == null) {
                defaults!![color.ordinal] + UNLEARNED_PENALTY
            } else {
                val d = distance(linear, luminance, reference) / LEARNED_SPREAD
                d * d
            }
            if (cost < bestCost) {
                bestCost = cost
                best = color
            }
        }
        return best
    }

    /**
     * Names the centers of all faces captured so far, jointly, and learns them under those names,
     * replacing everything learned before. Returns the name of each center ([centers] order), all
     * different.
     *
     * Call it after each capture with every captured face's center (any order, at most six; a
     * rescanned face simply replaces its old entry), and show the returned names: they may change
     * as more centers come in, because each new center is evidence about the others (see the class
     * documentation). [faces] optionally gives all nine samples of each of those faces (same order as
     * [centers]); it settles light pastel centers that look white on their own, as in [labelForCenter].
     */
    fun learnCenters(centers: List<StickerSample>, faces: List<List<StickerSample>> = emptyList()): List<CubeColor> {
        require(centers.size <= COLORS.size) { "A cube has six centers, got ${centers.size}" }
        require(faces.isEmpty() || faces.size == centers.size) { "Need a face for every center or none, got ${faces.size} for ${centers.size}" }
        val labels = if (centers.isEmpty()) {
            emptyList()
        } else {
            val ruledOut = BooleanArray(centers.size) { k -> faces.isNotEmpty() && hasWhiterSticker(centers[k], faces[k], EMPTY) }
            PaletteLabeler.labelCenters(centers, whiteRuledOut = ruledOut) ?: COLORS.take(centers.size)
        }
        val references = arrayOfNulls<Reference>(COLORS.size)
        labels.forEachIndexed { k, color -> references[color.ordinal] = Reference(centers[k]) }
        synchronized(lock) {
            state = State(references)
        }
        return labels
    }

    /**
     * Learns this cube's colors from all faces of an [n]x[n] cube captured so far, replacing everything
     * learned before; any size (n in 2..10). [faces] are the captured faces (at most six, any order;
     * a rescanned face simply replaces its old entry), each with all `n * n` samples as returned by
     * [GridSampler.sample] for that size.
     *
     *  - **Odd sizes** have a fixed center per face: this is [learnCenters] with the faces' centers
     *    (and the faces, for settling light pastel centers). Returns the name of each face's center
     *    ([faces] order, all different), which the scan screen can show as that face's color.
     *  - **Even sizes** have no fixed centers, so no face has a color of its own: all captured
     *    stickers are clustered jointly under a per-photo lighting model and the clusters are named
     *    by how they relate to each other ([PaletteLabeler], as the resolver does), once there is
     *    enough evidence: at least twelve stickers, among which all six colors show clearly (each at
     *    least twice, as clusters clearly apart). On a scrambled cube that is usually from the first
     *    or second capture on (4x4 and larger), the fourth or fifth on a 2x2. Until then nothing is
     *    learned and [classify] uses the default rules. Returns null: there is no per-face center
     *    color; the scan screen identifies faces by its guided order instead.
     *
     * The scan screen should call this after every capture (and recapture) with all faces captured so
     * far, from a background thread for big cubes (a few milliseconds for 7x7 with six faces), and
     * keep calling [classify] for the live preview: it then compares stickers with this cube's
     * learned colors.
     *
     * @throws IllegalArgumentException if [n] is not in 2..10, there are more than six faces, or a
     *   face does not have `n * n` samples.
     */
    fun learnFaces(faces: List<List<StickerSample>>, n: Int): List<CubeColor>? {
        require(n in MIN_SIZE..MAX_SIZE) { "Cube size must be in $MIN_SIZE..$MAX_SIZE, got $n" }
        require(faces.size <= COLORS.size) { "A cube has six faces, got ${faces.size}" }
        require(faces.all { it.size == n * n }) { "Every face needs ${n * n} samples, got ${faces.map { it.size }}" }
        if (n % 2 == 1) {
            val center = (n / 2) * n + n / 2
            return learnCenters(faces.map { it[center] }, faces)
        }
        val learned = JointClustering.learnColors(faces)
        val references = arrayOfNulls<Reference>(COLORS.size)
        learned?.forEach { (color, sample) -> references[color.ordinal] = Reference(sample) }
        synchronized(lock) {
            state = State(references)
        }
        return null
    }

    /**
     * The color to learn a newly captured face's [center] as: the most likely one among the colors
     * not learned yet, named together with the centers learned already (whose names stay fixed; see
     * [PaletteLabeler]). Six centers captured one after another, each [learn]ed with the label
     * returned here, therefore always get six different labels. When every color is learned already,
     * the nearest learned color. [learnCenters] is more reliable on unusual palettes, because it can
     * still revise earlier names.
     *
     * [face] optionally gives the other stickers of the same photo (e.g. all nine samples of the
     * captured face; the center may be included). They share the center's light, so they settle the
     * hardest case: a light pastel center (peach under cool light, baby blue under warm light) looks
     * white on its own, but a white center is the whitest thing on its face. If a sticker in [face]
     * is clearly more neutral than the center and at least about as bright, the center is not white.
     */
    fun labelForCenter(center: StickerSample, face: List<StickerSample> = emptyList()): CubeColor {
        val snapshot = state
        val references = snapshot.references
        val open = COLORS.filter { references[it.ordinal] == null }
        return when (open.size) {
            0 -> classify(center)
            1 -> open[0]
            else -> {
                val learnedColors = COLORS.filter { references[it.ordinal] != null }
                val centers = learnedColors.map { references[it.ordinal]!!.sample } + center
                val allowed = learnedColors.map { setOf(it) } + listOf(open.toSet())
                val ruledOut = BooleanArray(centers.size)
                ruledOut[centers.size - 1] = CubeColor.WHITE in open && hasWhiterSticker(center, face, snapshot)
                PaletteLabeler.labelCenters(centers, allowed, ruledOut)?.last() ?: open[0]
            }
        }
    }

    /**
     * Whether some sticker of [face] is clearly whiter than [center], both white-balanced on the
     * learned white of [snapshot] if it has one (see [State.balanced]). "Whiter" means a white
     * reading is clearly more plausible ([PaletteLabeler.photoWhiteCost], which also weighs the cast
     * a white reading implies: a beige yellow under mildly warm light is no whiter than a slightly
     * cool white).
     */
    private fun hasWhiterSticker(center: StickerSample, face: List<StickerSample>, snapshot: State): Boolean {
        if (face.isEmpty()) return false
        val balancedCenter = snapshot.balanced(center)
        val centerCost = PaletteLabeler.photoWhiteCost(balancedCenter)
        val centerLuminance = luminance(linearOf(balancedCenter))
        return face.any { other ->
            val balanced = snapshot.balanced(other)
            PaletteLabeler.photoWhiteCost(balanced) + WHITER_MARGIN < centerCost &&
                LiveClassifier.chromaticityDistance(balanced, balancedCenter) >= OTHER_COLOR &&
                luminance(linearOf(balanced)) >= AS_BRIGHT * centerLuminance
        }
    }

    /** Exposure-matched CIEDE2000 distance between a sticker and a learned color. */
    private fun distance(linear: DoubleArray, luminance: Double, reference: Reference): Double {
        val k = if (luminance > 0.0) {
            (reference.luminance / luminance).coerceIn(1.0 / MAX_EXPOSURE_RATIO, MAX_EXPOSURE_RATIO)
        } else {
            MAX_EXPOSURE_RATIO
        }
        val lab = ColorMath.linearToLab(linear[0] * k, linear[1] * k, linear[2] * k)
        return ColorMath.deltaE(lab, reference.lab).toDouble()
    }

    private companion object {
        val COLORS = CubeColor.entries

        /** Supported cube sizes for [learnFaces]. */
        const val MIN_SIZE = 2
        const val MAX_SIZE = 10

        /** Nothing learned. */
        val EMPTY = State(arrayOfNulls(COLORS.size))

        /**
         * Typical spread (deltaE) of one color between faces captured under the same light: exposure
         * differences are matched, what remains is white-balance drift, shading, noise and sampling.
         */
        const val LEARNED_SPREAD = 6.0

        /**
         * Extra cost of a color that is not learned yet. A learned color within about
         * `LEARNED_SPREAD * sqrt(UNLEARNED_PENALTY + 1)` (12 deltaE) of a sticker beats the default
         * rules; beyond that the default rules decide among the colors not learned yet.
         */
        const val UNLEARNED_PENALTY = 3.0

        /** Largest exposure difference (luminance ratio) matched before comparing with a learned color. */
        const val MAX_EXPOSURE_RATIO = 2.0

        /** The learned white is used for white balance up to this [LiveClassifier.whiteDistance]. */
        const val MAX_WHITE_DISTANCE = 1.5

        /**
         * [labelForCenter] and [learnCenters]: the center is ruled out as white by a sticker of the
         * same photo that is more white-like ([PaletteLabeler.photoWhiteCost] lower by
         * [WHITER_MARGIN]), clearly another color ([LiveClassifier.chromaticityDistance] at least
         * [OTHER_COLOR]; two whites in one photo differ by about 0.1, pastel colors and white by 0.7
         * or more) and at least [AS_BRIGHT] times as bright (white is the lightest sticker color;
         * shading varies a little across a face).
         */
        const val WHITER_MARGIN = 0.15
        const val OTHER_COLOR = 0.35
        const val AS_BRIGHT = 0.85

        /** Range of the white-balance gains taken from the learned white. */
        const val MIN_BALANCE = 0.6
        const val MAX_BALANCE = 1.7

        fun linearOf(sample: StickerSample): DoubleArray =
            doubleArrayOf(ColorMath.srgbToLinear(sample.r), ColorMath.srgbToLinear(sample.g), ColorMath.srgbToLinear(sample.b))

        fun luminance(linear: DoubleArray): Double = 0.2126729 * linear[0] + 0.7151522 * linear[1] + 0.0721750 * linear[2]
    }
}
