package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt
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
    fun findsStickersOnFacesWithoutDarkGaps() {
        // White-bodied cubes (white plastic between the stickers) and stickerless cubes (tiles that
        // touch) have no dark gaps to lock onto. The grid is moved to where the sampling windows are
        // uniform, so a guide off by up to 0.3 of a cell still reads every sticker right; on white
        // bodies, stickers that stand out from the plastic are located within 0.2 of a cell. Dim to
        // bright light, with specular highlights.
        val random = Random(41)
        val looks = listOf(
            KnockOffCubes.WHITE_BODY,
            CubeLook("pastel, white body", KnockOffCubes.PASTEL.srgb, SyntheticFaces.Body.WHITE),
            KnockOffCubes.STICKERLESS,
            CubeLook("pastel, stickerless", KnockOffCubes.PASTEL.srgb, SyntheticFaces.Body.STICKERLESS),
        )
        for (look in looks) {
            val faces = SyntheticFaces(random, look)
            var worst = 0.0
            repeat(40) { n ->
                val colors = List(9) { CubeColor.entries[random.nextInt(6)] }
                val shift = random.nextDouble(-0.1, 0.1) to random.nextDouble(-0.1, 0.1)
                val conditions = faces.randomConditions().copy(
                    brightness = random.nextDouble(0.2, 1.25),
                    shift = shift,
                    scale = 1.0,
                    rotationDegrees = 0.0,
                    keystone = 0.0 to 0.0,
                )
                val (image, guide) = faces.render(colors, conditions, imageSize = 160)
                val centers = GridSampler.stickerCenters(image, guide)
                val samples = GridSampler.sample(image, guide)
                for (k in 0 until 9) {
                    val u = k % 3 + 0.5 + 3 * shift.first
                    val v = k / 3 + 0.5 + 3 * shift.second
                    val error = sqrt((centers[2 * k] - u) * (centers[2 * k] - u) + (centers[2 * k + 1] - v) * (centers[2 * k + 1] - v))
                    val standsOut = look.body == SyntheticFaces.Body.WHITE && colors[k] != CubeColor.WHITE
                    if (standsOut) {
                        worst = maxOf(worst, error)
                        assertTrue("$look face $n cell $k: off by $error cells", error <= 0.2)
                    }
                    // The sample is nearest to its own sticker color as rendered there.
                    val light = conditions.brightness * (1.0 + conditions.gradient.first * (k % 3 - 1) / 3.0 + conditions.gradient.second * (k / 3 - 1) / 3.0)
                    val nearest = CubeColor.entries.minBy { c ->
                        val lin = look.linear.getValue(c)
                        val rendered = IntArray(3) { ColorMath.linearToSrgb(lin[it] * light * conditions.whiteBalance[it]) }
                        ColorMath.deltaE(samples[k].lab, StickerSample.of(rendered[0], rendered[1], rendered[2]).lab)
                    }
                    assertEquals("$look face $n cell $k (center off by $error cells): ${samples[k]}", colors[k], nearest)
                }
            }
            println("Faces without dark gaps, $look: stickers standing out located within ${"%.2f".format(worst)} cells (guide off by up to 0.42)")
        }
    }

    /** A 640x480 camera frame with a rendered face in the middle, and its guide region. */
    private fun cameraFrame(): Pair<IntImage, GridRegion> {
        val faces = SyntheticFaces(Random(1))
        val colors = CubeColor.entries + CubeColor.entries.take(3)
        val (face, _) = faces.render(colors, faces.randomConditions(), imageSize = 400)
        val frame = IntImage(640, 480)
        for (y in 0 until 400) for (x in 0 until 400) frame[x + 120, y + 40] = face.argb(x, y)
        return frame to GridRegion(120 + 44, 40 + 44, 312)
    }

    @Test
    fun isFastEnoughForLivePreview() {
        val (frame, region) = cameraFrame()
        repeat(300) { GridSampler.sample(frame, region, 90) }
        val runs = 1000
        val start = System.nanoTime()
        repeat(runs) { GridSampler.sample(frame, region, 90) }
        val perCallMs = (System.nanoTime() - start) / 1e6 / runs
        println("GridSampler.sample: %.3f ms per call (640x480 frame)".format(perCallMs))
        assertTrue("GridSampler took $perCallMs ms per call", perCallMs < 5.0)
    }

    @Test
    fun allocatesLittleMoreThanItsResult() {
        // Live preview samples every analyzed frame; per-call garbage would churn the GC on a phone.
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("per-thread allocation counter unavailable", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val (frame, region) = cameraFrame()
        val thread = Thread.currentThread().id
        repeat(300) { GridSampler.sample(frame, region, 90) }
        val runs = 1000
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(runs) { GridSampler.sample(frame, region, 90) }
        val perCall = (bean.getThreadAllocatedBytes(thread) - before).toDouble() / runs
        println("GridSampler.sample: %.2f KB allocated per call".format(perCall / 1024))
        // The result itself (nine samples with their Lab values and the list) is about 0.6 KB.
        assertTrue("GridSampler allocated $perCall bytes per call", perCall < 4096)
    }

    @Test
    fun concurrentCallsDoNotShareScratchState() {
        val faces = SyntheticFaces(Random(12))
        val inputs = List(8) { k ->
            val colors = List(9) { CubeColor.entries[(it + k) % 6] }
            faces.render(colors, faces.randomConditions(), imageSize = 200)
        }
        val expected = inputs.map { (img, guide) -> GridSampler.sample(img, guide) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(64) { n -> Callable { n % inputs.size to inputs[n % inputs.size].let { (img, guide) -> GridSampler.sample(img, guide) } } }
            for (future in pool.invokeAll(tasks)) {
                val (k, samples) = future.get()
                assertEquals("input $k", expected[k], samples)
            }
        } finally {
            pool.shutdown()
        }
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
