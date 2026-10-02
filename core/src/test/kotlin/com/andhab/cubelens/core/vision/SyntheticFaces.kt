package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders photo-like images of one cube face for tests: rounded stickers on black plastic (or the
 * body of [CubeLook.body]), per-photo exposure and white balance, uneven illumination, sensor noise,
 * specular highlights, and a face that is shifted, scaled, rotated and slightly in perspective
 * relative to the scanning guide.
 *
 * Stickers have the colors of [look] in neutral light.
 */
class SyntheticFaces(private val random: Random, private val look: CubeLook) {

    /** A renderer for a standard black-bodied cube with the colors of [palette]. */
    constructor(random: Random, palette: Palette = Palette.NOMINAL) : this(random, palette.look)

    /** What shows between the stickers. */
    enum class Body {
        /** Black plastic gaps (the usual stickered cube). */
        BLACK,

        /** White plastic gaps (white-bodied cubes). */
        WHITE,

        /** No gaps: colored plastic tiles that touch, with thin seams of the same or a slightly darker color. */
        STICKERLESS,
    }

    /** A cube's sticker colors as a phone camera captures them in neutral light. */
    enum class Palette(
        /** sRGB (0..255) of each sticker color. */
        val srgb: Map<CubeColor, IntArray>,
    ) {
        /**
         * The commonly published nominal Rubik's brand colors (white FFFFFF, yellow FFD500, green
         * 009B48, blue 0046AD, red B71234, orange FF5800) at a typical auto exposure, where a white
         * sticker comes out at about 206 (0.62 in linear light). Independent of the photo fixtures
         * that [LiveClassifier.reference] is calibrated on; its orange is redder and its yellow
         * deeper than the user's cube.
         */
        NOMINAL(
            mapOf(
                CubeColor.WHITE to intArrayOf(206, 206, 206),
                CubeColor.YELLOW to intArrayOf(206, 172, 0),
                CubeColor.GREEN to intArrayOf(0, 124, 56),
                CubeColor.BLUE to intArrayOf(0, 55, 139),
                CubeColor.RED to intArrayOf(147, 12, 40),
                CubeColor.ORANGE to intArrayOf(206, 70, 0),
            ),
        ),

        /**
         * Typical sticker colors of the user's cube in the photo fixtures, i.e. the data that
         * [LiveClassifier.reference] is calibrated on.
         */
        PHOTO(
            mapOf(
                CubeColor.WHITE to intArrayOf(192, 200, 212),
                CubeColor.YELLOW to intArrayOf(222, 214, 62),
                CubeColor.GREEN to intArrayOf(48, 165, 80),
                CubeColor.BLUE to intArrayOf(1, 79, 162),
                CubeColor.RED to intArrayOf(183, 25, 29),
                CubeColor.ORANGE to intArrayOf(228, 114, 22),
            ),
        ),
        ;

        /** [srgb] in linear light. */
        val linear: Map<CubeColor, DoubleArray> = srgb.mapValues { (_, c) -> DoubleArray(3) { ColorMath.srgbToLinear(c[it]) } }

        /** An ideal (noise-free, neutral light) sample of [color]. */
        fun sample(color: CubeColor): StickerSample = srgb.getValue(color).let { StickerSample.of(it[0], it[1], it[2]) }

        /** This palette on a black-bodied cube. */
        val look: CubeLook get() = CubeLook(name, srgb)
    }

    /** Lighting and pose of one rendered photo. */
    data class Conditions(
        /** Overall exposure factor applied in linear light. */
        val brightness: Double,
        /** White-balance gains (R, G, B) applied in linear light. */
        val whiteBalance: DoubleArray,
        /** Linear illumination change across the face, as (x, y) slopes per face width. */
        val gradient: Pair<Double, Double>,
        /** Face center offset from the guide center, in fractions of the guide size. */
        val shift: Pair<Double, Double>,
        /** Face size relative to the guide. */
        val scale: Double,
        /** In-plane rotation of the face, degrees. */
        val rotationDegrees: Double,
        /** Keystone (perspective) strength; 0 is a frontal view. */
        val keystone: Pair<Double, Double>,
        /** Number of stickers that get a specular highlight. */
        val highlights: Int,
        /** Sensor noise standard deviation, sRGB levels. */
        val noise: Double,
    )

