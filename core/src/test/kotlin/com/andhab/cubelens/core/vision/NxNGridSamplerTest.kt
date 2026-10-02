package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.random.Random

/** [GridSampler] on N×N faces (N = 2..10). */
class NxNGridSamplerTest {

    /** [count] clearly different colors (0xRRGGBB): hues around the wheel at two saturations. */
    private fun distinctColors(count: Int): List<Int> = List(count) { i ->
        val hue = i.toFloat() / count
        val saturation = if (i % 2 == 0) 0.85f else 0.55f
        Color.HSBtoRGB(hue, saturation, 0.85f) and 0xFFFFFF
    }

    /** A face with flat [n]x[n] stickers on black plastic at [region] of a [width] x [height] image. */
    private fun flatFace(width: Int, height: Int, region: GridRegion, n: Int, colors: List<Int>): IntImage {
        val img = IntImage(width, height, IntArray(width * height) { 0xFF5A6E50.toInt() })
        val cell = region.size.toDouble() / n
        for (y in region.top until region.top + region.size) {
            for (x in region.left until region.left + region.size) {
                val fx = (x + 0.5 - region.left) / cell
                val fy = (y + 0.5 - region.top) / cell
                val col = fx.toInt().coerceIn(0, n - 1)
                val row = fy.toInt().coerceIn(0, n - 1)
                val inSticker = abs(fx - col - 0.5) < 0.38 && abs(fy - row - 0.5) < 0.38
                img[x, y] = if (inSticker) (0xFF shl 24) or colors[row * n + col] else 0xFF1C1C1E.toInt()
            }
        }
        return img
    }

    private fun assertSamples(expected: List<Int>, samples: List<StickerSample>, message: String) {
        assertEquals(expected.size, samples.size)
        for (i in expected.indices) {
            val e = expected[i]
            val s = samples[i]
            val ok = abs(s.r - (e shr 16 and 0xFF)) <= 2 && abs(s.g - (e shr 8 and 0xFF)) <= 2 && abs(s.b - (e and 0xFF)) <= 2
            assertTrue("$message: cell $i expected ${"%06X".format(e)} got ${"%06X".format(s.argb and 0xFFFFFF)}", ok)
        }
    }

    @Test
    fun outputIsRowMajorAsSeenUprightForEveryCameraRotationAndSize() {
        // A landscape frame with the guide off-center, so a wrong rotation mapping lands elsewhere.
        for (n in 2..10) {
            val colors = distinctColors(n * n)
            val region = GridRegion(left = 150, top = 40, size = 22 * n + 100)
            val upright = flatFace(region.left + region.size + 60, region.top + region.size + 30, region, n, colors)
            for (rotation in listOf(0, 90, 180, 270)) {
                val buffer = upright.asBufferNeedingRotation(rotation)
                val bufferRegion = upright.regionInBuffer(region, rotation)
                assertSamples(colors, GridSampler.sample(buffer, bufferRegion, rotation, n), "${n}x$n, rotation $rotation")
                assertSamples(colors, GridSampler.sample(buffer, bufferRegion, rotation - 360, n), "${n}x$n, rotation ${rotation - 360}")
            }
        }
    }

    @Test
    fun ignoringTheCameraRotationReadsTheFaceTurned() {
        for (n in listOf(2, 4, 5, 7)) {
            val colors = distinctColors(n * n)
            val region = GridRegion(20, 20, 30 * n)
            val buffer = flatFace(30 * n + 40, 30 * n + 40, region, n, colors).asBufferNeedingRotation(90)
            // Read as-is, the buffer shows the face turned a quarter counter-clockwise: the top row is
            // the upright right column, top to bottom.
            val asIs = GridSampler.sample(buffer, region, 0, n)
            val turned = List(n * n) { p -> colors[(p % n) * n + (n - 1 - p / n)] }
            assertSamples(turned, asIs, "${n}x$n unrotated read")
            assertNotEquals(asIs, GridSampler.sample(buffer, region, 90, n))
            // ...which is exactly the face turned by NxNOrientationFixer.rotateFace's convention.
            val readColors = asIs.map { s -> colors.indexOfFirst { abs((it shr 16 and 0xFF) - s.r) <= 2 && abs((it shr 8 and 0xFF) - s.g) <= 2 && abs((it and 0xFF) - s.b) <= 2 } }
            val clockwise = List(n * n) { p -> readColors[(n - 1 - p % n) * n + p / n] }
            assertEquals(List(n * n) { it }, clockwise)
        }
    }

