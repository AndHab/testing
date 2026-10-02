package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNError
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNValidator
import kotlin.math.atan2
import kotlin.math.sqrt

/*
 * Camera color vision: turning images of the six faces into sticker colors.
 *
 * The pipeline is:
 *  1. [GridSampler] reads the nine stickers of one face from a camera frame or photo, robustly
 *     (it locks onto the actual stickers inside the guide square and ignores black plastic and glare;
 *     on stickerless and white-bodied cubes it fits the grid to where the stickers are uniform).
 *  2. [LiveClassifier] gives an instant per-sticker color guess for the live preview, for standard
 *     vivid cubes and for pastel, candy and muted knock-offs. [AdaptiveLiveClassifier] does better on
 *     the scanning screen: it learns this cube's actual colors from the captured centers, named
 *     jointly ([AdaptiveLiveClassifier.learnCenters]).
 *  3. [ScanResolver] takes all six scans and classifies the 54 stickers jointly (each color exactly
 *     nine times, per-face lighting compensated), names the colors by how they relate to each other
 *     (so unusual palettes work), places every scan on its face (by center color, or by the scan
 *     order for non-standard color arrangements), fixes face rotations with [OrientationFixer] and
 *     estimates the cube's display palette ([CubePaletteEstimate]).
 *
 * **Other sizes (2x2 to 7x7; the engine allows up to 10x10).** Every step takes the size `n`:
 * `GridSampler.sample(..., n)` reads `n * n` stickers, `ScanResolver.resolve(n, scans, scanPositions)`
 * returns an [NxNScanAnalysis] (joint clustering of `6 * n * n` stickers; even sizes, which have no
 * fixed centers, are clustered from scratch and placed by scan order; face rotations are fixed by
 * [NxNOrientationFixer]; from 4x4 on, a single confidently misread sticker is corrected, and every
 * sticker that another equally good reading colors differently is flagged; one face photographed
 * twice is reported, and a 2x2 reading resting on clearly misread stickers is rejected), and
 * [AdaptiveLiveClassifier.learnFaces] learns any size's colors while scanning. For n = 3 they give
 * exactly the 3x3 results.
 *
 * Colors are compared with [ColorMath.deltaE].
 */

/** Read-only view of an image, e.g. a camera frame or a decoded photo. */
interface PixelSource {
    val width: Int
    val height: Int

    /** Pixel at ([x], [y]) as 0xAARRGGBB; alpha is ignored. */
    fun argb(x: Int, y: Int): Int
}

/** CIELAB color (D65), L in 0..100. */
data class Lab(val l: Float, val a: Float, val b: Float) {
    /** CIE chroma C*ab, the distance from the neutral axis. */
    val chroma: Float get() = sqrt(a * a + b * b)

    /** CIE hue angle h_ab in degrees, 0 until 360 (0 for neutral colors). */
    val hue: Float
        get() {
            if (a == 0f && b == 0f) return 0f
            val h = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat()
            return if (h < 0f) h + 360f else h
        }
}

/** Robust color reading of a single sticker. */
data class StickerSample(val r: Int, val g: Int, val b: Int, val lab: Lab) {
    val argb: Int get() = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        /** A sample of the sRGB color ([r], [g], [b]), each 0..255. */
        fun of(r: Int, g: Int, b: Int): StickerSample = StickerSample(r, g, b, ColorMath.srgbToLab(r, g, b))

