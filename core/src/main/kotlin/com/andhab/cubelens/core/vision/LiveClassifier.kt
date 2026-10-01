package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Fast single-sticker classification for live feedback while scanning.
 *
 * Works for standard vivid cubes and for knock-offs with pastel, "candy" or muted stickers. A single
 * sample under unknown light can only be judged by what exposure and white balance change little,
 * so the rules rest on chromaticity (channel ratios), hue angle and chroma relative to lightness
 * rather than on absolute lightness or distances to fixed colors:
 *
 *  1. **White.** In log chromaticity of linear RGB, `(ln R/G, ln B/G)`, exposure cancels out and a
 *     white-balance error (von Kries channel gains) is a plain translation. A white sticker therefore
 *     sits on a known line through the origin, from cool (bluish) to very warm (incandescent) casts,
 *     plus some green/magenta tint near the neutral point. The white region hugs that line: wide
 *     across it near neutral, where tints happen, and narrow at the warm and cool ends. That keeps
 *     white under strong casts while pastel stickers stay out, although several of them are no more
 *     colorful than a white under a warm cast: a peach sticker lies about as far along the warm
 *     direction as white under incandescent light, but well off the line; pastel yellow and baby
 *     blue likewise sit beside it, not on it. Clipped (overexposed) channels only bound their ratio,
 *     and are given that slack.
 *  2. **Light, low-chroma colors** (saturation `C* / (L* + 16)` below [VIVID_SATURATION]): hue bands.
 *     Pastel hues differ from vivid ones (pink "red" near 0 degrees, peach "orange" at 60-70, close
 *     to where a vivid yellow lands under a warm cast), so the orange/yellow boundary moves from 71
 *     degrees for saturated colors up to 80 degrees for pastels, and the red band reaches further
 *     into pink and magenta. The saturation ratio is nearly independent of exposure (both chroma
 *     and `L* + 16` scale with the cube root of the light level).
 *  3. **Vivid colors**: the nearest [reference] color in [ColorMath.deltaE] decides between blue, the
 *     remaining whites (b* close to the white reference) and the warm-to-green family, and within that
 *     family the CIELAB hue angle decides in fixed bands (red about 25-35 degrees, orange 50-60,
 *     yellow 90-105, green about 145, boundaries with room for color casts).
 *
 * Tolerance, measured on rendered faces: standard cubes are read essentially perfectly from about 0.15x
 * of normal exposure upwards and under casts up to about 1.6x red / 0.4x blue gain; pastel cubes in
 * normal light (exposure 0.65-1.25x, mild warm or cool casts) about 98.5% of stickers, candy, muted
 * and stickerless cubes over 99.5%. It fails where a single sample is physically ambiguous: a pastel
 * sticker under a cast of the opposite hue looks white (peach under cool light, baby blue under
 * strong warm light), and very dark orange looks red.
 *
 * This is for the live preview only. [AdaptiveLiveClassifier] learns the actual colors of the cube
 * being scanned from the captured centers; the final colors come from [ScanResolver], which compares
 * the stickers of all six scans with each other and compensates the lighting of each scan.
 */
object LiveClassifier {

    /**
     * Typical CIELAB values of standard sticker colors as phone cameras capture them in ordinary
     * indoor light (auto white balance).
     *
     * Calibrated on real phone photos of one standard-colored cube (the photo fixtures in this
     * module's tests): each value is close to the mean of that color's stickers there. Tests that
     * classify those same photos are therefore calibration checks; generalization is checked
     * separately, with references re-derived without the photo being classified (leave one out) and
     * with synthetic faces in nominal sticker colors that were not derived from the photos.
     */
    val reference: Map<CubeColor, Lab> = mapOf(
        CubeColor.WHITE to Lab(84f, -1f, -4f),
        CubeColor.YELLOW to Lab(83f, -13f, 70f),
        CubeColor.GREEN to Lab(60f, -48f, 33f),
        CubeColor.BLUE to Lab(34f, 12f, -50f),
        CubeColor.RED to Lab(39f, 58f, 40f),
        CubeColor.ORANGE to Lab(61f, 39f, 64f),
    )

    // Step 1: the white region in log chromaticity (u, v) = (ln R/G, ln B/G) of linear RGB.
    // Warm casts (more red, less blue) move a white sample along WARM; TINT is perpendicular to it
    // (positive: magenta, negative: green). Measured white-sticker positions: neutral near (0, 0);
    // warm 1.12/0.8 gains at +0.25 along, strong warm 1.3/0.65 at +0.5, incandescent 1.6/0.4 at
    // +1.0; cool 0.88/1.16 at -0.2, strong cool 0.8/1.3 at -0.35 (-0.5 for the bluish white of the
    // photo fixtures); green or magenta tints of about 10% at +-0.3 across, near neutral. Sticker
    // to sticker, whites scatter by about +-0.05 across. The nearest pastel stickers: peach at
    // +0.6..+1.0 along and +0.2..+0.25 across, pastel yellow at +0.85..+1.3 along and -0.4 across,
    // baby blue at -0.4..-0.9 along and -0.3 across.
    private const val WARM_U = 0.456
    private const val WARM_V = -0.890
    private const val TINT_U = 0.890
    private const val TINT_V = 0.456

