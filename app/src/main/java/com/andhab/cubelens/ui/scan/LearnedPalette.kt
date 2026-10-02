package com.andhab.cubelens.ui.scan

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.vision.ColorMath
import com.andhab.cubelens.core.vision.Lab
import com.andhab.cubelens.core.vision.LiveClassifier
import com.andhab.cubelens.core.vision.StickerSample
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The display colors of the cube being scanned, estimated from the faces captured so far, so the
 * live readout and the thumbnails show a pastel cube in its own colors while it is being scanned.
 *
 * Every captured sticker counts towards the color it is currently read as (the session's adaptive
 * classifier has learned the cube's colors from the captured faces, so these labels follow what it
 * learned). Per color:
 *
 *  1. **Measure**: the per-channel median of its stickers in linear light, robust to glare and to a
 *     stray misread sticker.
 *  2. **Standard or not**: if every measured color looks like a standard vivid sticker as a phone
 *     camera sees it ([LiveClassifier.reference], compared exposure-matched with
 *     [ColorMath.deltaE]), the stock colors are kept: they look better than camera-measured ones.
 *  3. **Normalize**: white-balanced on the cube's white when it has been seen and looks like a white
 *     plastic under some cast, and exposed so that white (or, without one, the lightest color, by a
 *     limited amount) sits at the brightness of a display white.
 *  4. **Display**: like the final review palette, lightness lifted a little towards white and chroma
 *     raised a little, hue unchanged, fitted into sRGB at constant hue, so muted camera colors read
 *     on the dark UI while a pastel stays a pastel.
 *
 * Colors not seen yet keep their stock look (a [com.andhab.cubelens.ui.theme.StickerPalette] falls
 * back to it for missing labels).
 */
internal object LearnedPalette {

    /**
     * Display colors (opaque 0xAARRGGBB) of the colors seen on [faces], each face's stickers read as
     * [labels] (same shape); empty if nothing was captured or the cube looks standard.
     */
    fun estimate(faces: List<List<StickerSample>>, labels: List<List<CubeColor>>): Map<CubeColor, Int> {
        require(faces.size == labels.size) { "Need labels for every face: ${faces.size} faces, ${labels.size} labels" }
        val groups = HashMap<CubeColor, MutableList<StickerSample>>()
        faces.forEachIndexed { f, face ->
            require(face.size == labels[f].size) { "Face $f has ${face.size} stickers but ${labels[f].size} labels" }
            face.forEachIndexed { i, sample -> groups.getOrPut(labels[f][i]) { ArrayList() } += sample }
        }
        if (groups.isEmpty()) return emptyMap()
        val measured = groups.mapValues { (_, samples) -> medianLinear(samples) }
        if (measured.all { (color, linear) -> looksStandard(color, linear) }) return emptyMap()
        return normalize(measured).mapValues { (_, lab) -> toDisplay(lab) }
    }

    /**
     * Whether [linear] (a measured color) is within [STANDARD_TOLERANCE] of how a camera sees a
     * standard sticker of [color], after matching its exposure to that reference.
     */
    internal fun looksStandard(color: CubeColor, linear: DoubleArray): Boolean {
        val reference = LiveClassifier.reference.getValue(color)
        val referenceLuminance = luminance(ColorMath.labToLinear(reference))
        val ownLuminance = luminance(linear)
        val k = if (ownLuminance > 0.0) (referenceLuminance / ownLuminance).coerceIn(1 / MAX_EXPOSURE_RATIO, MAX_EXPOSURE_RATIO) else MAX_EXPOSURE_RATIO
        val lab = ColorMath.linearToLab(linear[0] * k, linear[1] * k, linear[2] * k)
        return ColorMath.deltaE(lab, reference) <= STANDARD_TOLERANCE
    }

    /** White-balanced, exposure-normalized CIELAB of each measured color (see the class documentation). */
    private fun normalize(measured: Map<CubeColor, DoubleArray>): Map<CubeColor, Lab> {
        val white = measured[CubeColor.WHITE]?.takeIf { labOf(it).chroma <= MAX_WHITE_CHROMA }
        val gains = white?.let(::neutralizingGains) ?: doubleArrayOf(1.0, 1.0, 1.0)
        val balanced = measured.mapValues { (_, c) -> DoubleArray(3) { c[it] * gains[it] } }
        val scale = if (white != null) {
            val whiteLuminance = luminance(balanced.getValue(CubeColor.WHITE))
            if (whiteLuminance > 0.0) (WHITE_LUMINANCE / whiteLuminance).coerceIn(MIN_SCALE, MAX_SCALE) else 1.0
        } else {
            // No white to go by: brighten (never darken) by a limited amount, so a dim capture is not
            // shown murky but a lone green is not blown up into a pastel either.
            val lightest = balanced.values.maxOf(::luminance)
            if (lightest > 0.0) (WHITE_LUMINANCE / lightest).coerceIn(1.0, MAX_SCALE_WITHOUT_WHITE) else 1.0
        }
        return balanced.mapValues { (_, c) -> ColorMath.linearToLab(c[0] * scale, c[1] * scale, c[2] * scale) }
    }