    @Test
    fun theDefaultSizeIsThreeAndUnchanged() {
        val random = Random(5)
        val faces = SyntheticFaces(random)
        repeat(10) {
            val (image, guide) = faces.render(List(9) { CubeColor.entries[random.nextInt(6)] }, faces.randomConditions())
            assertEquals(GridSampler.sample(image, guide), GridSampler.sample(image, guide, 0, 3))
            assertArrayEquals(GridSampler.stickerCenters(image, guide), GridSampler.stickerCenters(image, guide, 0, 3), 0.0)
        }
    }

    @Test
    fun locksOntoStickersWhenTheGuideIsOff() {
        // The face shifted by up to a third of a cell and scaled by 5%, as a big face held a little off
        // the guide: every sticker is located within 0.12 of a cell.
        val random = Random(11)
        for (n in listOf(4, 5, 7)) {
            val faces = SyntheticFaces(random, KnockOffCubes.VIVID)
            for ((shift, scale) in listOf((0.3 to -0.25) to 0.95, (-0.25 to 0.3) to 1.05, (0.0 to 0.0) to 1.0)) {
                val colors = List(n * n) { CubeColor.entries[random.nextInt(6)] }
                val conditions = faces.randomConditions().copy(shift = shift.first / n to shift.second / n, scale = scale, rotationDegrees = 0.0, keystone = 0.0 to 0.0)
                val (img, guide) = faces.render(n, colors, conditions, 30 * n)
                val centers = GridSampler.stickerCenters(img, guide, 0, n)
                for (k in 0 until n * n) {
                    val expectedU = n / 2.0 + shift.first + (k % n - (n - 1) / 2.0) * scale
                    val expectedV = n / 2.0 + shift.second + (k / n - (n - 1) / 2.0) * scale
                    assertEquals("${n}x$n cell $k u", expectedU, centers[2 * k], 0.12)
                    assertEquals("${n}x$n cell $k v", expectedV, centers[2 * k + 1], 0.12)
                }
            }
        }
    }

    @Test
    fun readsRenderedFacesOfEverySizeAndBody() {
        // Faces as a phone sees them (exposure, casts, uneven light, highlights, a guide off by a few
        // percent, rotation up to 3 degrees and perspective), black-bodied, white-bodied and stickerless,
        // vivid and pastel: each sample must be nearest to its own sticker color as rendered there.
        val looks = listOf(
            KnockOffCubes.VIVID,
            KnockOffCubes.PASTEL,
            KnockOffCubes.WHITE_BODY,
            KnockOffCubes.STICKERLESS,
            CubeLook("pastel, stickerless", KnockOffCubes.PASTEL.srgb, SyntheticFaces.Body.STICKERLESS),
        )
        val report = StringBuilder("Sampler, rendered faces (sticker read nearest to its own color):\n")
        for (look in looks) {
            report.append("  $look:")
            for (n in listOf(2, 4, 5, 6, 7)) {
                val random = Random(100 * n + look.name.length)
                val renderer = SyntheticFaces(random, look)
                var wrong = 0
                var total = 0
                repeat(FACES) {
                    val colors = List(n * n) { CubeColor.entries[random.nextInt(6)] }
                    val conditions = NxNSessions.conditions(NxNSessions.Light.NORMAL, doubleArrayOf(1.0, 1.0, 1.0), random)
                    val (image, guide) = renderer.render(n, colors, conditions, NxNSessions.guideSize(n))
                    val samples = GridSampler.sample(image, guide, 0, n)
                    val session = NxNSessions.Session(n, listOf(Face.F), listOf(colors), listOf(samples), listOf(0), listOf(conditions), conditions.whiteBalance)
                    wrong += NxNSessions.samplerErrors(session, look).size
                    total += n * n
                }
                report.append(" ${n}x$n ${total - wrong}/$total;")
                // At most one misread sticker in 500 (or a single one) on every size and body.
                assertTrue("$look ${n}x$n: $wrong of $total stickers misread", wrong <= maxOf(1, total / 500))
            }
            report.append('\n')
        }
        print(report)
    }

