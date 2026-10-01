package com.andhab.cubelens.core.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * sRGB / CIELAB conversions and color distances.
 *
 * Conversions follow IEC 61966-2-1 (sRGB transfer curve and primaries) and CIE 15 (CIELAB) with the
 * D65 reference white, so `srgbToLab(255, 255, 255)` is L=100, a=b=0.
 */
object ColorMath {

    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883
    private const val EPSILON = 216.0 / 24389.0 // (6/29)^3
    private const val KAPPA = 24389.0 / 27.0

    /** sRGB-encoded 0..255 to linear light 0..1. */
    private val LINEAR = DoubleArray(256) { i ->
        val c = i / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /**
     * Lightness weight used by [deltaE]. 2 is the CIE "textiles" parametric factor: lightness
     * differences count half as much as chroma and hue differences.
     */
    const val LIGHTNESS_WEIGHT = 2.0

    /** The linear-light value (0..1) of an 8-bit sRGB channel value. */
    fun srgbToLinear(channel: Int): Double = LINEAR[channel.coerceIn(0, 255)]

    /** The 8-bit sRGB channel value of a linear-light value (clamped to 0..1). */
    fun linearToSrgb(linear: Double): Int {
        val c = linear.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0).roundToInt().coerceIn(0, 255)
    }

    /** CIELAB of an 8-bit sRGB color. */
    fun srgbToLab(r: Int, g: Int, b: Int): Lab = linearToLab(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))

    /**
     * CIELAB of a linear-light RGB color (sRGB primaries). Values above 1 are allowed and give
     * L above 100, which is useful for exposure-compensated colors.
     */
    fun linearToLab(r: Double, g: Double, b: Double): Lab {
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Lab((116.0 * fy - 16.0).toFloat(), (500.0 * (fx - fy)).toFloat(), (200.0 * (fy - fz)).toFloat())
    }

    /** Linear-light RGB (sRGB primaries, may fall outside 0..1 for out-of-gamut colors) of [lab]. */
    fun labToLinear(lab: Lab): DoubleArray {
        val fy = (lab.l + 16.0) / 116.0
        val fx = fy + lab.a / 500.0
        val fz = fy - lab.b / 200.0
        val x = finv(fx) * XN
        val y = finv(fy) * YN
        val z = finv(fz) * ZN
        return doubleArrayOf(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
        )
    }

    /** 0xFFRRGGBB sRGB color of [lab], clamped to the sRGB gamut. */
    fun labToArgb(lab: Lab): Int {
        val (r, g, b) = labToLinear(lab)
        return (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
    }

    /**
     * Perceptual distance between two colors: CIEDE2000 with lightness weight
     * [LIGHTNESS_WEIGHT] (kL = 2, kC = kH = 1).
     *
     * Why this metric: on a cube under real lighting, the same sticker's lightness swings widely
     * between faces and even across one face (shading, exposure, glare), while its hue and chroma
     * stay comparatively stable. Plain Euclidean Lab (CIE76) lets those lightness swings dominate,
     * e.g. a shaded red is closer to a dark blue than to a lit red. CIEDE2000 fixes the known Lab
     * non-uniformities that matter here: its hue term is chroma-weighted, which keeps red vs orange
     * (about 20 degrees apart in hue) well separated at the high chroma of sticker plastic, and its
     * blue-region rotation term stops blues from drifting towards purple. Halving the lightness term
     * (the CIE parametric factor for textured, non-ideal viewing) makes classification depend mostly
     * on chroma and hue, while still separating white from yellow (chroma) and keeping a usable
     * lightness signal for orange vs red.
     */
    fun deltaE(x: Lab, y: Lab): Float =
        ciede2000(x.l.toDouble(), x.a.toDouble(), x.b.toDouble(), y.l.toDouble(), y.a.toDouble(), y.b.toDouble(), LIGHTNESS_WEIGHT)
            .toFloat()

    /**
     * CIEDE2000 color difference (Sharma, Wu & Dalal 2005) with parametric factors [kL], [kC], [kH].
     */
    fun ciede2000(
        l1: Double, a1: Double, b1: Double,
        l2: Double, a2: Double, b2: Double,
        kL: Double = 1.0, kC: Double = 1.0, kH: Double = 1.0,
    ): Double {
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar7 = ((c1 + c2) / 2.0).pow(7)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + POW25_7)))
        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)
        val h1p = hueDegrees(b1, a1p)
        val h2p = hueDegrees(b2, a2p)

        val dLp = l2 - l1
        val dCp = c2p - c1p
        val chromaProduct = c1p * c2p
        val dhp = when {
            chromaProduct == 0.0 -> 0.0
            abs(h2p - h1p) <= 180.0 -> h2p - h1p
            h2p - h1p > 180.0 -> h2p - h1p - 360.0
            else -> h2p - h1p + 360.0
        }
        val dHp = 2.0 * sqrt(chromaProduct) * sin(Math.toRadians(dhp / 2.0))

        val lBarP = (l1 + l2) / 2.0
        val cBarP = (c1p + c2p) / 2.0
        val hBarP = when {
            chromaProduct == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }
        val t = 1.0 -
            0.17 * cos(Math.toRadians(hBarP - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hBarP)) +
            0.32 * cos(Math.toRadians(3.0 * hBarP + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hBarP - 63.0))
        val dTheta = 30.0 * exp(-((hBarP - 275.0) / 25.0).let { it * it })
        val cBarP7 = cBarP.pow(7)
        val rC = 2.0 * sqrt(cBarP7 / (cBarP7 + POW25_7))
        val lm50 = (lBarP - 50.0) * (lBarP - 50.0)
        val sL = 1.0 + 0.015 * lm50 / sqrt(20.0 + lm50)
        val sC = 1.0 + 0.045 * cBarP
        val sH = 1.0 + 0.015 * cBarP * t
        val rT = -sin(Math.toRadians(2.0 * dTheta)) * rC

        val lTerm = dLp / (kL * sL)
        val cTerm = dCp / (kC * sC)
        val hTerm = dHp / (kH * sH)
        return sqrt(lTerm * lTerm + cTerm * cTerm + hTerm * hTerm + rT * cTerm * hTerm)
    }

    private const val POW25_7 = 6103515625.0 // 25^7

    private fun hueDegrees(b: Double, a: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        val h = Math.toDegrees(atan2(b, a))
        return if (h < 0.0) h + 360.0 else h
    }

    private fun f(t: Double): Double = if (t > EPSILON) cbrt(t) else (KAPPA * t + 16.0) / 116.0

    private fun finv(ft: Double): Double {
        val t3 = ft * ft * ft
        return if (t3 > EPSILON) t3 else (116.0 * ft - 16.0) / KAPPA
    }
}
