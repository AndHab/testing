package com.andhab.cubelens.ui.cube

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.nxn.LayerMove
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A rough JVM benchmark of [CubeRenderer] on Robolectric's native (Skia) canvas.
 *
 * Absolute times are not device times: on a phone HWUI only records the draw calls on the UI thread
 * and rasterizes on the GPU, so the recording time below is the relevant per-frame CPU cost, and
 * software rasterization of a 1080 px frame is a pessimistic upper bound. What this guards is that
 * a 7×7 costs about the same order as a 3×3 (the painter's algorithm draws a few box faces, not
 * hundreds of cubies) and that drawing allocates nothing on the Java heap.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CubeRendererBenchmarkTest {

    @Test
    fun bigCubesDrawAtAComparableCostWithoutAllocating() {
        val results = mutableMapOf<Int, Pair<Double, Double>>()
        for (n in listOf(3, 5, 7)) {
            val frame = CubeFrame().apply {
                size = n
                colors = TestCubes.scrambled(n)
                yaw = -35f
                pitch = 28f
                move = LayerMove.parse(if (n == 3) "R" else "3Uw'")
                highlights = highlightTable(setOf(0, 5))
                pulse = 0.5f
            }
            val renderer = CubeRenderer()
            val picture = Picture()
            val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            val raster = Canvas(bitmap)

            fun record(i: Int) {
                frame.progress = (i % 60) / 60f
                frame.yaw = -35f + i * 0.5f
                val canvas = picture.beginRecording(SIDE, SIDE)
                renderer.draw(canvas, SIDE.toFloat(), SIDE.toFloat(), frame)
                picture.endRecording()
            }
            repeat(WARM_UP) { record(it) }
            var start = System.nanoTime()
            repeat(FRAMES) { record(it) }
            val recordMs = (System.nanoTime() - start) / 1e6 / FRAMES

            fun raster(i: Int) {
                frame.progress = (i % 60) / 60f
                renderer.draw(raster, SIDE.toFloat(), SIDE.toFloat(), frame)
            }
            repeat(WARM_UP / 4) { raster(it) }
            start = System.nanoTime()
            // Same canvas every frame (Picture.beginRecording creates a new canvas per call).
            val bytes = allocatedBytes { repeat(FRAMES / 4) { raster(it) } }
            val rasterMs = (System.nanoTime() - start) / 1e6 / (FRAMES / 4)
            results[n] = recordMs to rasterMs
            println(
                "CubeRenderer ${n}x$n: record %.3f ms/frame, software raster %.2f ms/frame, %d bytes allocated in %d frames"
                    .format(recordMs, rasterMs, bytes, FRAMES / 4),
            )
            assertTrue("${n}x$n allocated $bytes bytes in ${FRAMES / 4} frames", bytes < 4_096)
        }
        val (record3, raster3) = results.getValue(3)
        val (record7, raster7) = results.getValue(7)
        assertTrue("7x7 recording ($record7 ms) stays within 6x of 3x3 ($record3 ms)", record7 < record3 * 6 + 0.5)
        assertTrue("7x7 raster ($raster7 ms) stays within 4x of 3x3 ($raster3 ms)", raster7 < raster3 * 4 + 2)
    }

    private companion object {
        const val SIDE = 1080
        const val WARM_UP = 200
        const val FRAMES = 400
    }
}
