package com.andhab.cubelens.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class RgbaPixelSourceTest {

    /** A 3x2 RGBA image whose rows are padded to 16 bytes, padding filled with 0x7F garbage. */
    private fun paddedImage(): ByteBuffer {
        val rowStride = 16
        val bytes = ByteArray(rowStride * 2) { 0x7F }
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val i = y * rowStride + x * 4
            bytes[i] = r.toByte()
            bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte()
            bytes[i + 3] = 0xFF.toByte()
        }
        put(0, 0, 255, 0, 0)
        put(1, 0, 0, 255, 0)
        put(2, 0, 0, 0, 255)
        put(0, 1, 10, 20, 30)
        put(1, 1, 200, 150, 100)
        put(2, 1, 255, 255, 255)
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun readsPixelsThroughRowPadding() {
        val source = RgbaPixelSource(paddedImage(), width = 3, height = 2, rowStride = 16)
        assertEquals(0xFFFF0000.toInt(), source.argb(0, 0))
        assertEquals(0xFF00FF00.toInt(), source.argb(1, 0))
        assertEquals(0xFF0000FF.toInt(), source.argb(2, 0))
        assertEquals(0xFF0A141E.toInt(), source.argb(0, 1))
        assertEquals(0xFFC89664.toInt(), source.argb(1, 1))
        assertEquals(0xFFFFFFFF.toInt(), source.argb(2, 1))
    }

    @Test
    fun clampsToTheVisibleCrop() {
        // Only the right column is visible: everything left of it reads as its nearest visible pixel.
        val source = RgbaPixelSource(paddedImage(), width = 3, height = 2, rowStride = 16, crop = BufferRect(2, 0, 3, 2))
        assertEquals(0xFF0000FF.toInt(), source.argb(0, 0))
        assertEquals(0xFF0000FF.toInt(), source.argb(1, 0))
        assertEquals(0xFFFFFFFF.toInt(), source.argb(0, 1))
        // Coordinates are still buffer coordinates.
        assertEquals(0xFFFFFFFF.toInt(), source.argb(2, 1))
        // Outside the image entirely: clamped too, never reading row padding or past the buffer.
        assertEquals(0xFFFFFFFF.toInt(), source.argb(99, 99))
        assertEquals(0xFF0000FF.toInt(), source.argb(-5, -5))
    }

    @Test
    fun cropIsClippedToTheImage() {
        val source = RgbaPixelSource(paddedImage(), width = 3, height = 2, rowStride = 16, crop = BufferRect(1, 1, 10, 10))
        assertEquals(0xFFC89664.toInt(), source.argb(0, 0))
        assertEquals(0xFFFFFFFF.toInt(), source.argb(5, 5))
    }

    @Test
    fun honorsPixelStrideAndLeavesTheBufferPositionAlone() {
        // 8 bytes per pixel (RGBA plus 4 padding bytes), 2x1.
        val bytes = byteArrayOf(1, 2, 3, -1, 9, 9, 9, 9, 4, 5, 6, -1, 9, 9, 9, 9)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(5)
        val source = RgbaPixelSource(buffer, width = 2, height = 1, rowStride = 16, pixelStride = 8)
        assertEquals(0xFF010203.toInt(), source.argb(0, 0))
        assertEquals(0xFF040506.toInt(), source.argb(1, 0))
        assertEquals(5, buffer.position())
    }

    @Test
    fun rejectsInconsistentLayouts() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaPixelSource(ByteBuffer.allocate(16), width = 3, height = 2, rowStride = 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaPixelSource(ByteBuffer.allocate(20), width = 3, height = 2, rowStride = 12)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaPixelSource(ByteBuffer.allocate(64), width = 3, height = 2, rowStride = 12, pixelStride = 3)
        }
    }
}