    @Test
    fun whiteBodiesUnderAColorCastAreNotTakenForDarkGaps() {
        // On a white body the darkest stickers (red, blue) can form the darker class of the lattice on
        // their own, and under a cast they can be dark enough to pass for gaps; locking onto them put
        // the sampling windows on the white plastic. Each of these runs of 60 faces had 1 to 4 faces
        // misread that way. The dark class must also run across the face as gaps do.
        val report = StringBuilder("White bodies under casts (stickers read right):")
        val pastelWhite = CubeLook("pastel white body", KnockOffCubes.PASTEL.srgb, SyntheticFaces.Body.WHITE)
        val runs = listOf(
            Triple(KnockOffCubes.WHITE_BODY, 7, NxNSessions.Light.COOL) to 107119,
            Triple(KnockOffCubes.WHITE_BODY, 6, NxNSessions.Light.COOL) to 106119,
            Triple(pastelWhite, 7, NxNSessions.Light.NORMAL) to 407099,
            Triple(pastelWhite, 7, NxNSessions.Light.MIXED) to 407129,
            Triple(pastelWhite, 6, NxNSessions.Light.COOL) to 406119,
        )
        for ((setup, seed) in runs) {
            val (look, n, light) = setup
            val random = Random(seed)
            val renderer = SyntheticFaces(random, look)
            var wrong = 0
            var total = 0
            repeat(CAST_FACES) {
                val colors = List(n * n) { CubeColor.entries[random.nextInt(6)] }
                val cast = NxNSessions.sessionCast(light, random)
                val conditions = NxNSessions.conditions(light, cast, random)
                val (image, guide) = renderer.render(n, colors, conditions, NxNSessions.guideSize(n))
                val samples = GridSampler.sample(image, guide, 0, n)
                val session = NxNSessions.Session(n, listOf(Face.F), listOf(colors), listOf(samples), listOf(0), listOf(conditions), cast)
                wrong += NxNSessions.samplerErrors(session, look).size
                total += n * n
            }
            report.append(" $look ${n}x$n ${light.name.lowercase()} ${total - wrong}/$total;")
            assertEquals("$look ${n}x$n $light: misread stickers", 0, wrong)
        }
        println(report)
    }

    @Test
    fun aFitThroughImpreciselyLocatedCellsDoesNotWarpTheGrid() {
        // A pastel stickerless 6x6 in warm light (the fourth session of this seed): tiles of one color
        // merge into large patches, so only 11 of the 36 cells of one photo were located, imprecisely,
        // and a homography through them predicted a squeezed, skewed grid that read nine stickers of
        // its top rows off their tiles. A fit is used only if it predicts a regular grid.
        val look = CubeLook("pastel stickerless", KnockOffCubes.PASTEL.srgb, SyntheticFaces.Body.STICKERLESS)
        val random = Random(621090)
        val renderer = SyntheticFaces(random, look)
        var session: NxNSessions.Session? = null
        repeat(4) { session = NxNSessions.scan(renderer, 6, NxNSessions.scrambled(6, random).toColors(), NxNSessions.Light.WARM, random) }
        assertEquals(emptyList<Pair<Int, Int>>(), NxNSessions.samplerErrors(session!!, look))
    }

