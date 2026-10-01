package com.andhab.cubelens.camera

import com.andhab.cubelens.core.vision.GridRegion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The on-screen scan guide: a square at ([left], [top]) with side [size], in pixels of the camera
 * preview view, which is [viewWidth] x [viewHeight] pixels.
 */
data class GuideGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val left: Float,
    val top: Float,
    val size: Float,
) {
    init {
        require(viewWidth > 0 && viewHeight > 0) { "Empty view: ${viewWidth}x$viewHeight" }
        require(size > 0f) { "Empty guide: $size" }
    }

    /** Horizontal center of the guide, in view pixels. */
    val centerX: Float get() = left + size / 2f

    /** Vertical center of the guide, in view pixels. */
    val centerY: Float get() = top + size / 2f
}

/**
 * An axis-aligned rectangle of image buffer pixels, `left until right` by `top until bottom`
 * (the same convention as `android.graphics.Rect`).
 */
data class BufferRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right > left && bottom > top) { "Empty rectangle: $this" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Maps the scan guide from preview coordinates to camera frame coordinates.
 *
 * The preview and the analysis stream are bound with the preview's viewport, so the analysis
 * frame's crop rectangle is exactly the part of the sensor image the user sees. Turned upright by
 * the frame's rotation, that crop is shown in the view the way `PreviewView` does with
 * `FILL_CENTER`: scaled uniformly to cover the view and centered. This inverts that transform.
 */
object GuideMapper {

    /**
     * The square of the analysis frame buffer that shows what the user sees inside [guide].
     *
     * @param crop the visible part of the frame (`ImageProxy.cropRect`), in buffer pixels.
     * @param rotationDegrees clockwise rotation that turns the buffer upright (`ImageInfo.rotationDegrees`):
     *   0, 90, 180 or 270.
     * @return the region in buffer pixels, to pass to
     *   [com.andhab.cubelens.core.vision.GridSampler.sample] together with [rotationDegrees].
     */
    fun toBufferRegion(guide: GuideGeometry, crop: BufferRect, rotationDegrees: Int): GridRegion {
        val rotation = Math.floorMod(rotationDegrees, 360)
        require(rotation % 90 == 0) { "rotationDegrees must be a multiple of 90, got $rotationDegrees" }
        val sideways = rotation == 90 || rotation == 270
        // Size of the crop once turned upright, as it appears on screen (before scaling).
        val uprightWidth = (if (sideways) crop.height else crop.width).toDouble()
        val uprightHeight = (if (sideways) crop.width else crop.height).toDouble()

        // FILL_CENTER: uniform scale that covers the view, centered (overflow is cut off evenly).
        val scale = max(guide.viewWidth / uprightWidth, guide.viewHeight / uprightHeight)
        val offsetX = (guide.viewWidth - uprightWidth * scale) / 2.0
        val offsetY = (guide.viewHeight - uprightHeight * scale) / 2.0

        // The guide in upright crop pixels.
        val u = (guide.left - offsetX) / scale
        val v = (guide.top - offsetY) / scale
        val side = guide.size / scale

        // Back into the buffer: undo the clockwise rotation. A square stays a square; only which of
        // its corners is the buffer's top-left changes.
        val x: Double
        val y: Double
        when (rotation) {
            0 -> {
                x = u
                y = v
            }
            90 -> {
                x = v
                y = crop.height - u - side
            }
            180 -> {
                x = crop.width - u - side
                y = crop.height - v - side
            }
            else -> {
                x = crop.width - v - side
                y = u
            }
        }
        return GridRegion(
            left = crop.left + x.roundToInt(),
            top = crop.top + y.roundToInt(),
            size = side.roundToInt().coerceAtLeast(1),
        )
    }
}
