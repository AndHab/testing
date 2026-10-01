package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor

/**
 * Live sticker classification that learns the actual colors of the cube being scanned.
 *
 * Knock-off cubes come in every shade, and the default rules of [LiveClassifier] can only guess what
 * a pastel or muted sticker is meant to be. The center sticker of each captured face shows exactly
 * what that face's color looks like on this cube, in this light. So the scanning screen
 * [learn]s each captured center (labelled with [labelForCenter]), and [classify] then compares
 * stickers with the learned colors:
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
 * Thread-safe: [classify] and [labelForCenter] may run on a camera analyzer thread while [learn],
 * [forget] and [reset] are called on the UI thread. The learned state is an immutable snapshot that
 * writers replace atomically, so readers never block and always see a consistent set of colors.
 */
class AdaptiveLiveClassifier {

    /** One learned color: the center sample's CIELAB, linear RGB and luminance. */
    private class Reference(val lab: Lab, val linear: DoubleArray, val luminance: Double)

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

        /** CIELAB of a learned color, white-balanced like [balanced]. */
        fun balancedLab(reference: Reference): Lab {
            val gains = whiteBalance ?: return reference.lab
            val c = reference.linear
            return ColorMath.linearToLab(c[0] * gains[0], c[1] * gains[1], c[2] * gains[2])
        }
    }

    @Volatile
    private var state = State(arrayOfNulls(COLORS.size))

    private val lock = Any()

    /** CIELAB of each learned color (of the center sample it was learned from). */
    val learned: Map<CubeColor, Lab>
        get() {
            val snapshot = state.references
            return COLORS.mapNotNull { c -> snapshot[c.ordinal]?.let { c to it.lab } }.toMap()
        }

    /** Remembers that [center] (a captured face's center sticker) shows what [color] looks like on this cube. */
    fun learn(color: CubeColor, center: StickerSample) {
        val linear = linearOf(center)
        val reference = Reference(ColorMath.linearToLab(linear[0], linear[1], linear[2]), linear, luminance(linear))
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
            state = State(arrayOfNulls(COLORS.size))
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
     * The color to learn a newly captured face's [center] as: the most likely one among the colors
     * not learned yet, judged by the default rules (white-balanced on the learned white, if any) and
     * by where its hue falls between the hues of the colors already learned (see [PaletteLabeler]).
     * Six centers captured one after another, each [learn]ed with the label returned here, therefore
     * always get six different labels. When every color is learned already, the nearest learned color.
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
                val known = COLORS.mapNotNull { c -> references[c.ordinal]?.let { c to snapshot.balancedLab(it) } }.toMap()
                val balancedCenter = snapshot.balanced(center)
                val whiteRuledOut = CubeColor.WHITE in open && hasWhiterSticker(balancedCenter, face, snapshot)
                PaletteLabeler.labelOne(balancedCenter, known, open, whiteRuledOut)
            }
        }
    }

    /** Whether some sticker of [face] is clearly whiter than [center] (both white-balanced like [State.balanced]). */
    private fun hasWhiterSticker(center: StickerSample, face: List<StickerSample>, snapshot: State): Boolean {
        if (face.isEmpty()) return false
        val centerDistance = LiveClassifier.whiteDistance(center)
        val centerLuminance = luminance(linearOf(center))
        return face.any { other ->
            val balanced = snapshot.balanced(other)
            LiveClassifier.whiteDistance(balanced) + WHITER_MARGIN < centerDistance &&
                LiveClassifier.chromaticityDistance(balanced, center) >= OTHER_COLOR &&
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
         * [labelForCenter]: the center is ruled out as white by a sticker of the same photo that is
         * more white-like ([LiveClassifier.whiteDistance] smaller by [WHITER_MARGIN]), clearly another
         * color ([LiveClassifier.chromaticityDistance] at least [OTHER_COLOR]; two whites in one photo
         * differ by about 0.1, pastel colors and white by 0.7 or more) and at least [AS_BRIGHT] times
         * as bright (white is the lightest sticker color; shading varies a little across a face).
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
