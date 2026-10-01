package com.andhab.cubelens.camera

import com.andhab.cubelens.core.vision.GridRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GuideMapperTest {

    // A portrait phone: 1080x2400 view. The viewport crops the sensor image to the view's aspect
    // ratio (0.45): from a 1280x960 landscape buffer that is 1280x576, centered vertically.
    private val view = GuideGeometry(viewWidth = 1080, viewHeight = 2400, left = 120f, top = 600f, size = 840f)
    private val landscapeCrop = BufferRect(left = 0, top = 192, right = 1280, bottom = 768)

    // Upright, the crop is 576x1280 and is shown at 1.875x: the guide covers upright crop pixels
    // (64, 320) to (512, 768), a 448 pixel square.

    @Test
    fun rotation90MapsTheGuideIntoTheSidewaysBuffer() {
        // Upright (u, v) comes from buffer (v, height - u).
        assertEquals(GridRegion(left = 320, top = 192 + 64, size = 448), GuideMapper.toBufferRegion(view, landscapeCrop, 90))
    }

    @Test
    fun rotation270MapsTheGuideIntoTheSidewaysBuffer() {
        // Upright (u, v) comes from buffer (width - v, u).
        assertEquals(GridRegion(left = 512, top = 192 + 64, size = 448), GuideMapper.toBufferRegion(view, landscapeCrop, 270))
    }

    @Test
    fun rotation0KeepsTheLayout() {
        val portraitCrop = BufferRect(left = 192, top = 0, right = 768, bottom = 1280)
        assertEquals(GridRegion(left = 192 + 64, top = 320, size = 448), GuideMapper.toBufferRegion(view, portraitCrop, 0))
    }

    @Test
    fun rotation180MirrorsBothAxes() {
        val portraitCrop = BufferRect(left = 192, top = 0, right = 768, bottom = 1280)
        // Upright (u, v) comes from buffer (width - u, height - v).
        assertEquals(GridRegion(left = 192 + 64, top = 1280 - 320 - 448, size = 448), GuideMapper.toBufferRegion(view, portraitCrop, 180))
    }

    @Test
    fun negativeAndFullTurnRotationsAreNormalized() {
        assertEquals(GuideMapper.toBufferRegion(view, landscapeCrop, 270), GuideMapper.toBufferRegion(view, landscapeCrop, -90))
        assertEquals(GuideMapper.toBufferRegion(view, landscapeCrop, 90), GuideMapper.toBufferRegion(view, landscapeCrop, 450))
    }

    @Test
    fun offCenterCropIsOffset() {
        // The visible part does not have to be centered in the buffer (or start at its origin).
        val crop = BufferRect(left = 40, top = 100, right = 616, bottom = 1380)
        assertEquals(GridRegion(left = 40 + 64, top = 100 + 320, size = 448), GuideMapper.toBufferRegion(view, crop, 0))

        val sideways = BufferRect(left = 37, top = 211, right = 37 + 1280, bottom = 211 + 576)
        assertEquals(GridRegion(left = 37 + 320, top = 211 + 64, size = 448), GuideMapper.toBufferRegion(view, sideways, 90))
        assertEquals(GridRegion(left = 37 + 512, top = 211 + 64, size = 448), GuideMapper.toBufferRegion(view, sideways, 270))
    }

    @Test
    fun aspectMismatchIsCroppedLikeFillCenter() {
        // A square view over a 4:3 crop: scaled by 1000/960 and cut evenly on the left and right.
        val square = GuideGeometry(viewWidth = 1000, viewHeight = 1000, left = 100f, top = 100f, size = 800f)
        val crop = BufferRect(0, 0, 1280, 960)
        val scale = 1000.0 / 960.0
        val cut = (1280 * scale - 1000) / 2
        val expected = GridRegion(
            left = ((100 + cut) / scale).let(Math::round).toInt(),
            top = (100 / scale).let(Math::round).toInt(),
            size = (800 / scale).let(Math::round).toInt(),
        )
        assertEquals(expected, GuideMapper.toBufferRegion(square, crop, 0))
    }

    @Test
    fun everyGuideCornerLandsOnTheMatchingBufferCorner() {
        // Forward-map each corner of the region back to the view (buffer -> upright -> view) and check
        // it lands on the guide corner the user sees there, for all four rotations.
        for (rotation in listOf(0, 90, 180, 270)) {
            val sideways = rotation % 180 != 0
            val crop = if (sideways) BufferRect(13, 192, 13 + 1280, 768) else BufferRect(192, 7, 768, 1287)
            val region = GuideMapper.toBufferRegion(view, crop, rotation)
            val corners = listOf(
                region.left.toDouble() to region.top.toDouble(),
                (region.left + region.size).toDouble() to region.top.toDouble(),
                region.left.toDouble() to (region.top + region.size).toDouble(),
                (region.left + region.size).toDouble() to (region.top + region.size).toDouble(),
            )
            val inView = corners.map { (x, y) -> bufferToView(x, y, crop, rotation) }
            val minX = inView.minOf { it.first }
            val minY = inView.minOf { it.second }
            val maxX = inView.maxOf { it.first }
            val maxY = inView.maxOf { it.second }
            val tolerance = 2.0 // pixels in the view, from rounding to whole buffer pixels
            assertTrue("rotation $rotation left $minX", abs(minX - view.left) < tolerance)
            assertTrue("rotation $rotation top $minY", abs(minY - view.top) < tolerance)
            assertTrue("rotation $rotation right $maxX", abs(maxX - (view.left + view.size)) < tolerance)
            assertTrue("rotation $rotation bottom $maxY", abs(maxY - (view.top + view.size)) < tolerance)
        }
    }

    @Test
    fun tinyGuideStillGivesAPixel() {
        val tiny = view.copy(size = 0.5f)
        assertEquals(1, GuideMapper.toBufferRegion(tiny, landscapeCrop, 90).size)
    }

    /** What the view shows at buffer point ([x], [y]): turn the crop upright, then FILL_CENTER it. */
    private fun bufferToView(x: Double, y: Double, crop: BufferRect, rotation: Int): Pair<Double, Double> {
        val lx = x - crop.left
        val ly = y - crop.top
        val (u, v) = when (rotation) {
            0 -> lx to ly
            90 -> (crop.height - ly) to lx
            180 -> (crop.width - lx) to (crop.height - ly)
            else -> ly to (crop.width - lx)
        }
        val uprightWidth = if (rotation % 180 == 0) crop.width else crop.height
        val uprightHeight = if (rotation % 180 == 0) crop.height else crop.width
        val scale = maxOf(view.viewWidth.toDouble() / uprightWidth, view.viewHeight.toDouble() / uprightHeight)
        val offsetX = (view.viewWidth - uprightWidth * scale) / 2
        val offsetY = (view.viewHeight - uprightHeight * scale) / 2
        return (u * scale + offsetX) to (v * scale + offsetY)
    }
}