    /** Random conditions spanning the range a handheld phone scan sees. */
    fun randomConditions(): Conditions {
        val wb = when (random.nextInt(4)) {
            0 -> doubleArrayOf(1.0, 1.0, 1.0)
            1 -> doubleArrayOf(1.12, 1.0, 0.80) // warm (tungsten-ish, not fully corrected)
            2 -> doubleArrayOf(0.88, 1.0, 1.16) // cool (shade / daylight through a window)
            else -> doubleArrayOf(random.nextDouble(0.9, 1.1), random.nextDouble(0.95, 1.05), random.nextDouble(0.88, 1.12))
        }
        return Conditions(
            brightness = random.nextDouble(0.6, 1.3),
            whiteBalance = wb,
            gradient = random.nextDouble(-0.25, 0.25) to random.nextDouble(-0.25, 0.25),
            shift = random.nextDouble(-0.04, 0.04) to random.nextDouble(-0.04, 0.04),
            scale = random.nextDouble(0.94, 1.06),
            rotationDegrees = random.nextDouble(-4.0, 4.0),
            keystone = random.nextDouble(-0.06, 0.06) to random.nextDouble(-0.06, 0.06),
            highlights = random.nextInt(0, 4),
            noise = random.nextDouble(2.0, 6.0),
        )
    }

    /**
     * Renders a [imageSize] x [imageSize] photo of a face showing [colors] (nine, row-major as seen),
     * returning the image and the guide square the user aligned the face with.
     */
    fun render(colors: List<CubeColor>, conditions: Conditions, imageSize: Int = 256): Pair<IntImage, GridRegion> {
        require(colors.size == 9)
        val guideSize = (imageSize * 0.78).toInt()
        val guide = GridRegion((imageSize - guideSize) / 2, (imageSize - guideSize) / 2, guideSize)

        // Per-sticker variation: size, position jitter, small tint, and highlights.
        val stickerHalf = DoubleArray(9) { random.nextDouble(0.37, 0.40) }
        val jitterX = DoubleArray(9) { random.nextDouble(-0.02, 0.02) }
        val jitterY = DoubleArray(9) { random.nextDouble(-0.02, 0.02) }
        val tint = Array(9) { DoubleArray(3) { random.nextDouble(0.95, 1.05) } }
        val highlightCells = (0 until 9).shuffled(random).take(conditions.highlights).toSet()
        val highlight = Array(9) { doubleArrayOf(random.nextDouble(-0.2, 0.2), random.nextDouble(-0.2, 0.2), random.nextDouble(0.08, 0.16)) }
        val background = doubleArrayOf(random.nextDouble(0.05, 0.4), random.nextDouble(0.05, 0.4), random.nextDouble(0.03, 0.3))
        // Only other bodies draw more random numbers here, so black-bodied renders stay as they were.
        val seamShade = if (look.body == Body.STICKERLESS) (if (random.nextBoolean()) 1.0 else random.nextDouble(0.55, 0.9)) else 1.0
        val whiteBody = if (look.body == Body.WHITE) random.nextDouble(0.5, 0.68) else 0.0

        val cx = guide.left + guide.size / 2.0 + conditions.shift.first * guide.size
        val cy = guide.top + guide.size / 2.0 + conditions.shift.second * guide.size
        val cell = guide.size * conditions.scale / 3.0
        val theta = Math.toRadians(conditions.rotationDegrees)
        val cosT = cos(theta)
        val sinT = sin(theta)
        val (kx, ky) = conditions.keystone

        val image = IntImage(imageSize, imageSize)
        val linear = DoubleArray(3)
        for (py in 0 until imageSize) {
            for (px in 0 until imageSize) {
                // Image -> face coordinates (cells, -1.5..1.5 around the face center).
                val dx = (px + 0.5 - cx) / cell
                val dy = (py + 0.5 - cy) / cell
                var fx = cosT * dx + sinT * dy
                var fy = -sinT * dx + cosT * dy
                val w = 1.0 + kx * fx + ky * fy
                fx /= w
                fy /= w
                val inFace = abs(fx) < 1.5 && abs(fy) < 1.5
                if (!inFace) {
                    for (ch in 0 until 3) linear[ch] = background[ch]
                } else {
                    val col = min(2, max(0, (fx + 1.5).toInt()))
                    val row = min(2, max(0, (fy + 1.5).toInt()))
                    val k = row * 3 + col
                    val lx = fx + 1.5 - col - 0.5 - jitterX[k]
                    val ly = fy + 1.5 - row - 0.5 - jitterY[k]
                    val inSticker = if (look.body == Body.STICKERLESS) {
                        abs(fx + 1.0 - col) < 0.5 - SEAM && abs(fy + 1.0 - row) < 0.5 - SEAM
                    } else {
                        insideRoundedSquare(lx, ly, stickerHalf[k], 0.09)
                    }
                    if (inSticker) {
                        val base = look.linear.getValue(colors[k])
                        for (ch in 0 until 3) linear[ch] = base[ch] * tint[k][ch]
                        if (k in highlightCells) {
                            val h = highlight[k]
                            val d2 = ((lx - h[0]) * (lx - h[0]) + 0.5 * (ly - h[1]) * (ly - h[1])) / (h[2] * h[2])
                            if (d2 < 1.0) {
                                val alpha = 0.9 * (1.0 - d2)
                                for (ch in 0 until 3) linear[ch] = linear[ch] * (1 - alpha) + 1.6 * alpha
                            }
                        }
                    } else {
                        when (look.body) {
                            Body.BLACK -> {
                                val plastic = 0.012 + 0.006 * random.nextDouble()
                                for (ch in 0 until 3) linear[ch] = plastic
                            }
                            Body.WHITE -> {
                                val plastic = whiteBody * (0.97 + 0.06 * random.nextDouble())
                                for (ch in 0 until 3) linear[ch] = plastic
                            }
                            Body.STICKERLESS -> {
                                val base = look.linear.getValue(colors[k])
                                for (ch in 0 until 3) linear[ch] = base[ch] * seamShade
                            }
                        }
                    }
                }
                val light = conditions.brightness * (1.0 + conditions.gradient.first * fx / 3.0 + conditions.gradient.second * fy / 3.0)
                var argb = 0xFF shl 24
                for (ch in 0 until 3) {
                    val v = ColorMath.linearToSrgb(linear[ch] * light * conditions.whiteBalance[ch]) + random.nextGaussianCompat() * conditions.noise
                    argb = argb or (v.toInt().coerceIn(0, 255) shl (16 - 8 * ch))
                }
                image[px, py] = argb
            }
        }
        return image to guide
    }

