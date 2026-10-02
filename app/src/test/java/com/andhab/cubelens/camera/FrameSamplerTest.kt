package com.andhab.cubelens.camera

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.vision.ColorMath
import com.andhab.cubelens.core.vision.LiveClassifier
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.max

/**
 * End to end, without a camera: a face is placed in the guide on screen, "captured" into a sensor
 * buffer at each rotation (padded rows, off-center visible crop, garbage outside the crop), then
 * read back through [RgbaPixelSource], [GuideMapper] and the sampler. The colors must come out
 * exactly as the user sees them on screen: the nine of a real photo of the user's cube, and the N²
 * of faces of every other size.
 */
class FrameSamplerTest {

    private val photo: BufferedImage = checkNotNull(javaClass.getResourceAsStream("/scan/frame_orange.jpg")).use(ImageIO::read)

    /** The orange face's stickers as seen in the photo, row-major. */
    private val seen = "YBBGOBBYG".map(CubeColor::fromLetter)

    /** The photo's sticker grid: a 472px square at (165, 535). */
    private val photoGrid = Grid(left = 165.0, top = 535.0, size = 472.0)

    private val guide = GuideGeometry(viewWidth = 1080, viewHeight = 2400, left = 130f, top = 640f, size = 820f)

    @Test
    fun readsTheFaceAsSeenAtEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sideways = rotation % 180 != 0
            // Visible crop (aspect of the view, 0.45 upright) placed off-center in a larger buffer.
            val crop = if (sideways) BufferRect(31, 157, 31 + 1280, 157 + 576) else BufferRect(119, 23, 119 + 576, 23 + 1280)
            val bufferWidth = crop.right + 45
            val bufferHeight = crop.bottom + 61
            val rowStride = bufferWidth * 4 + 64
            val buffer = render(photo, photoGrid, bufferWidth, bufferHeight, rowStride, crop, rotation)

            val source = RgbaPixelSource(buffer, bufferWidth, bufferHeight, rowStride, crop = crop)
            val samples = FrameSampler.sample(source, crop, rotation, guide)
            assertEquals("rotation $rotation", seen, samples.map(LiveClassifier::classify))
        }
    }

    @Test
    fun readsFacesOfEverySizeAsSeenAtEveryRotation() {
        for (n in listOf(2, 4, 5, 7)) {
            val colors = List(n * n) { CubeColor.entries[(it * 5 + it / n) % CubeColor.entries.size] }
            val face = drawFace(colors, n)
            for (rotation in listOf(0, 90, 180, 270)) {
                val sideways = rotation % 180 != 0
                val crop = if (sideways) BufferRect(31, 157, 31 + 1280, 157 + 576) else BufferRect(119, 23, 119 + 576, 23 + 1280)
                val bufferWidth = crop.right + 45
                val bufferHeight = crop.bottom + 61
                val rowStride = bufferWidth * 4 + 64
                val buffer = render(face, Grid(0.0, 0.0, face.width.toDouble()), bufferWidth, bufferHeight, rowStride, crop, rotation)

                val source = RgbaPixelSource(buffer, bufferWidth, bufferHeight, rowStride, crop = crop)
                val samples = FrameSampler.sample(source, crop, rotation, guide, size = n)
                assertEquals("$n×$n rotation $rotation", colors, samples.map(LiveClassifier::classify))
            }
        }
    }

    /** A flat [n]×[n] face: stickers in typical camera colors on a black body. */
    private fun drawFace(colors: List<CubeColor>, n: Int): BufferedImage {
        val side = 490
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val cell = side / n
        val gap = cell / 12
        for (y in 0 until side) {
            for (x in 0 until side) {
                val c = (x / cell).coerceAtMost(n - 1)
                val r = (y / cell).coerceAtMost(n - 1)
                val inX = x - c * cell
                val inY = y - r * cell
                val sticker = inX in gap until cell - gap && inY in gap until cell - gap
                image.setRGB(x, y, if (sticker) ColorMath.labToArgb(LiveClassifier.reference.getValue(colors[r * n + c])) else BODY)
            }
        }
        return image
    }

    /** Where a face's sticker grid is in an image: a square of [size] at ([left], [top]). */
    private data class Grid(val left: Double, val top: Double, val size: Double)

    /**
     * A sensor buffer that, turned [rotation] degrees clockwise and shown FILL_CENTER in the view,
     * shows [image]'s face ([grid]) exactly inside [guide]. Outside [crop] it is magenta noise the
     * reader must never see.
     */
    private fun render(image: BufferedImage, grid: Grid, width: Int, height: Int, rowStride: Int, crop: BufferRect, rotation: Int): ByteBuffer {
        val bytes = ByteArray(rowStride * height)
        val uprightWidth = if (rotation % 180 == 0) crop.width else crop.height
        val uprightHeight = if (rotation % 180 == 0) crop.height else crop.width
        val scale = max(guide.viewWidth.toDouble() / uprightWidth, guide.viewHeight.toDouble() / uprightHeight)
        val offsetX = (guide.viewWidth - uprightWidth * scale) / 2
        val offsetY = (guide.viewHeight - uprightHeight * scale) / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = if (x < crop.left || x >= crop.right || y < crop.top || y >= crop.bottom) {
                    0xFFFF00FF.toInt()
                } else {
                    val lx = x - crop.left + 0.5
                    val ly = y - crop.top + 0.5
                    val (u, v) = when (rotation) {
                        0 -> lx to ly
                        90 -> (crop.height - ly) to lx
                        180 -> (crop.width - lx) to (crop.height - ly)
                        else -> ly to (crop.width - lx)
                    }
                    val viewX = u * scale + offsetX
                    val viewY = v * scale + offsetY
                    val px = grid.left + (viewX - guide.left) / guide.size * grid.size
                    val py = grid.top + (viewY - guide.top) / guide.size * grid.size
                    image.getRGB(floor(px).toInt().coerceIn(0, image.width - 1), floor(py).toInt().coerceIn(0, image.height - 1))
                }
                val i = y * rowStride + x * 4
                bytes[i] = (argb shr 16).toByte()
                bytes[i + 1] = (argb shr 8).toByte()
                bytes[i + 2] = argb.toByte()
                bytes[i + 3] = 0xFF.toByte()
            }
        }
        return ByteBuffer.wrap(bytes)
    }

    private companion object {
        /** Black cube plastic between the stickers. */
        val BODY = 0xFF101012.toInt()
    }
}
