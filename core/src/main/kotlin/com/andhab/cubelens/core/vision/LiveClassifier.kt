package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import kotlin.math.ln

/**
 * Fast single-sticker classification for live feedback while scanning.
 *
 * Three steps, each aimed at what a single sample can and cannot tell under unknown light:
 *  1. Near-neutral samples are white. This is decided on the ratios R/G and B/G of linear RGB, which
 *     don't change with exposure, and the accepted range is wide enough for strong white-balance
 *     casts: a white sticker in dim light or warm light is still far more neutral than any colored
 *     sticker, whose weakest channel is a small fraction of its strongest.
 *  2. Otherwise the nearest [reference] color in [ColorMath.deltaE] decides between blue, the
 *     remaining whites (b* close to the white reference) and the warm-to-green family.
 *  3. Within that family (red, orange, yellow, green) the CIELAB hue angle decides, in fixed bands.
 *     Exposure and shading change these stickers' lightness and chroma a lot but their hue much
 *     less, and the bands sit between the hues of standard stickers (red about 25-35 degrees, orange
 *     50-60, yellow 90-105, green about 145) with room for color casts.
 *
 * This is for the live preview only. On rendered faces it reads at least 99.9% of stickers correctly
 * from about 0.15x of normal exposure upwards and under casts up to about 1.6x red / 0.4x blue gain,
 * for both the calibration colors and nominal brand colors; it fails first on very dark orange (taken
 * for red), on white under even stronger warm casts (taken for yellow) and on dim stickers swamped by
 * a specular highlight. The final colors come from [ScanResolver], which compares the stickers of
 * all six scans with each other and compensates the lighting of each scan.
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

    // Step 1: the white box, as natural logs of linear-RGB ratios. A white sticker sits near (0, 0);
    // casts move it by the log of the white-balance gains (warm light: R/G up, B/G down). The nearest
    // colored stickers are light green (ln R/G about -1.3), orange (ln R/G above 1.1) and yellow
    // (ln B/G below -1.5, typically about -2.5).
    private const val NEUTRAL_MIN_LOG_RG = -0.6
    private const val NEUTRAL_MAX_LOG_RG = 0.85
    private const val NEUTRAL_MIN_LOG_BG = -1.25
    private const val NEUTRAL_MAX_LOG_BG = 0.9

    /** Linear-light floor of the brightest channel for the ratio test (sRGB about 23); darker is noise. */
    private const val NEUTRAL_MIN_LINEAR = 0.008

    /** Added to each linear channel before taking ratios, so that zero channels don't blow up. */
    private const val RATIO_EPSILON = 0.002

    /** Step 2: b* (above the white reference) at which a light, weakly chromatic sample turns from white to yellow. */
    private const val WHITE_YELLOW_B = 30f

    // Step 3: hue bands (degrees) of the warm-to-green family.
    private const val RED_ORANGE_HUE = 42f
    private const val ORANGE_YELLOW_HUE = 68f
    private const val YELLOW_GREEN_HUE = 120f
    private const val MAGENTA_HUE = 300f // hues from here up to 360 are reds turned bluish

    private val colors = CubeColor.entries

    /** [reference] indexed by [CubeColor.ordinal]. */
    private val references: List<Lab> = colors.map { reference.getValue(it) }

    /** The most likely color of [sample]. */
    fun classify(sample: StickerSample): CubeColor = classify(sample, references)

    /**
     * [classify] against other reference colors ([references] indexed by [CubeColor.ordinal]), e.g.
     * references re-derived from part of the calibration data.
     */
    internal fun classify(sample: StickerSample, references: List<Lab>): CubeColor {
        if (isNeutral(sample)) return CubeColor.WHITE
        val lab = sample.lab
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
                if (lab.b - references[CubeColor.WHITE.ordinal].b < WHITE_YELLOW_B) CubeColor.WHITE else byHue(lab.hue)
            CubeColor.RED, CubeColor.ORANGE, CubeColor.GREEN -> byHue(lab.hue)
        }
    }

    /** Whether [sample] is close enough to neutral (in exposure-independent chromaticity) to be white. */
    private fun isNeutral(sample: StickerSample): Boolean {
        val r = ColorMath.srgbToLinear(sample.r)
        val g = ColorMath.srgbToLinear(sample.g)
        val b = ColorMath.srgbToLinear(sample.b)
        if (maxOf(r, g, b) < NEUTRAL_MIN_LINEAR) return false
        val logRg = ln((r + RATIO_EPSILON) / (g + RATIO_EPSILON))
        val logBg = ln((b + RATIO_EPSILON) / (g + RATIO_EPSILON))
        return logRg in NEUTRAL_MIN_LOG_RG..NEUTRAL_MAX_LOG_RG && logBg in NEUTRAL_MIN_LOG_BG..NEUTRAL_MAX_LOG_BG
    }

    /** The red, orange, yellow or green sticker with CIELAB hue angle [hue] (degrees). */
    private fun byHue(hue: Float): CubeColor = when {
        hue < RED_ORANGE_HUE || hue >= MAGENTA_HUE -> CubeColor.RED
        hue < ORANGE_YELLOW_HUE -> CubeColor.ORANGE
        hue < YELLOW_GREEN_HUE -> CubeColor.YELLOW
        else -> CubeColor.GREEN
    }

    /** Distances from [lab] to every reference color (default: [reference]), indexed by [CubeColor.ordinal]. */
    internal fun distances(lab: Lab, references: List<Lab> = this.references): FloatArray =
        FloatArray(colors.size) { ColorMath.deltaE(lab, references[it]) }
}