    /** Half-width of the white region on the magenta side away from neutral (log units): peach is next. */
    private const val MAGENTA_WIDTH = 0.14

    /** Half-width of the white region on the green side away from neutral: pastel yellow and baby blue are next. */
    private const val GREEN_WIDTH = 0.20

    /**
     * Extra half-width near neutral, for green and magenta tints; fades out over [TINT_FADE] along the
     * locus (on the magenta side only towards warm casts: no pastel sticker is magenta of a cool white).
     */
    private const val MAGENTA_TINT_WIDTH = 0.30
    private const val GREEN_TINT_WIDTH = 0.22
    private const val TINT_FADE = 0.26

    /** Extent of the white region along the locus: strong cool to beyond incandescent. */
    private const val WHITE_COOLEST = -0.6
    private const val WHITE_WARMEST = 1.2

    /** Scale of the distance beyond the ends of the locus, log units per unit of [whiteDistance]. */
    private const val WHITE_END_SOFTNESS = 0.15

    /** An 8-bit channel value at or above this may be clipped: its true value could be higher. */
    private const val CLIPPED_LEVEL = 250

    /** How much higher (in log units) a clipped channel may really be, and the search steps for it. */
    private const val CLIP_SLACK = 0.5
    private const val CLIP_STEPS = 5

    /** Linear-light floor of the brightest channel for the white test (sRGB about 23); darker is noise. */
    private const val NEUTRAL_MIN_LINEAR = 0.008

    /** Added to each linear channel before taking ratios, so that zero channels don't blow up. */
    private const val RATIO_EPSILON = 0.002

    /**
     * Step 2/3 switch: samples whose chroma relative to lightness, `C* / (L* + 16)`, is below this
     * are judged as light, low-chroma colors. Standard vivid stickers are at 0.7 (yellow) to 1.3
     * (red, blue); pastel stickers at 0.25-0.5, muted ones at 0.45-0.8.
     */
    internal const val VIVID_SATURATION = 0.6f

    /** Pastel orange/yellow boundary for saturation up to [PASTEL_SATURATION] (degrees). */
    private const val PASTEL_ORANGE_YELLOW_HUE = 80f
    private const val PASTEL_SATURATION = 0.45f

    /** Pastel red/blue boundary on the magenta side: pink "reds" reach about 340 degrees under cool light. */
    private const val PASTEL_MAGENTA_HUE = 310f

    /** Blue/green boundary of the low-chroma bands (between mint near 150-165 and baby blue near 245-270). */
    private const val GREEN_BLUE_HUE = 205f

    /** Step 3: b* (above the white reference) at which a light, weakly chromatic sample turns from white to yellow. */
    private const val WHITE_YELLOW_B = 30f

    // Hue bands (degrees) of the warm-to-green family for saturated colors. Orange/yellow: oranges
    // reach 63-71 degrees on light "candy" cubes under cool light, yellows drop to 76 degrees under
    // incandescent light.
    private const val RED_ORANGE_HUE = 42f
    private const val ORANGE_YELLOW_HUE = 71f
    private const val YELLOW_GREEN_HUE = 120f
    private const val MAGENTA_HUE = 300f // hues from here up to 360 are reds turned bluish

    /** Soft costs: degrees outside a color's hue band per unit of cost (squared). */
    private const val HUE_SOFTNESS = 20.0

    private val colors = CubeColor.entries

    /** [reference] indexed by [CubeColor.ordinal]. */
    private val references: List<Lab> = colors.map { reference.getValue(it) }

    /** The most likely color of [sample]. */
    fun classify(sample: StickerSample): CubeColor = classify(sample, references)

    /**
     * [classify] against other reference colors ([references] indexed by [CubeColor.ordinal]), e.g.
     * references re-derived from part of the calibration data. The references only affect vivid samples.
     */
    internal fun classify(sample: StickerSample, references: List<Lab>): CubeColor {
        if (whiteDistance(sample) < 1.0) return CubeColor.WHITE
        val lab = sample.lab
        val saturation = saturation(lab)
        if (!(saturation >= VIVID_SATURATION)) return lightByHue(lab.hue, saturation)
        var best = colors[0]
        var bestDistance = Float.MAX_VALUE
        for (i in colors.indices) {
            val d = ColorMath.deltaE(lab, references[i])
            if (d < bestDistance) {
                bestDistance = d
                best = colors[i]
            }
        }
        return when (best) {
            CubeColor.BLUE -> CubeColor.BLUE
            CubeColor.WHITE, CubeColor.YELLOW ->
                if (lab.b - references[CubeColor.WHITE.ordinal].b < WHITE_YELLOW_B) CubeColor.WHITE else vividByHue(lab.hue)
            CubeColor.RED, CubeColor.ORANGE, CubeColor.GREEN -> vividByHue(lab.hue)
        }
    }

