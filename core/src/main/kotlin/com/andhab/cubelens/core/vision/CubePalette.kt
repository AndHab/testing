package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the stickers of a scanned cube look like, ready for drawing that cube.
 *
 * [ScanResolver] estimates it from the scans (see [ScanAnalysis.palette]). Standard vivid cubes look
 * better in the app's stock colors than in camera-measured ones, so for them [isStandardLike] is true
 * and the app should keep its stock palette ([STANDARD] has the same values). Pastel and other
 * unusual cubes are drawn in [colors], so that a pink "red" shows as pink.
 */
data class CubePaletteEstimate(
    /**
     * Display color of every sticker color as opaque 0xAARRGGBB sRGB: measured, white-balanced on the
     * cube's white, and lifted a little in lightness and chroma to read well on a dark background,
     * keeping each color's hue and how pastel it is.
     */
    val colors: Map<CubeColor, Int>,
    /** Whether every color is close to the standard vivid look (then prefer the stock palette). */
    val isStandardLike: Boolean,
) {
    init {
        require(colors.keys == CubeColor.entries.toSet()) { "Need a color for each of the six sticker colors, got ${colors.keys}" }
    }

    companion object {
        /** The app's stock vivid sticker colors. */
        val STANDARD = CubePaletteEstimate(
            mapOf(
                CubeColor.WHITE to 0xFFF4F4F7.toInt(),
                CubeColor.YELLOW to 0xFFFFD500.toInt(),
                CubeColor.GREEN to 0xFF14C95E.toInt(),
                CubeColor.BLUE to 0xFF1F6BFF.toInt(),
                CubeColor.RED to 0xFFF2243C.toInt(),
                CubeColor.ORANGE to 0xFFFF7B00.toInt(),
            ),
            isStandardLike = true,
        )
    }
}

/**
 * Estimates a [CubePaletteEstimate] from the measured colors of a cube's six clusters.
 *
 *  1. White balance: the per-channel gains that make the white cluster neutral, if it is plausibly a
 *     white under some cast ([LiveClassifier.whiteDistance]); otherwise the most neutral of the light
 *     clusters; otherwise none.
 *  2. Exposure: everything is scaled so that the reference white has luminance [WHITE_LUMINANCE].
 *  3. Standard-likeness: each chromatic color is compared (CIEDE2000) with the same color of a few
 *     standard looks normalized the same way: the camera-calibrated [LiveClassifier.reference], the
 *     nominal brand colors and the stock display colors. Every color within [STANDARD_TOLERANCE] of
 *     one of them, and white usable as the white reference, means standard-like.
 *  4. Display: in LCh, lightness is lifted towards 100 by [LIGHTNESS_LIFT] and chroma raised by
 *     [CHROMA_LIFT], hue unchanged, then chroma is reduced as needed to fit the sRGB gamut.
 */
internal object PaletteEstimator {

    /** Luminance (linear, 0..1) the white reference is scaled to: L* about 94. */
    private const val WHITE_LUMINANCE = 0.86

    /** A cluster is usable as the white reference up to this [LiveClassifier.whiteDistance]. */
    private const val MAX_WHITE_DISTANCE = 1.5

    /** Fallback white candidates must have at least this fraction of the lightest cluster's luminance. */
    private const val LIGHT_FRACTION = 0.6

    /** Largest CIEDE2000 (kL = 1) distance from a standard look for a color to count as standard. */
    private const val STANDARD_TOLERANCE = 8.0

    /** Fraction of the distance to L* = 100 that lightness is raised by for display. */
    private const val LIGHTNESS_LIFT = 0.2

    /** Factor chroma is raised by for display (before gamut mapping). */
    private const val CHROMA_LIFT = 1.15

    /** Highest display lightness. */
    private const val MAX_DISPLAY_LIGHTNESS = 97.0

    private val COLORS = CubeColor.entries

    /**
     * The palette of a cube whose sticker colors are [linear] (linear RGB per color, in one common
     * light, e.g. the lighting-compensated median of each color's stickers).
     */
    fun estimate(linear: Map<CubeColor, DoubleArray>): CubePaletteEstimate {
        require(linear.keys == COLORS.toSet()) { "Need all six colors" }
        val (normalized, whiteIsReference) = normalize(linear)
        val standardLike = whiteIsReference && COLORS.all { c ->
            c == CubeColor.WHITE || standardLooks.any { look -> distance(normalized.getValue(c), look.getValue(c)) <= STANDARD_TOLERANCE }
        }
        return CubePaletteEstimate(COLORS.associateWith { toDisplay(normalized.getValue(it)) }, standardLike)
    }

