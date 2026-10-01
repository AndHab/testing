package com.andhab.cubelens.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.camera.GuideGeometry
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.vision.GridRegion
import com.andhab.cubelens.core.vision.GridSampler
import com.andhab.cubelens.core.vision.LiveClassifier
import com.andhab.cubelens.core.vision.PixelSource
import com.andhab.cubelens.ui.theme.CubeLensTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Screenshots of the scan flow over a fake camera feed built from the user's own cube photos
 * (src/test/resources/scan). PNGs land in app/build/outputs/roborazzi/scan_*.png.
 *
 * Live colors are not made up: they are read from the same frame the preview shows, through the
 * real sampler and live classifier.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScanScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** Faces of the user's real scrambled cube, as scanned in the reference hold. */
    private val realCube: List<CubeColor> = FaceletCube.parse(REAL_CUBE).toColors()

    private fun captured(vararg steps: ScanStep): List<List<CubeColor>?> =
        ScanStep.entries.map { step -> if (step in steps) realCube.subList(step.face.ordinal * 9, step.face.ordinal * 9 + 9) else null }

    @Test
    fun permissionRationale() = shot("scan_permission") {
        CameraGateContent(CameraGate.Rationale, onPrimary = {}, onManualEntry = {}, onBack = {})
    }

    @Test
    fun permissionDenied() = shot("scan_permission_denied") {
        CameraGateContent(CameraGate.Denied, onPrimary = {}, onManualEntry = {}, onBack = {})
    }

    @Test
    fun firstFaceLive() {
        val frame = Frame.load("green")
        assertEquals(CubeColor.GREEN, frame.liveColors[4])
        shotScan("scan_step1_live", frame, ScanUiState(currentStep = ScanStep.Green, liveColors = frame.liveColors, torchAvailable = true))
    }

    @Test
    fun fourthFaceAlmostCaptured() {
        val frame = Frame.load("orange")
        assertEquals(CubeColor.ORANGE, frame.liveColors[4])
        shotScan(
            "scan_step4_progress",
            frame,
            ScanUiState(
                currentStep = ScanStep.Orange,
                captures = captured(ScanStep.Green, ScanStep.Red, ScanStep.Blue),
                liveColors = frame.liveColors,
                captureProgress = 0.5f,
                captureCount = 3,
                lastCaptured = ScanStep.Blue,
                torchAvailable = true,
                torchOn = true,
            ),
        )
    }

    @Test
    fun wrongFace() {
        val frame = Frame.load("blue")
        assertEquals(CubeColor.BLUE, frame.liveColors[4])
        shotScan(
            "scan_mismatch",
            frame,
            ScanUiState(
                currentStep = ScanStep.Red,
                captures = captured(ScanStep.Green),
                liveColors = frame.liveColors,
                hint = ScanHint.WrongFace(seen = CubeColor.BLUE, expected = CubeColor.RED),
                captureCount = 1,
                lastCaptured = ScanStep.Green,
            ),
        )
    }

    @Test
    fun alreadyScanned() {
        val frame = Frame.load("green")
        shotScan(
            "scan_already_scanned",
            frame,
            ScanUiState(
                currentStep = ScanStep.White,
                captures = captured(ScanStep.Green, ScanStep.Red, ScanStep.Blue, ScanStep.Orange),
                liveColors = frame.liveColors,
                hint = ScanHint.AlreadyScanned(CubeColor.GREEN, ScanStep.Green),
                autoCapture = false,
                captureCount = 4,
                lastCaptured = ScanStep.Orange,
            ),
        )
    }

    @Test
    fun complete() {
        val frame = Frame.load("yellow")
        shotScan(
            "scan_complete",
            frame,
            ScanUiState(
                currentStep = ScanStep.Yellow,
                captures = captured(*ScanStep.entries.toTypedArray()),
                liveColors = frame.liveColors,
                isComplete = true,
                captureCount = 6,
                lastCaptured = ScanStep.Yellow,
            ),
        )
    }

    @Test
    fun captureFeedback() {
        // The moment after an auto-capture: white flash fading, the face flying into its thumbnail.
        val frame = Frame.load("green")
        var state by mutableStateOf(ScanUiState(currentStep = ScanStep.Green, liveColors = frame.liveColors, captureProgress = 0.97f))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CubeLensTheme {
                var guide by remember { mutableStateOf<GuideGeometry?>(null) }
                ScanContent(
                    state = state,
                    preview = { FakeCameraPreview(frame, guide) },
                    onBack = {},
                    onCapture = {},
                    onAutoCaptureChange = {},
                    onTorchChange = {},
                    onSelectStep = {},
                    onManualEntry = {},
                    onGuideChange = { guide = it },
                )
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        state = state.copy(
            currentStep = ScanStep.Red,
            captures = ScanStep.entries.map { if (it == ScanStep.Green) frame.liveColors else null },
            captureProgress = 0f,
            captureCount = 1,
            lastCaptured = ScanStep.Green,
        )
        compose.mainClock.advanceTimeBy(220)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/scan_capture_feedback.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun smallPhone() {
        val frame = Frame.load("orange")
        shotScan(
            "scan_small_phone",
            frame,
            ScanUiState(
                currentStep = ScanStep.White,
                captures = captured(ScanStep.Green, ScanStep.Red, ScanStep.Blue, ScanStep.Orange),
                liveColors = frame.liveColors,
                hint = ScanHint.WrongFace(seen = CubeColor.ORANGE, expected = CubeColor.WHITE),
                captureCount = 4,
                lastCaptured = ScanStep.Orange,
            ),
        )
    }

    private fun shotScan(name: String, frame: Frame, state: ScanUiState) = shot(name) {
        var guide by remember { mutableStateOf<GuideGeometry?>(null) }
        ScanContent(
            state = state,
            preview = { FakeCameraPreview(frame, guide) },
            onBack = {},
            onCapture = {},
            onAutoCaptureChange = {},
            onTorchChange = {},
            onSelectStep = {},
            onManualEntry = {},
            onGuideChange = { guide = it },
        )
    }

    /**
     * Renders [content] full screen. Infinite animations (aurora drift, breathing thumbnail, idle
     * cube) never let the UI go idle, so the clock is driven by hand.
     */
    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { CubeLensTheme { content() } }
        // Layout reports the guide's position; the next frames draw the scrim and the preview there.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** A fake camera frame: the cube's face fills the square [FACE] of [image]. */
    private class Frame(val image: ImageBitmap, val liveColors: List<CubeColor>) {
        companion object {
            fun load(name: String): Frame {
                val stream = checkNotNull(Frame::class.java.getResourceAsStream("/scan/frame_$name.jpg")) { "Missing frame $name" }
                val bitmap = stream.use { BitmapFactory.decodeStream(it) }
                val samples = GridSampler.sample(BitmapSource(bitmap), STICKERS)
                return Frame(bitmap.asImageBitmap(), samples.map(LiveClassifier::classify))
            }
        }
    }

    private class BitmapSource(private val bitmap: Bitmap) : PixelSource {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override fun argb(x: Int, y: Int): Int = bitmap.getPixel(x, y)
    }

    /** Draws [frame] scaled so the cube's face lands exactly in the guide, as if aimed well. */
    @Composable
    private fun FakeCameraPreview(frame: Frame, guide: GuideGeometry?) {
        Canvas(Modifier.fillMaxSize()) {
            // Beyond the frame's edges (very short screens): the frame's dark surroundings.
            drawRect(FrameSurroundings)
            if (guide == null) return@Canvas
            val scale = guide.size / FACE_SIZE
            val left = guide.left - FACE_LEFT * scale
            val top = guide.top - FACE_TOP * scale
            drawImage(
                image = frame.image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize((frame.image.width * scale).roundToInt(), (frame.image.height * scale).roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
    }

    private companion object {
        val FrameSurroundings = Color(0xFF1E1B1D)

        /** The user's real scrambled cube. */
        const val REAL_CUBE = "DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR"

        // Where the face sits in the frames (see the frame generator): a 500px square at (150, 520).
        const val FACE_LEFT = 150f
        const val FACE_TOP = 520f
        const val FACE_SIZE = 500f

        /** The sticker grid inside that square (the photos' grid is at 14..467 of 480 pixels). */
        val STICKERS = GridRegion(left = 165, top = 535, size = 472)
    }
}
