package com.andhab.cubelens.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.andhab.cubelens.core.vision.GridSampler
import com.andhab.cubelens.core.vision.PixelSource
import com.andhab.cubelens.core.vision.StickerSample
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the nine stickers inside the scan guide from a camera frame.
 */
object FrameSampler {

    /**
     * Samples the face inside [guide] from a frame [source] whose visible part is [crop] and which
     * must be turned [rotationDegrees] clockwise to look upright.
     *
     * @return nine samples, row-major as the user sees the face on screen.
     */
    fun sample(
        source: PixelSource,
        crop: BufferRect,
        rotationDegrees: Int,
        guide: GuideGeometry,
    ): List<StickerSample> {
        val region = GuideMapper.toBufferRegion(guide, crop, rotationDegrees)
        return GridSampler.sample(source, region, rotationDegrees)
    }
}

/**
 * CameraX analyzer that samples the face inside the scan guide from every frame and reports the
 * nine samples with the frame's timestamp.
 *
 * The guide's position is set from the UI thread with [updateGuide]; until it is known, frames are
 * skipped. [onSamples] is called on the analysis executor. Each frame is closed after sampling, so
 * with `STRATEGY_KEEP_ONLY_LATEST` a slow device simply analyzes fewer frames.
 *
 * @param onSamples receives nine samples (row-major as seen on screen) and the frame time in
 *   milliseconds (monotonic, camera time base).
 */
class CubeFrameAnalyzer(
    private val onSamples: (samples: List<StickerSample>, timestampMillis: Long) -> Unit,
) : ImageAnalysis.Analyzer {

    private val guide = AtomicReference<GuideGeometry?>(null)

    /** Where the guide currently is on screen, or null to pause analysis. Thread-safe. */
    fun updateGuide(geometry: GuideGeometry?) {
        guide.set(geometry)
    }

    override fun analyze(image: ImageProxy) {
        image.use { frame ->
            val geometry = guide.get() ?: return
            val samples = try {
                FrameSampler.sample(
                    source = frame.toRgbaPixelSource(),
                    crop = frame.cropRect.toBufferRect(),
                    rotationDegrees = frame.imageInfo.rotationDegrees,
                    guide = geometry,
                )
            } catch (e: IllegalArgumentException) {
                // A malformed frame (unexpected format or crop): skip it, the next one will do.
                Log.w(TAG, "Skipping a camera frame", e)
                return
            }
            onSamples(samples, frame.imageInfo.timestamp / NANOS_PER_MILLI)
        }
    }

    private companion object {
        const val TAG = "CubeFrameAnalyzer"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
