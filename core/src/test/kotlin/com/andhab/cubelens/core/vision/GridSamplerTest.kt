package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class GridSamplerTest {

    /** Nine clearly different colors, so the output order can be checked cell by cell. */
    private val palette = listOf(
        0xC81E1E, 0x1EA03C, 0x1E50B4, 0xE6D232, 0xE6781E, 0xC8C8D2, 0x8C3CA0, 0x3CB4B4, 0xA0A01E,
    )

    /** A face with flat stickers on black plastic at [region] of a [width] x [height] image. */
    private fun flatFace(width: Int, height: Int, region: GridRegion, colors: List<Int> = palette): IntImage {
        val img = IntImage(width, height, IntArray(width * height) { 0xFF5A6E50.toInt() })
        val cell = region.size / 3.0
        for (y in region.top until region.top + region.size) {
            for (x in region.left until region.left + region.size) {
                val fx = (x + 0.5 - region.left) / cell
                val fy = (y + 0.5 - region.top) / cell
                val col = fx.toInt().coerceIn(0, 2)
                val row = fy.toInt().coerceIn(0, 2)
                val inSticker = abs(fx - col - 0.5) < 0.39 && abs(fy - row - 0.5) < 0.39
                img[x, y] = if (inSticker) (0xFF shl 24) or colors[row * 3 + col] else 0xFF1C1C1E.toInt()
            }
        }
        return img
    }

    private fun assertSamples(expected: List<Int>, samples: List<StickerSample>, message: String) {
        assertEquals(9, samples.size)
        for (i in 0 until 9) {
            val e = expected[i]
            val s = samples[i]
            val ok = abs(s.r - (e shr 16 and 0xFF)) <= 2 && abs(s.g - (e shr 8 and 0xFF)) <= 2 && abs(s.b - (e and 0xFF)) <= 2
            assertTrue("$message: cell $i expected ${"%06X".format(e)} got ${"%06X".format(s.argb and 0xFFFFFF)}", ok)
        }
    }

    @Test
    fun outputIsRowMajorAsSeenUprightForEveryCameraRotation() {
        // A landscape frame with the guide off-center, so a wrong rotation mapping lands elsewhere.
        val region = GridRegion(left = 150, top = 40, size = 210)
        val upright = flatFace(400, 300, region)
        for (rotation in listOf(0, 90, 180, 270)) {
            val buffer = upright.asBufferNeedingRotation(rotation)
            val bufferRegion = upright.regionInBuffer(region, rotation)
            assertSamples(palette, GridSampler.sample(buffer, bufferRegion, rotation), "rotation $rotation")
            assertSamples(palette, GridSampler.sample(buffer, bufferRegion, rotation - 360), "rotation ${rotation - 360}")
        }
    }

    @Test
    fun ignoringTheCameraRotationReadsTheFaceTurned() {
        val region = GridRegion(20, 20, 240)
        val buffer = flatFace(280, 280, region).asBufferNeedingRotation(90)
        // Read as-is, the buffer shows the face turned a quarter counter-clockwise: the top row is the
        // upright right column, top to bottom.
        val asIs = GridSampler.sample(buffer, region, 0)
        val turned = listOf(2, 5, 8, 1, 4, 7, 0, 3, 6).map { palette[it] }
        assertSamples(turned, asIs, "unrotated read")
        assertNotEquals(asIs, GridSampler.sample(buffer, region, 90))
    }

    @Test
    fun ignoresBlackGapsGlareAndPrint() {
        val region = GridRegion(16, 16, 300)
        val red = 0xB4191E
        val img = flatFace(332, 332, region, List(9) { red })
        val cell = region.size / 3.0
        val random = Random(3)
        for (k in 0 until 9) {
            val cx = region.left + (k % 3 + 0.5) * cell
            val cy = region.top + (k / 3 + 0.5) * cell
            // A specular highlight blob and a dark "logo" stroke on every sticker.
            val hx = cx + random.nextDouble(-0.15, 0.15) * cell
            val hy = cy + random.nextDouble(-0.15, 0.15) * cell
            for (y in (cy - 0.4 * cell).toInt()..(cy + 0.4 * cell).toInt()) {
                for (x in (cx - 0.4 * cell).toInt()..(cx + 0.4 * cell).toInt()) {
                    val d = ((x - hx) * (x - hx) + (y - hy) * (y - hy)) / (0.12 * cell * 0.12 * cell)
                    if (d < 1.0) img[x, y] = 0xFFF5F0F0.toInt()
                    if (abs((x - cx) - (y - cy)) < 0.03 * cell) img[x, y] = 0xFF141414.toInt()
                }
            }
        }
        val reference = StickerSample.ofArgb(red)
        for (s in GridSampler.sample(img, region)) {
            assertTrue("sample ${s.argb.toString(16)} too far from the sticker color", ColorMath.deltaE(s.lab, reference.lab) < 2f)
        }
    }

    @Test
    fun locksOntoStickersWhenTheGuideIsOff() {
        val random = Random(11)
        val faces = SyntheticFaces(random)
        val colors = listOf(CubeColor.RED, CubeColor.WHITE, CubeColor.BLUE, CubeColor.YELLOW, CubeColor.GREEN, CubeColor.ORANGE, CubeColor.BLUE, CubeColor.RED, CubeColor.WHITE)
        for ((shift, scale) in listOf((0.06 to -0.05) to 0.92, (-0.05 to 0.06) to 1.08, (0.0 to 0.0) to 1.0)) {
            val conditions = faces.randomConditions().copy(shift = shift, scale = scale, rotationDegrees = 3.0, keystone = 0.0 to 0.0)
            val (img, guide) = faces.render(colors, conditions, imageSize = 300)
            val centers = GridSampler.stickerCenters(img, guide)
            for (k in 0 until 9) {
                // Where the sticker really is, in guide cell units (rotation ignored: < 0.08 cell at 3 degrees).
                val expectedU = 1.5 + shift.first * 3 + (k % 3 - 1) * scale
                val expectedV = 1.5 + shift.second * 3 + (k / 3 - 1) * scale
                assertEquals("cell $k u", expectedU, centers[2 * k], 0.15)
                assertEquals("cell $k v", expectedV, centers[2 * k + 1], 0.15)
            }
            assertEquals(colors, GridSampler.sample(img, guide).map(LiveClassifier::classify))
        }
    }

    @Test
    fun isFastEnoughForLivePreview() {
        val faces = SyntheticFaces(Random(1))
        val colors = CubeColor.entries + CubeColor.entries.take(3)
        val (face, _) = faces.render(colors, faces.randomConditions(), imageSize = 400)
        // A 640x480 camera frame with the face in the middle.
        val frame = IntImage(640, 480)
        for (y in 0 until 400) for (x in 0 until 400) frame[x + 120, y + 40] = face.argb(x, y)
        val region = GridRegion(120 + 44, 40 + 44, 312)
        repeat(300) { GridSampler.sample(frame, region, 90) }
        val runs = 1000
        val start = System.nanoTime()
        repeat(runs) { GridSampler.sample(frame, region, 90) }
        val perCallMs = (System.nanoTime() - start) / 1e6 / runs
        println("GridSampler.sample: %.3f ms per call (640x480 frame)".format(perCallMs))
        assertTrue("GridSampler took $perCallMs ms per call", perCallMs < 5.0)
    }

    @Test
    fun rejectsInvalidArguments() {
        val img = IntImage(10, 10)
        assertThrows(IllegalArgumentException::class.java) { GridSampler.sample(img, GridRegion(0, 0, 9), 45) }
        assertThrows(IllegalArgumentException::class.java) { GridSampler.sample(img, GridRegion(0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { GridSampler.sample(IntImage(0, 0), GridRegion(0, 0, 9)) }
        // A guide partly outside the image is clamped rather than failing.
        assertEquals(9, GridSampler.sample(img, GridRegion(-5, 5, 30)).size)
    }
}