    /**
     * Renders a photo of an [n]x[n] face (n in 2..10) showing [colors] (`n * n`, row-major as seen),
     * like [render] does for 3x3 faces, returning the image and the guide square the face was aligned
     * with. [guideSize] is the guide's side in pixels; the image is the guide plus a margin of 11% of
     * it on every side (the proportions of [render]).
     *
     * Stickers have realistic proportions for the size: a 2x2 cube's tiles cover about 84% of their
     * cell, a 7x7's about 74% (the plastic between stickers is about as thick on every size, while
     * cells get smaller). [Conditions] keep their meaning relative to the whole face (shift, scale,
     * rotation, perspective, illumination gradient), and [Conditions.highlights] stickers get a glare
     * spot as on a 3x3 face. Noise comes from a table of Gaussian values (faster than drawing them for
     * the large images of big faces). The same [random] sequence is not shared with [render].
     */
    fun render(n: Int, colors: List<CubeColor>, conditions: Conditions, guideSize: Int): Pair<IntImage, GridRegion> {
        require(n in 2..10 && colors.size == n * n)
        val margin = (guideSize * 0.141).toInt() // 0.11 of the image on each side, as in render()
        val imageSize = guideSize + 2 * margin
        val guide = GridRegion(margin, margin, guideSize)
        val cells = n * n
        val half = n / 2.0
        val (minHalf, maxHalf) = stickerHalfRange(n)

        val stickerHalf = DoubleArray(cells) { random.nextDouble(minHalf, maxHalf) }
        val jitterX = DoubleArray(cells) { random.nextDouble(-0.02, 0.02) }
        val jitterY = DoubleArray(cells) { random.nextDouble(-0.02, 0.02) }
        val tint = Array(cells) { DoubleArray(3) { random.nextDouble(0.95, 1.05) } }
        val highlightCells = (0 until cells).shuffled(random).take(conditions.highlights).toSet()
        val highlight = Array(cells) { doubleArrayOf(random.nextDouble(-0.2, 0.2), random.nextDouble(-0.2, 0.2), random.nextDouble(0.08, 0.16)) }
        val background = doubleArrayOf(random.nextDouble(0.05, 0.4), random.nextDouble(0.05, 0.4), random.nextDouble(0.03, 0.3))
        val seamShade = if (look.body == Body.STICKERLESS) (if (random.nextBoolean()) 1.0 else random.nextDouble(0.55, 0.9)) else 1.0
        val whiteBody = if (look.body == Body.WHITE) random.nextDouble(0.5, 0.68) else 0.0

        val cx = guide.left + guide.size / 2.0 + conditions.shift.first * guide.size
        val cy = guide.top + guide.size / 2.0 + conditions.shift.second * guide.size
        val cell = guide.size * conditions.scale / n
        val theta = Math.toRadians(conditions.rotationDegrees)
        val cosT = cos(theta)
        val sinT = sin(theta)
        val (kx, ky) = conditions.keystone
        val toThree = 3.0 / n // face coordinates in 3x3 cells, for the face-relative keystone

        val image = IntImage(imageSize, imageSize)
        val linear = DoubleArray(3)
        for (py in 0 until imageSize) {
            for (px in 0 until imageSize) {
                // Image -> face coordinates (cells, -n/2..n/2 around the face center).
                val dx = (px + 0.5 - cx) / cell
                val dy = (py + 0.5 - cy) / cell
                var fx = cosT * dx + sinT * dy
                var fy = -sinT * dx + cosT * dy
                val w = 1.0 + kx * fx * toThree + ky * fy * toThree
                fx /= w
                fy /= w
                if (abs(fx) >= half || abs(fy) >= half) {
                    for (ch in 0 until 3) linear[ch] = background[ch]
                } else {
                    val col = min(n - 1, max(0, (fx + half).toInt()))
                    val row = min(n - 1, max(0, (fy + half).toInt()))
                    val k = row * n + col
                    val lx = fx + half - col - 0.5 - jitterX[k]
                    val ly = fy + half - row - 0.5 - jitterY[k]
                    val inSticker = if (look.body == Body.STICKERLESS) {
                        abs(fx + half - col - 0.5) < 0.5 - SEAM && abs(fy + half - row - 0.5) < 0.5 - SEAM
                    } else {
                        insideRoundedSquare(lx, ly, stickerHalf[k], 0.09)
                    }
                    if (inSticker) {
                        val base = look.linear.getValue(colors[k])
                        for (ch in 0 until 3) linear[ch] = base[ch] * tint[k][ch]
                        if (k in highlightCells) {
                            val h = highlight[k]
                            val d2 = ((lx - h[0]) * (lx - h[0]) + 0.5 * (ly - h[1]) * (ly - h[1])) / (h[2] * h[2])
                            if (d2 < 1.0) {
                                val alpha = 0.9 * (1.0 - d2)
                                for (ch in 0 until 3) linear[ch] = linear[ch] * (1 - alpha) + 1.6 * alpha
                            }
                        }
                    } else {
                        when (look.body) {
                            Body.BLACK -> {
                                val plastic = 0.012 + 0.006 * random.nextDouble()
                                for (ch in 0 until 3) linear[ch] = plastic
                            }
                            Body.WHITE -> {
                                val plastic = whiteBody * (0.97 + 0.06 * random.nextDouble())
                                for (ch in 0 until 3) linear[ch] = plastic
                            }
                            Body.STICKERLESS -> {
                                val base = look.linear.getValue(colors[k])
                                for (ch in 0 until 3) linear[ch] = base[ch] * seamShade
                            }
                        }
                    }
                }
                val light = conditions.brightness * (1.0 + conditions.gradient.first * fx / n + conditions.gradient.second * fy / n)
                var argb = 0xFF shl 24
                for (ch in 0 until 3) {
                    val v = ColorMath.linearToSrgb(linear[ch] * light * conditions.whiteBalance[ch]) + GAUSSIAN[random.nextInt(GAUSSIAN.size)] * conditions.noise
                    argb = argb or (v.toInt().coerceIn(0, 255) shl (16 - 8 * ch))
                }
                image[px, py] = argb
            }
        }
        return image to guide
    }