    /**
     * White-balanced, exposure-normalized CIELAB of each color, and whether the white cluster served
     * as the white reference.
     */
    internal fun normalize(linear: Map<CubeColor, DoubleArray>): Pair<Map<CubeColor, Lab>, Boolean> {
        val whiteDistance = linear.mapValues { (_, c) -> LiveClassifier.whiteDistance(c[0], c[1], c[2]) }
        val lightest = linear.values.maxOf { luminance(it) }
        val reference: CubeColor? = when {
            whiteDistance.getValue(CubeColor.WHITE) <= MAX_WHITE_DISTANCE -> CubeColor.WHITE
            else -> COLORS
                .filter { luminance(linear.getValue(it)) >= LIGHT_FRACTION * lightest }
                .minByOrNull { whiteDistance.getValue(it) }
                ?.takeIf { whiteDistance.getValue(it) <= MAX_WHITE_DISTANCE }
        }
        val gains = reference?.let { PaletteLabeler.neutralizingGains(linear.getValue(it)) } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val balanced = linear.mapValues { (_, c) -> DoubleArray(3) { c[it] * gains[it] } }
        val referenceLuminance = luminance(balanced.getValue(reference ?: COLORS.maxBy { luminance(balanced.getValue(it)) }))
        val scale = if (referenceLuminance > 0.0) (WHITE_LUMINANCE / referenceLuminance).coerceIn(0.1, 20.0) else 1.0
        val normalized = balanced.mapValues { (_, c) -> ColorMath.linearToLab(c[0] * scale, c[1] * scale, c[2] * scale) }
        return normalized to (reference == CubeColor.WHITE)
    }

    /** Standard vivid cubes, normalized like a measured palette. */
    private val standardLooks: List<Map<CubeColor, Lab>> by lazy {
        val calibrated = LiveClassifier.reference.mapValues { (_, lab) -> ColorMath.labToLinear(lab).map { it.coerceAtLeast(0.0) }.toDoubleArray() }
        val nominal = mapOf(
            CubeColor.WHITE to 0xFFFFFF, CubeColor.YELLOW to 0xFFD500, CubeColor.GREEN to 0x009B48,
            CubeColor.BLUE to 0x0046AD, CubeColor.RED to 0xB71234, CubeColor.ORANGE to 0xFF5800,
        ).mapValues { (_, rgb) -> linearOf(rgb) }
        val stock = CubePaletteEstimate.STANDARD.colors.mapValues { (_, argb) -> linearOf(argb) }
        listOf(calibrated, nominal, stock).map { normalize(it).first }
    }

    private fun linearOf(rgb: Int): DoubleArray = doubleArrayOf(
        ColorMath.srgbToLinear((rgb shr 16) and 0xFF),
        ColorMath.srgbToLinear((rgb shr 8) and 0xFF),
        ColorMath.srgbToLinear(rgb and 0xFF),
    )

    private fun distance(x: Lab, y: Lab): Double =
        ColorMath.ciede2000(x.l.toDouble(), x.a.toDouble(), x.b.toDouble(), y.l.toDouble(), y.a.toDouble(), y.b.toDouble())

    /** The display color (0xFFRRGGBB) of a normalized color: lifted, then fitted into sRGB at constant hue. */
    internal fun toDisplay(lab: Lab): Int {
        val l = (100.0 - (100.0 - lab.l) * (1.0 - LIGHTNESS_LIFT)).coerceIn(0.0, MAX_DISPLAY_LIGHTNESS)
        val chroma = sqrt(lab.a.toDouble() * lab.a + lab.b.toDouble() * lab.b) * CHROMA_LIFT
        val h = atan2(lab.b.toDouble(), lab.a.toDouble())
        fun at(c: Double) = Lab(l.toFloat(), (c * cos(h)).toFloat(), (c * sin(h)).toFloat())
        if (inGamut(at(chroma))) return ColorMath.labToArgb(at(chroma))
        var lo = 0.0
        var hi = chroma
        repeat(24) {
            val mid = (lo + hi) / 2
            if (inGamut(at(mid))) lo = mid else hi = mid
        }
        return ColorMath.labToArgb(at(lo))
    }

    private fun inGamut(lab: Lab): Boolean = ColorMath.labToLinear(lab).all { it in -1e-4..1.0 + 1e-4 }

    private fun luminance(c: DoubleArray): Double = 0.2126729 * c[0] + 0.7151522 * c[1] + 0.0721750 * c[2]
}
