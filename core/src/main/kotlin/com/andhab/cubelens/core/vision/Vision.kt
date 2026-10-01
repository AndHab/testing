package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face

/** Read-only view of an image, e.g. a camera frame or a decoded photo. */
interface PixelSource {
    val width: Int
    val height: Int

    /** Pixel at ([x], [y]) as 0xAARRGGBB; alpha is ignored. */
    fun argb(x: Int, y: Int): Int
}

/** CIELAB color (D65), L in 0..100. */
data class Lab(val l: Float, val a: Float, val b: Float)

/** Robust color reading of a single sticker. */
data class StickerSample(val r: Int, val g: Int, val b: Int, val lab: Lab) {
    val argb: Int get() = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** An axis-aligned square in source pixel coordinates. */
data class GridRegion(val left: Int, val top: Int, val size: Int)

/**
 * Result of turning six scanned faces into a cube.
 *
 * All color lists are in facelet order (see [com.andhab.cubelens.core.cube.Facelets]).
 */
data class ScanAnalysis(
    /** Colors with each scan placed on the face its center color belongs to, as captured (not re-oriented). */
    val rawColors: List<CubeColor>,
    /** Final colors after automatically fixing face rotations (equals [rawColors] if nothing could be fixed). */
    val colors: List<CubeColor>,
    /** Clockwise quarter turns applied to each face's 3x3 grid to get from [rawColors] to [colors]. */
    val faceRotations: Map<Face, Int>,
    /** Whether [colors] is a valid, solvable cube. */
    val isValid: Boolean,
    /** Facelets whose color assignment was uncertain; the UI may highlight these for review. */
    val uncertain: Set<Int>,
)

/** sRGB / CIELAB conversions and color distances. STUB bodies. */
object ColorMath {
    fun srgbToLab(r: Int, g: Int, b: Int): Lab = TODO()

    /** Perceptual distance between two colors. */
    fun deltaE(x: Lab, y: Lab): Float = TODO()
}

/** Samples the nine stickers of a face from an image. STUB body. */
object GridSampler {
    /**
     * Samples the 3x3 stickers inside [region] of [source].
     *
     * [rotationDegrees] (0, 90, 180 or 270) is the clockwise rotation that makes [source] upright as
     * the user sees it (CameraX `ImageInfo.rotationDegrees`). The returned nine samples are
     * row-major as the user sees the face on screen.
     */
    fun sample(source: PixelSource, region: GridRegion, rotationDegrees: Int = 0): List<StickerSample> = TODO()
}

/** Fast single-sticker classification for live feedback while scanning. STUB body. */
object LiveClassifier {
    fun classify(sample: StickerSample): CubeColor = TODO()
}

/** Turns all scanned faces into a validated cube. STUB body. */
object ScanResolver {
    /**
     * [scans] are the six faces in any order, each nine samples row-major as seen on screen, each
     * captured at any rotation. Centers decide which face each scan is (standard color scheme: white
     * up, green front, red right); all 54 stickers are then classified jointly (each color exactly
     * nine times) and face rotations are fixed automatically when the scan doesn't form a valid cube.
     */
    fun resolve(scans: List<List<StickerSample>>): ScanAnalysis = TODO()
}

/** Finds face rotations that turn a mis-oriented scan into a valid cube. STUB bodies. */
object OrientationFixer {
    /** Rotates one face's 3x3 grid by [quarterTurns] clockwise quarter turns (center unchanged). */
    fun rotateFace(colors: List<CubeColor>, face: Face, quarterTurns: Int): List<CubeColor> = TODO()

    /**
     * Searches all combinations of face rotations for a valid cube, preferring the fewest rotated
     * faces. Returns the fixed colors and the rotation applied to each face, or null if none is valid.
     */
    fun fix(colors: List<CubeColor>): Pair<List<CubeColor>, Map<Face, Int>>? = TODO()
}