    companion object {
        /** Half-width of the seam between two stickerless tiles, in cells. */
        private const val SEAM = 0.025

        /**
         * Range of a sticker's half-width (cells) on an [n]x[n] cube: 2x2 tiles cover about 84% of
         * their cell, 3x3 ones 74-80% (as [render] draws them), 7x7 ones about 72-76%.
         */
        fun stickerHalfRange(n: Int): Pair<Double, Double> = when (n) {
            2 -> 0.405 to 0.43
            3, 4 -> 0.37 to 0.40
            5, 6 -> 0.36 to 0.39
            else -> 0.355 to 0.38
        }

        /** Standard normal values for [render] with a size (reproducible). */
        private val GAUSSIAN: DoubleArray = Random(7).let { r ->
            DoubleArray(8192) {
                val u1 = r.nextDouble(1e-12, 1.0)
                val u2 = r.nextDouble()
                kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * Math.PI * u2)
            }
        }
    }

    private fun insideRoundedSquare(x: Double, y: Double, half: Double, radius: Double): Boolean {
        val ax = abs(x)
        val ay = abs(y)
        if (ax > half || ay > half) return false
        val ix = ax - (half - radius)
        val iy = ay - (half - radius)
        return ix <= 0 || iy <= 0 || ix * ix + iy * iy <= radius * radius
    }

    private fun Random.nextGaussianCompat(): Double {
        // Box-Muller; kotlin.random has no Gaussian.
        val u1 = nextDouble(1e-12, 1.0)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