    /**
     * A cost per color for [sample] (indexed by [CubeColor.ordinal]); the color [classify] returns has
     * the lowest. White costs the squared [whiteDistance] (below 1 inside the white region), any other
     * color 1 plus the squared distance (in units of 20 degrees) of the sample's hue from that color's
     * hue band. Used to rank the alternatives, e.g. when a color is already taken.
     */
    internal fun costs(sample: StickerSample): DoubleArray {
        val costs = costs(whiteDistance(sample), sample.lab)
        val chosen = classify(sample).ordinal
        val min = costs.min()
        if (costs[chosen] > min) costs[chosen] = min - TIE_BREAK
        return costs
    }

    private const val TIE_BREAK = 1e-3

    /** The costs of [costs] for a color with the given [whiteDistance] and CIELAB value [lab], without the classification rules. */
    internal fun costs(whiteDistance: Double, lab: Lab): DoubleArray {
        val hue = lab.hue.toDouble()
        val saturation = saturation(lab)
        val oy = orangeYellowHue(saturation).toDouble()
        val magenta = magentaHue(saturation).toDouble()
        val costs = DoubleArray(colors.size)
        for (c in colors) {
            costs[c.ordinal] = when (c) {
                CubeColor.WHITE -> whiteDistance * whiteDistance
                CubeColor.RED -> 1.0 + bandCost(hue, magenta, RED_ORANGE_HUE + 360.0)
                CubeColor.ORANGE -> 1.0 + bandCost(hue, RED_ORANGE_HUE.toDouble(), oy)
                CubeColor.YELLOW -> 1.0 + bandCost(hue, oy, YELLOW_GREEN_HUE.toDouble())
                CubeColor.GREEN -> 1.0 + bandCost(hue, YELLOW_GREEN_HUE.toDouble(), GREEN_BLUE_HUE.toDouble())
                CubeColor.BLUE -> 1.0 + bandCost(hue, GREEN_BLUE_HUE.toDouble(), magenta)
            }
        }
        return costs
    }

    /** Squared angular distance (in [HUE_SOFTNESS] units) of [hue] from the band [from] until [to] (degrees, `from < to`). */
    private fun bandCost(hue: Double, from: Double, to: Double): Double {
        if (hue.isNaN()) return 0.0
        val offset = ((hue - from) % 360.0 + 360.0) % 360.0
        if (offset < to - from) return 0.0
        val outside = minOf(offset - (to - from), 360.0 - offset)
        val x = outside / HUE_SOFTNESS
        return x * x
    }

    /**
     * How far [sample] is from looking like a white sticker under some plausible light: below 1
     * inside the white region (see the class documentation), growing with the distance outside it.
     * Infinite for samples too dark to tell.
     */
    internal fun whiteDistance(sample: StickerSample): Double = whiteDistance(
        ColorMath.srgbToLinear(sample.r), ColorMath.srgbToLinear(sample.g), ColorMath.srgbToLinear(sample.b),
        sample.r >= CLIPPED_LEVEL, sample.g >= CLIPPED_LEVEL, sample.b >= CLIPPED_LEVEL,
    )

    /**
     * [whiteDistance] of the linear RGB color ([r], [g], [b]). A clipped channel's true value may be
     * higher than measured, which moves its ratios by up to [CLIP_SLACK]; the most white-like
     * reading within that slack counts.
     */
    internal fun whiteDistance(
        r: Double, g: Double, b: Double,
        clippedR: Boolean = false, clippedG: Boolean = false, clippedB: Boolean = false,
    ): Double {
        if (!(maxOf(r, g, b) >= NEUTRAL_MIN_LINEAR)) return Double.POSITIVE_INFINITY
        val u = ln((r + RATIO_EPSILON) / (g + RATIO_EPSILON))
        val v = ln((b + RATIO_EPSILON) / (g + RATIO_EPSILON))
        val stepsR = if (clippedR) CLIP_STEPS else 0
        val stepsG = if (clippedG) CLIP_STEPS else 0
        val stepsB = if (clippedB) CLIP_STEPS else 0
        val step = CLIP_SLACK / CLIP_STEPS
        var best = Double.POSITIVE_INFINITY
        for (i in 0..stepsR) {
            for (j in 0..stepsG) {
                for (k in 0..stepsB) {
                    best = minOf(best, locusDistance(u + (i - j) * step, v + (k - j) * step))
                }
            }
        }
        return best
    }