    @Test
    fun tinyCellsFallBackToTheGuideGrid() {
        // A 7x7 face in a 35-pixel guide: 5 pixels per cell, too few to find the gaps. The stickers are
        // read where the guide puts them, without failing.
        val n = 7
        val colors = distinctColors(n * n)
        val region = GridRegion(3, 3, 35)
        val image = flatFace(41, 41, region, n, colors)
        val centers = GridSampler.stickerCenters(image, region, 0, n)
        for (k in 0 until n * n) {
            assertEquals(k % n + 0.5, centers[2 * k], 1e-9)
            assertEquals(k / n + 0.5, centers[2 * k + 1], 1e-9)
        }
        assertEquals(n * n, GridSampler.sample(image, region, 0, n).size)
        // A guide partly outside the image is clamped rather than failing, at any size.
        for (size in 2..10) assertEquals(size * size, GridSampler.sample(IntImage(10, 10), GridRegion(-5, 5, 30), 0, size).size)
    }

    @Test
    fun rejectsUnsupportedSizes() {
        val img = IntImage(100, 100)
        for (n in listOf(-1, 0, 1, 11)) {
            assertThrows(IllegalArgumentException::class.java) { GridSampler.sample(img, GridRegion(0, 0, 90), 0, n) }
        }
    }

    /** A 640x480 camera frame with a rendered [n]x[n] face in the middle (about 312 pixels), and its guide. */
    private fun cameraFrame(n: Int, look: CubeLook = KnockOffCubes.VIVID): Pair<IntImage, GridRegion> {
        val faces = SyntheticFaces(Random(1), look)
        val colors = List(n * n) { CubeColor.entries[it % 6] }
        val (face, guide) = faces.render(n, colors, faces.randomConditions(), 312)
        val frame = IntImage(640, 480)
        val left = (640 - face.width) / 2
        val top = (480 - face.height) / 2
        for (y in 0 until face.height) for (x in 0 until face.width) frame[x + left, y + top] = face.argb(x, y)
        return frame to GridRegion(left + guide.left, top + guide.top, guide.size)
    }

    @Test
    fun isFastEnoughForLivePreviewOnBigFaces() {
        val report = StringBuilder("GridSampler.sample on a 640x480 frame (ms per call):")
        for (n in listOf(2, 3, 4, 5, 6, 7)) {
            for (look in listOf(KnockOffCubes.VIVID, KnockOffCubes.WHITE_BODY)) {
                val (frame, region) = cameraFrame(n, look)
                repeat(60) { GridSampler.sample(frame, region, 90, n) }
                val runs = 150
                val start = System.nanoTime()
                repeat(runs) { GridSampler.sample(frame, region, 90, n) }
                val perCallMs = (System.nanoTime() - start) / 1e6 / runs
                report.append(" ${n}x$n ${if (look == KnockOffCubes.VIVID) "black" else "white"} body %.2f;".format(perCallMs))
                // A few milliseconds even for 7x7 (shared CI machines are slower than a phone's big core).
                assertTrue("${n}x$n $look: $perCallMs ms per call", perCallMs < 15.0)
            }
        }
        println(report)
    }

    @Test
    fun allocatesLittleMoreThanItsResultOnBigFaces() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("per-thread allocation counter unavailable", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val n = 7
        val (frame, region) = cameraFrame(n)
        repeat(100) { GridSampler.sample(frame, region, 90, n) }
        val runs = 200
        val before = bean.currentThreadAllocatedBytes
        repeat(runs) { GridSampler.sample(frame, region, 90, n) }
        val perCall = (bean.currentThreadAllocatedBytes - before).toDouble() / runs
        println("GridSampler.sample 7x7: %.2f KB allocated per call".format(perCall / 1024))
        // The result (49 samples with their Lab values and the list) is about 3.5 KB.
        assertTrue("GridSampler allocated $perCall bytes per call", perCall < 12 * 1024)
    }

    private companion object {
        const val FACES = 40
        const val CAST_FACES = 60
    }
}