        /** A sample of the sRGB color [argb] (0xAARRGGBB, alpha ignored). */
        fun ofArgb(argb: Int): StickerSample = of((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
}

/** An axis-aligned square in source pixel coordinates. */
data class GridRegion(val left: Int, val top: Int, val size: Int)

/** How [ScanResolver] decided which face each scan shows. */
enum class Placement {
    /**
     * By the color of each scan's center, with the standard color scheme
     * ([com.andhab.cubelens.core.cube.ColorScheme.STANDARD]: white up, green front, red right).
     * Works for any scan order, but only for cubes with the standard color arrangement.
     */
    CENTER_COLORS,

    /**
     * By the face the scan flow asked for at each step (the `scanPositions` passed to
     * [ScanResolver.resolve]). Used for cubes whose colors are arranged differently from the standard
     * scheme, e.g. the Japanese scheme (white opposite blue); the center labels then define the scheme.
     */
    SCAN_ORDER,
}

/**
 * Result of turning six scanned faces into a cube.
 *
 * All color lists are in facelet order (see [com.andhab.cubelens.core.cube.Facelets]).
 */
data class ScanAnalysis(
    /** Colors with each scan placed on its face (see [placement]), as captured (not re-oriented). */
    val rawColors: List<CubeColor>,
    /** Final colors after automatically fixing face rotations (equals [rawColors] if nothing could be fixed). */
    val colors: List<CubeColor>,
    /** Clockwise quarter turns applied to each face's 3x3 grid to get from [rawColors] to [colors]. */
    val faceRotations: Map<Face, Int>,
    /** Whether [colors] is a valid, solvable cube. */
    val isValid: Boolean,
    /**
     * Facelets whose color assignment was uncertain; the UI may highlight these for review.
     * Indices refer to positions in [colors]. A facelet is uncertain when its color was a close
     * call, or when it depends on how a face was held during scanning (another combination of face
     * rotations also gives a valid cube, with a different color here; see [ScanResolver]).
     */
    val uncertain: Set<Int>,
    /**
     * What this cube's stickers look like, for drawing it: estimated from the scans, or
     * [CubePaletteEstimate.STANDARD] when there is nothing to estimate from (malformed input).
     * When [CubePaletteEstimate.isStandardLike] is true the app should keep its stock colors.
     */
    val palette: CubePaletteEstimate = CubePaletteEstimate.STANDARD,
    /** How each scan was assigned to its face. */
    val placement: Placement = Placement.CENTER_COLORS,
)

/**
 * Result of turning six scanned faces of an [n]x[n] cube into a cube ([ScanResolver.resolve] with a
 * size): [ScanAnalysis] for every size.
 *
 * All color lists are in [NxNGeometry] order (face by face U, R, F, D, L, B, each face row-major as
 * seen when scanning, `face * n * n + row * n + col`); for n = 3 that is the [Facelets] order.
 */
data class NxNScanAnalysis(
    /** Stickers per row and column. */
    val n: Int,
    /** Colors with each scan placed on its face (see [placement]), as captured (not re-oriented). */
    val rawColors: List<CubeColor>,
    /** Final colors after automatically fixing face rotations (equals [rawColors] if nothing could be fixed). */
    val colors: List<CubeColor>,
    /** Clockwise quarter turns applied to each face's grid to get from [rawColors] to [colors]. */
    val faceRotations: Map<Face, Int>,
    /** Whether [colors] is a valid, solvable cube ([NxNValidator]). */
    val isValid: Boolean,
    /**
     * Stickers whose color assignment was uncertain; the UI may highlight these for review. Indices
     * refer to positions in [colors]. A sticker is uncertain when its color was a close call, when it
     * matches no color well, when it was corrected, or when it depends on how a face was held during
     * scanning (another combination of face rotations also gives a valid cube, with a different color
     * here), or on which of two equally good readings is right.
     *
     * This set can be large. Solved and lightly scrambled cubes have faces of one or two colors, and
     * a face of few colors often gives a valid cube held in more than one way: with faces held at
     * random angles, about half of such sessions come back with tens of stickers flagged (on a 2x2
     * cube often all 24), at every size. Faces captured upright as the guide asks make this much
     * rarer. A 2x2 reading that may rest on one face photographed twice (two photos that look alike
     * sticker for sticker, read as different faces) also has every sticker flagged. The scan screen
     * should be ready for a large set, e.g. by asking how a face was held, or by offering to retake
     * the faces whose stickers are flagged, rather than asking about every sticker.
     */
    val uncertain: Set<Int>,
    /**
     * What this cube's stickers look like, for drawing it: estimated from the scans, or
     * [CubePaletteEstimate.STANDARD] when there is nothing to estimate from (malformed input). When
     * [CubePaletteEstimate.isStandardLike] is true the app should keep its stock colors.
     */
    val palette: CubePaletteEstimate,
    /**
     * How each scan was assigned to its face: odd sizes as for 3x3 cubes; even sizes, which have no
     * fixed centers, always by scan order ([Placement.SCAN_ORDER]).
     */
    val placement: Placement,
    /**
     * Why [colors] is not a valid cube, as [NxNValidator] reports it (friendly messages with the
     * stickers to highlight), preceded by a note for malformed or implausible scans, for two photos
     * that seem to show the same side (the later one highlighted, to be retaken), and for a 2x2
     * reading that would need stickers to be colors they clearly don't show; empty when [isValid].
     */
    val problems: List<NxNError>,
) {
    /** [colors] as a cube, e.g. for [com.andhab.cubelens.core.nxn.NxNSolver]. */
    fun toCube(): NxNCube = NxNCube.of(n, colors)
}
