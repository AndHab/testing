package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** In-memory ARGB image for tests. */
class IntImage(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) : PixelSource {
    init {
        require(pixels.size == width * height)
    }

    override fun argb(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    /**
     * This image as a camera buffer that must be rotated clockwise by [rotationDegrees] to look like
     * this image again (i.e. this image turned counter-clockwise by [rotationDegrees]).
     */
    fun asBufferNeedingRotation(rotationDegrees: Int): IntImage {
        var img = this
        repeat(Math.floorMod(rotationDegrees, 360) / 90) { img = img.rotatedCounterClockwise() }
        return img
    }

    /** The region in [asBufferNeedingRotation]'s coordinates that shows [region] of this image. */
    fun regionInBuffer(region: GridRegion, rotationDegrees: Int): GridRegion {
        var r = region
        var w = width
        var h = height
        repeat(Math.floorMod(rotationDegrees, 360) / 90) {
            // Counter-clockwise turn: (x, y) -> (y, w - 1 - x).
            r = GridRegion(r.top, w - r.left - r.size, r.size)
            val t = w
            w = h
            h = t
        }
        return r
    }

    private fun rotatedCounterClockwise(): IntImage {
        val out = IntImage(height, width)
        for (y in 0 until height) for (x in 0 until width) out[y, width - 1 - x] = this.argb(x, y)
        return out
    }

    companion object {
        fun of(image: BufferedImage): IntImage {
            val w = image.width
            val h = image.height
            return IntImage(w, h, image.getRGB(0, 0, w, h, null, 0, w))
        }
    }
}

/** The user's real photos (tight crops of one face each, 480x480). */
object Photos {
    /** The 3x3 grid inside each crop: the crops have a 3% margin around the face. */
    val REGION = GridRegion(14, 14, 453)

    /** Ground truth, rows as displayed upright. */
    val TRUTH: Map<String, String> = linkedMapOf(
        "1_red" to "GOB YRG YWG",
        "2_green" to "RGR YGR OBW",
        "3_blue" to "RGW RBW RWO",
        "4_white" to "YOO RWR WYO",
        "5_red_rotated" to "GWY GRY BOG",
        "6_yellow" to "BWG BYO YOW",
        "7_orange" to "YBB GOB BYG",
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, IntImage>()

    fun load(name: String): IntImage = cache.getOrPut(name) {
        val stream = Photos::class.java.getResourceAsStream("/photos/$name.jpg") ?: error("Missing photo fixture $name")
        stream.use { IntImage.of(ImageIO.read(it)) }
    }

    fun truth(name: String): List<CubeColor> = TRUTH.getValue(name).filter { !it.isWhitespace() }.map { CubeColor.fromLetter(it) }

    fun scan(name: String, region: GridRegion = REGION): List<StickerSample> = GridSampler.sample(load(name), region)
}

fun List<CubeColor>.letters(): String = joinToString("") { it.letter.toString() }.chunked(3).joinToString(" ")