    /**
     * How warm a cast [sample] would have to be under to be a white sticker: its position along the
     * white locus in log units (0 neutral, about 0.5 for strong warm and 1 for incandescent light,
     * negative for cool light).
     */
    internal fun warmth(sample: StickerSample): Double {
        val r = ColorMath.srgbToLinear(sample.r) + RATIO_EPSILON
        val g = ColorMath.srgbToLinear(sample.g) + RATIO_EPSILON
        val b = ColorMath.srgbToLinear(sample.b) + RATIO_EPSILON
        return ln(r / g) * WARM_U + ln(b / g) * WARM_V
    }

    /**
     * Distance between the log chromaticities `(ln R/G, ln B/G)` of [x] and [y]: exposure-independent,
     * and in one photo also independent of its cast. Two stickers of one color differ by about 0.1;
     * a pastel color and white by 0.7 or more.
     */
    internal fun chromaticityDistance(x: StickerSample, y: StickerSample): Double {
        fun channel(c: Int) = ColorMath.srgbToLinear(c) + RATIO_EPSILON
        val du = ln(channel(x.r) / channel(x.g)) - ln(channel(y.r) / channel(y.g))
        val dv = ln(channel(x.b) / channel(x.g)) - ln(channel(y.b) / channel(y.g))
        return sqrt(du * du + dv * dv)
    }

    /** Normalized distance of log chromaticity ([u], [v]) from the white region's center line. */
    private fun locusDistance(u: Double, v: Double): Double {
        val along = u * WARM_U + v * WARM_V
        val across = u * TINT_U + v * TINT_V
        val fade = exp(-(along / TINT_FADE) * (along / TINT_FADE))
        val width = if (across >= 0.0) {
            MAGENTA_WIDTH + MAGENTA_TINT_WIDTH * (if (along <= 0.0) 1.0 else fade)
        } else {
            GREEN_WIDTH + GREEN_TINT_WIDTH * fade
        }
        val x = across / width
        val beyond = when {
            along < WHITE_COOLEST -> (WHITE_COOLEST - along) / WHITE_END_SOFTNESS
            along > WHITE_WARMEST -> (along - WHITE_WARMEST) / WHITE_END_SOFTNESS
            else -> 0.0
        }
        return sqrt(x * x + beyond * beyond)
    }

    /** Chroma relative to lightness, `C* / (L* + 16)`: about exposure independent. */
    internal fun saturation(lab: Lab): Float = lab.chroma / (lab.l + 16f)

    /** Orange/yellow hue boundary at [saturation]: 71 degrees for vivid colors, up to 80 for pastels. */
    private fun orangeYellowHue(saturation: Float): Float = interpolate(saturation, PASTEL_ORANGE_YELLOW_HUE, ORANGE_YELLOW_HUE)

    /** Blue/red hue boundary on the magenta side at [saturation]. */
    private fun magentaHue(saturation: Float): Float = interpolate(saturation, PASTEL_MAGENTA_HUE, MAGENTA_HUE)

    /** [pastel] up to [PASTEL_SATURATION], [vivid] from [VIVID_SATURATION], linear in between. */
    private fun interpolate(saturation: Float, pastel: Float, vivid: Float): Float {
        if (!(saturation > PASTEL_SATURATION)) return pastel
        if (saturation >= VIVID_SATURATION) return vivid
        val t = (saturation - PASTEL_SATURATION) / (VIVID_SATURATION - PASTEL_SATURATION)
        return pastel + t * (vivid - pastel)
    }

    /** Step 2: the color of a light, low-chroma sample with CIELAB hue angle [hue] (degrees). */
    private fun lightByHue(hue: Float, saturation: Float): CubeColor = when {
        hue < RED_ORANGE_HUE || hue >= magentaHue(saturation) -> CubeColor.RED
        hue < orangeYellowHue(saturation) -> CubeColor.ORANGE
        hue < YELLOW_GREEN_HUE -> CubeColor.YELLOW
        hue < GREEN_BLUE_HUE -> CubeColor.GREEN
        else -> CubeColor.BLUE
    }

    /** Step 3: the red, orange, yellow or green vivid sticker with CIELAB hue angle [hue] (degrees). */
    private fun vividByHue(hue: Float): CubeColor = when {
        hue < RED_ORANGE_HUE || hue >= MAGENTA_HUE -> CubeColor.RED
        hue < ORANGE_YELLOW_HUE -> CubeColor.ORANGE
        hue < YELLOW_GREEN_HUE -> CubeColor.YELLOW
        else -> CubeColor.GREEN
    }

    /** Distances from [lab] to every reference color (default: [reference]), indexed by [CubeColor.ordinal]. */
    internal fun distances(lab: Lab, references: List<Lab> = this.references): FloatArray =
        FloatArray(colors.size) { ColorMath.deltaE(lab, references[it]) }
}
