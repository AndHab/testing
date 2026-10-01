package com.andhab.cubelens.camera

import android.graphics.PixelFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.andhab.cubelens.core.vision.PixelSource
import java.nio.ByteBuffer

/**
 * A [PixelSource] reading an RGBA_8888 image plane in place, without copying the frame.
 *
 * Coordinates are buffer pixels. Reads outside [crop] are clamped to its edge, so pixels the user
 * cannot see on screen never influence a reading, even where the sampler looks a little beyond the
 * guide. Rows may be padded ([rowStride] larger than `width * pixelStride`); only absolute reads are
 * used, so the buffer's position and limit are left untouched.
 *
 * @param buffer the plane's bytes: R, G, B, A for each pixel.
 * @param rowStride bytes from the start of one row to the start of the next.
 * @param pixelStride bytes from one pixel to the next within a row (4 for RGBA_8888).
 * @param crop the visible part of the image; defaults to the whole image.
 */
class RgbaPixelSource(
    private val buffer: ByteBuffer,
    override val width: Int,
    override val height: Int,
    private val rowStride: Int,
    private val pixelStride: Int = 4,
    crop: BufferRect = BufferRect(0, 0, width, height),
) : PixelSource {

    private val minX = crop.left.coerceIn(0, width - 1)
    private val maxX = (crop.right - 1).coerceIn(minX, width - 1)
    private val minY = crop.top.coerceIn(0, height - 1)
    private val maxY = (crop.bottom - 1).coerceIn(minY, height - 1)

    init {
        require(width > 0 && height > 0) { "Empty image: ${width}x$height" }
        require(pixelStride >= 4) { "RGBA pixels need at least 4 bytes, got pixelStride $pixelStride" }
        require(rowStride >= width * pixelStride) { "rowStride $rowStride is too small for $width pixels" }
        val lastPixel = (height - 1) * rowStride + (width - 1) * pixelStride + 3
        require(buffer.capacity() > lastPixel) { "Buffer of ${buffer.capacity()} bytes is too small" }
    }

    override fun argb(x: Int, y: Int): Int {
        val index = y.coerceIn(minY, maxY) * rowStride + x.coerceIn(minX, maxX) * pixelStride
        val r = buffer.get(index).toInt() and 0xFF
        val g = buffer.get(index + 1).toInt() and 0xFF
        val b = buffer.get(index + 2).toInt() and 0xFF
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24
    }
}

/** The visible part of this frame as a [BufferRect]. */
fun Rect.toBufferRect(): BufferRect = BufferRect(left, top, right, bottom)

/**
 * Reads this frame's first plane in place. The frame must come from an `ImageAnalysis` configured
 * with `OUTPUT_IMAGE_FORMAT_RGBA_8888`, and the source is only valid until the frame is closed.
 */
fun ImageProxy.toRgbaPixelSource(): RgbaPixelSource {
    require(format == PixelFormat.RGBA_8888) { "Expected an RGBA_8888 frame, got format $format" }
    val plane = planes[0]
    return RgbaPixelSource(
        buffer = plane.buffer,
        width = width,
        height = height,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        crop = cropRect.toBufferRect(),
    )
}