    /** The display color (0xFFRRGGBB) of a normalized color: lifted, then fitted into sRGB at constant hue. */
    private fun toDisplay(lab: Lab): Int {
        val l = (100.0 - (100.0 - lab.l) * (1.0 - LIGHTNESS_LIFT)).coerceIn(0.0, MAX_DISPLAY_LIGHTNESS)
        val chroma = lab.chroma.toDouble() * CHROMA_LIFT
        val h = atan2(lab.b.toDouble(), lab.a.toDouble())
        fun at(c: Double) = Lab(l.toFloat(), (c * cos(h)).toFloat(), (c * sin(h)).toFloat())
        if (inGamut(at(chroma))) return ColorMath.labToArgb(at(chroma))
        var lo = 0.0
        var hi = chroma
        repeat(GAMUT_STEPS) {
            val mid = (lo + hi) / 2
            if (inGamut(at(mid))) lo = mid else hi = mid
        }
        return ColorMath.labToArgb(at(lo))
    }

    /** Per-channel gains that make [white] neutral at its own luminance, kept within a sane range. */
    private fun neutralizingGains(white: DoubleArray): DoubleArray {
        val y = luminance(white)
        return DoubleArray(3) { if (white[it] > 0.0) (y / white[it]).coerceIn(MIN_GAIN, MAX_GAIN) else 1.0 }
    }

    private fun medianLinear(samples: List<StickerSample>): DoubleArray {
        fun median(channel: (StickerSample) -> Int): Double {
            val sorted = samples.map(channel).sorted()
            val mid = sorted.size / 2
            val value = if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
            return linear(value)
        }
        return doubleArrayOf(median { it.r }, median { it.g }, median { it.b })
    }

    /** Linear light of an 8-bit sRGB value that may lie between two integers (a median of two). */
    private fun linear(srgb: Double): Double {
        val low = srgb.toInt()
        val t = srgb - low
        return ColorMath.srgbToLinear(low) * (1 - t) + ColorMath.srgbToLinear(low + 1) * t
    }

    private fun labOf(linear: DoubleArray): Lab = ColorMath.linearToLab(linear[0], linear[1], linear[2])

    private fun inGamut(lab: Lab): Boolean = ColorMath.labToLinear(lab).all { it in -GAMUT_SLACK..1.0 + GAMUT_SLACK }

    private fun luminance(c: DoubleArray): Double = 0.2126729 * c[0] + 0.7151522 * c[1] + 0.0721750 * c[2]

    /** Largest [ColorMath.deltaE] from the camera's view of a standard sticker that still counts as standard. */
    private const val STANDARD_TOLERANCE = 12.0

    /** Largest exposure difference (luminance ratio) matched before comparing with a standard sticker. */
    private const val MAX_EXPOSURE_RATIO = 2.0

    /** A measured white is used for white balance up to this CIELAB chroma (a white under a cast). */
    private const val MAX_WHITE_CHROMA = 30f

    /** Luminance (linear) white is exposed to: L* about 94. */
    private const val WHITE_LUMINANCE = 0.86

    private const val MIN_SCALE = 0.5
    private const val MAX_SCALE = 3.0

    /** Without a white, the most a dim capture is brightened. */
    private const val MAX_SCALE_WITHOUT_WHITE = 1.6

    private const val MIN_GAIN = 0.6
    private const val MAX_GAIN = 1.7

    /** Fraction of the distance to L* = 100 that lightness is raised by for display. */
    private const val LIGHTNESS_LIFT = 0.2

    /** Factor chroma is raised by for display (before gamut fitting). */
    private const val CHROMA_LIFT = 1.15

    /** Highest display lightness, so a white sticker still shows its gloss. */
    private const val MAX_DISPLAY_LIGHTNESS = 97.0

    private const val GAMUT_STEPS = 24
    private const val GAMUT_SLACK = 1e-4
}
