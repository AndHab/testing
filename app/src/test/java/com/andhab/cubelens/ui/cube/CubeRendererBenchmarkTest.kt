package com.andhab.cubelens.ui.cube

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A rough JVM benchmark of [CubeRenderer] on Robolectric's native (Skia) canvas.
 *
 * Absolute times are not device times: on a phone HWUI only records the draw calls on the UI thread
 * and rasterizes on the GPU, so the recording time below is the relevant per-frame CPU cost, and
 * software rasterization of a 1080 px frame is a pessimistic upper bound.
 *
 * [drawingAllocatesNothing] always runs: allocation counts are deterministic. The timing
 * comparison ([bigCubesDrawAtAComparableCost]: a 7×7 costs about the same order as a 3×3, since the
 * painter's algorithm draws a few box faces rather than hundreds of cubies) depends on machine load,
 * so it only runs on request: `CUBELENS_BENCHMARK=1 ./gradlew :app:testDebugUnitTest --tests '*CubeRendererBenchmarkTest*'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CubeRendererBenchmarkTest {

    @Test
    fun drawingAllocatesNothing() {
        // Layer turns of every kind, a whole-cube rotation (the focus frame rides along) and a grazing view.
        val cases = listOf(3 to "R", 3 to "3Rw", 5 to "2R", 7 to "3Uw'", 6 to "3F'")
        for ((n, move) in cases) {
            val bench = Bench(n, move, tilt = if (n == 6) 10f else 28f)
            // Warm up on the very frames measured below, so first-call linking of every code path
            // (class loading, Robolectric's shadow bootstrap) happens before the count starts.
            repeat(FRAMES / 4) { bench.raster(it) }
            // Same canvas every frame (Picture.beginRecording creates a new canvas per call).
            val bytes = allocatedBytes { repeat(FRAMES / 4) { bench.raster(it) } }
            println("CubeRenderer ${n}x$n $move: $bytes bytes allocated in ${FRAMES / 4} frames")
            assertTrue("${n}x$n $move allocated $bytes bytes in ${FRAMES / 4} frames", bytes < 4_096)
        }
    }

    @Test
    fun bigCubesDrawAtAComparableCost() {
        assumeTrue("Timing benchmark; set CUBELENS_BENCHMARK=1 to run it", System.getenv("CUBELENS_BENCHMARK") != null)
        val results = mutableMapOf<Int, Pair<Double, Double>>()
        for (n in SIZES) {
            val bench = Bench(n, if (n == 3) "R" else "3Uw'")
            repeat(WARM_UP) { bench.record(it) }
            var start = System.nanoTime()
            repeat(FRAMES) { bench.record(it) }
            val recordMs = (System.nanoTime() - start) / 1e6 / FRAMES

            repeat(WARM_UP / 4) { bench.raster(it) }
            start = System.nanoTime()
            repeat(FRAMES / 4) { bench.raster(it) }
            val rasterMs = (System.nanoTime() - start) / 1e6 / (FRAMES / 4)
            results[n] = recordMs to rasterMs
            println("CubeRenderer ${n}x$n: record %.3f ms/frame, software raster %.2f ms/frame".format(recordMs, rasterMs))
        }
        val (record3, raster3) = results.getValue(3)
        val (record7, raster7) = results.getValue(7)
        assertTrue("7x7 recording ($record7 ms) stays within 6x of 3x3 ($record3 ms)", record7 < record3 * 6 + 0.5)
        assertTrue("7x7 raster ($raster7 ms) stays within 4x of 3x3 ($raster3 ms)", raster7 < raster3 * 4 + 2)
    }

    /**
     * One [n]×[n] cube turning [move], with highlights and a focused face, drawn at a slowly
     * orbiting camera tilted by [tilt] degrees.
     */
    private class Bench(n: Int, move: String, tilt: Float = 28f) {
        private val frame = CubeFrame().apply {
            size = n
            colors = TestCubes.scrambled(n)
            yaw = -35f
            pitch = tilt
            this.move = LayerMove.parse(move)
            highlights = highlightTable(setOf(0, 5))
            pulse = 0.5f
            focusFace = Face.F
            focusGlow = 1f
            for (d in 0 until 6) faceDim[d] = if (d == Face.F.ordinal) 0f else 1f
        }
        private val renderer = CubeRenderer()
        private val picture = Picture()
        private val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)

        /** Records frame [i] into a [Picture], like HWUI records a display list. */
        fun record(i: Int) {
            frame.progress = (i % 60) / 60f
            frame.yaw = -35f + i * 0.5f
            val recording = picture.beginRecording(SIDE, SIDE)
            renderer.draw(recording, SIDE.toFloat(), SIDE.toFloat(), frame)
            picture.endRecording()
        }

        /** Rasterizes frame [i] in software into the same bitmap. */
        fun raster(i: Int) {
            frame.progress = (i % 60) / 60f
            frame.yaw = -35f + i * 0.5f
            renderer.draw(canvas, SIDE.toFloat(), SIDE.toFloat(), frame)
        }
    }

    private companion object {
        val SIZES = listOf(3, 5, 7)
        const val SIDE = 1080
        const val WARM_UP = 200
        const val FRAMES = 400
    }
}
