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
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.vision.*
import com.andhab.cubelens.ui.theme.CubeLensTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Screenshots of the scan flow over a fake camera feed built from the user's own cube photos
 * (src/test/resources/scan). PNGs land in app/build/outputs/roborazzi/scan_*.png.
 *
 * Live colors are not made up: they are read from the same frame the preview shows, through the
 * real sampler and live classifier. The frames of other sizes and of a pastel cube are mosaics of
 * real sticker tiles cut from the user's photos (recolored for the pastel cube), laid out N×N in the
 * same place as the 3×3 face.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScanScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** Faces of the user's real scrambled cube, as scanned in the reference hold. */
    private val realCube: List<CubeColor> = FaceletCube.parse(REAL_CUBE).toColors()

    private fun realFace(step: ScanStep): List<CubeColor> = realCube.subList(step.face.ordinal * 9, step.face.ordinal * 9 + 9)

    private fun captured(vararg steps: ScanStep): List<List<CubeColor>?> =
        ScanStep.entries.map { step -> if (step in steps) realFace(step) else null }

    @Test
    fun permissionRationale() = shot("scan_permission") {
        CameraGateContent(CameraGate.Rationale, onPrimary = {}, onManualEntry = {}, onBack = {})
    }

    @Test
    fun permissionDenied() = shot("scan_permission_denied") {
        CameraGateContent(CameraGate.Denied, onPrimary = {}, onManualEntry = {}, onBack = {})
    }

    @Test
    fun permissionForABigCube() = shot("scan_permission_5x5") {
        CameraGateContent(CameraGate.Rationale, onPrimary = {}, onManualEntry = {}, onBack = {}, size = 5)
    }

    @Test
    fun firstFaceLive() {
        val frame = Frame.load("green")
        assertEquals(CubeColor.GREEN, frame.liveColors[4])
        shotScan("scan_step1_live", frame, ScanUiState(currentStep = ScanStep.Front, liveColors = frame.liveColors, torchAvailable = true))
    }

    @Test
    fun fourthFaceAlmostCaptured() {
        val frame = Frame.load("orange")
        assertEquals(CubeColor.ORANGE, frame.liveColors[4])
        shotScan(
            "scan_step4_progress",
            frame,
            ScanUiState(
                currentStep = ScanStep.Left,
                captures = captured(ScanStep.Front, ScanStep.Right, ScanStep.Back),
                liveColors = frame.liveColors,
                captureProgress = 0.5f,
                captureCount = 3,
                lastCaptured = ScanStep.Back,
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
                currentStep = ScanStep.Right,
                captures = captured(ScanStep.Front),
                liveColors = frame.liveColors,
                hint = ScanHint.WrongFace(seen = CubeColor.BLUE, expected = CubeColor.RED),
                captureCount = 1,
                lastCaptured = ScanStep.Front,
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
                currentStep = ScanStep.Top,
                captures = captured(ScanStep.Front, ScanStep.Right, ScanStep.Back, ScanStep.Left),
                liveColors = frame.liveColors,
                hint = ScanHint.AlreadyScanned(CubeColor.GREEN, ScanStep.Front),
                autoCapture = false,
                captureCount = 4,
                lastCaptured = ScanStep.Left,
            ),
        )
    }

    @Test
    fun redoAFace() {
        // Tapped the green thumbnail after four faces: an absolute instruction and "Redo green".
        val frame = Frame.load("green")
        shotScan(
            "scan_redo",
            frame,
            ScanUiState(
                currentStep = ScanStep.Front,
                captures = captured(ScanStep.Front, ScanStep.Right, ScanStep.Back, ScanStep.Left),
                liveColors = frame.liveColors,
                captureProgress = 0.3f,
                captureCount = 4,
                lastCaptured = ScanStep.Left,
                torchAvailable = true,
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
                currentStep = ScanStep.Bottom,
                captures = captured(*ScanStep.entries.toTypedArray()),
                liveColors = frame.liveColors,
                isComplete = true,
                captureCount = 6,
                lastCaptured = ScanStep.Bottom,
            ),
        )
    }

    @Test
    fun captureFeedback() {
        // The moment after an auto-capture: white flash fading, the face flying into its thumbnail.
        val frame = Frame.load("green")
        var state by mutableStateOf(ScanUiState(currentStep = ScanStep.Front, liveColors = frame.liveColors, captureProgress = 0.97f))
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
            currentStep = ScanStep.Right,
            captures = ScanStep.entries.map { if (it == ScanStep.Front) frame.liveColors else null },
            captureProgress = 0f,
            captureCount = 1,
            lastCaptured = ScanStep.Front,
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
                currentStep = ScanStep.Top,
                captures = captured(ScanStep.Front, ScanStep.Right, ScanStep.Back, ScanStep.Left),
                liveColors = frame.liveColors,
                hint = ScanHint.WrongFace(seen = CubeColor.ORANGE, expected = CubeColor.WHITE),
                captureCount = 4,
                lastCaptured = ScanStep.Left,
            ),
        )
    }

    // Other sizes.

    @Test
    fun twoByTwoFirstFace() {
        // No fixed centers: "pick any side", a blank cube with the front framed, numbered slots.
        val frame = Frame.load("2x2", n = 2, grid = MOSAIC)
        assertEquals(listOf(CubeColor.GREEN, CubeColor.ORANGE, CubeColor.WHITE, CubeColor.RED), frame.liveColors)
        shotScan("scan_2x2_step1", frame, ScanUiState(size = 2, liveColors = frame.liveColors, captureProgress = 0.2f, torchAvailable = true))
    }

    @Test
    fun fourByFourTipTheTopTowardYou() {
        // Four sides done; the top is next, read with the first face at the bottom.
        val frame = Frame.load("4x4", n = 4, grid = MOSAIC)
        val cube = scrambled(4)
        shotScan(
            "scan_4x4_step5",
            frame,
            ScanUiState(
                size = 4,
                currentStep = ScanStep.Top,
                captures = ScanStep.entries.map { if (it.ordinal < 4) cube.face(it.face) else null },
                liveColors = frame.liveColors,
                captureProgress = 0.4f,
                captureCount = 4,
                lastCaptured = ScanStep.Left,
            ),
        )
    }

    @Test
    fun fourByFourSameFaceAgain() {
        val frame = Frame.load("4x4", n = 4, grid = MOSAIC)
        val cube = scrambled(4)
        shotScan(
            "scan_4x4_same_face",
            frame,
            ScanUiState(
                size = 4,
                currentStep = ScanStep.Back,
                captures = ScanStep.entries.map { if (it.ordinal < 2) cube.face(it.face) else null },
                liveColors = frame.liveColors,
                hint = ScanHint.SameAsCaptured(ScanStep.Front),
                captureCount = 2,
                lastCaptured = ScanStep.Right,
            ),
        )
    }

    @Test
    fun fourByFourLookAlikes() {
        // Two faces captured by hand look the same: both thumbnails ringed, one line to fix it.
        val frame = Frame.load("4x4", n = 4, grid = MOSAIC)
        val cube = scrambled(4)
        val front = cube.face(ScanStep.Front.face)
        shotScan(
            "scan_4x4_look_alikes",
            frame,
            ScanUiState(
                size = 4,
                currentStep = ScanStep.Back,
                captures = ScanStep.entries.map { if (it.ordinal < 2) front else null },
                liveColors = frame.liveColors,
                lookAlikes = setOf(ScanStep.Front, ScanStep.Right),
                captureCount = 2,
                lastCaptured = ScanStep.Right,
                captureProgress = 0.25f,
            ),
        )
    }

    @Test
    fun fiveByFiveLive() {
        val frame = Frame.load("5x5", n = 5, grid = MOSAIC)
        assertEquals(CubeColor.GREEN, frame.liveColors[12])
        shotScan("scan_5x5_live", frame, ScanUiState(size = 5, liveColors = frame.liveColors, captureProgress = 0.6f, torchAvailable = true))
    }

    @Test
    fun sevenBySevenLive() {
        // 49 small swatches, one per sticker; two faces in.
        val frame = Frame.load("7x7", n = 7, grid = MOSAIC)
        assertEquals(CubeColor.GREEN, frame.liveColors[24])
        val cube = scrambled(7)
        shotScan(
            "scan_7x7_live",
            frame,
            ScanUiState(
                size = 7,
                currentStep = ScanStep.Back,
                captures = ScanStep.entries.map { if (it.ordinal < 2) cube.face(it.face) else null },
                liveColors = frame.liveColors,
                hint = ScanHint.WrongFace(seen = CubeColor.GREEN, expected = CubeColor.BLUE),
                captureCount = 2,
                lastCaptured = ScanStep.Right,
            ),
        )
    }

    @Test
    fun sevenBySevenComplete() {
        val frame = Frame.load("7x7", n = 7, grid = MOSAIC)
        val cube = scrambled(7)
        shotScan(
            "scan_7x7_complete",
            frame,
            ScanUiState(
                size = 7,
                currentStep = ScanStep.Bottom,
                captures = ScanStep.entries.map { cube.face(it.face) },
                liveColors = frame.liveColors,
                isComplete = true,
                captureCount = 6,
                lastCaptured = ScanStep.Bottom,
            ),
        )
    }

    // A pastel knock-off.

    @Test
    fun pastelCubeInItsOwnColors() {
        // A real scan session: three faces of the user's cube captured in pastel colors teach the
        // classifier this cube's colors; the live face is read and drawn with what it learned.
        val classifier = AdaptiveLiveClassifier()
        val controller = ScanController(size = 3, classifier = classifier)
        controller.setAutoCapture(false)
        val noise = Random(5)
        var now = 0L
        for (step in listOf(ScanStep.Front, ScanStep.Right, ScanStep.Back)) {
            repeat(6) {
                controller.onFrame(realFace(step).map { pastelSample(it, noise) }, now)
                now += 33
            }
            controller.capture()
        }
        val learned = controller.state.value
        assertEquals(listOf(CubeColor.GREEN, CubeColor.RED, CubeColor.BLUE), learned.captures.take(3).map { it!![4] })
        assertTrue("a pastel cube gets its own colors", learned.stickerColors.isNotEmpty())

        val frame = Frame.load("pastel", classify = classifier::classify, grid = MOSAIC)
        assertEquals("BBGBOYYGB".map(CubeColor::fromLetter), frame.liveColors)
        shotScan("scan_pastel_live", frame, learned.copy(liveColors = frame.liveColors, autoCapture = true, captureProgress = 0.45f))
    }

    private fun scrambled(n: Int): NxNCube = NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(n * 11)))

    /** A pastel knock-off's [color] as a camera captures it, with a little sensor noise. */
    private fun pastelSample(color: CubeColor, noise: Random): StickerSample {
        val rgb = PastelDesign.getValue(color)
        fun channel(shift: Int) =
            (ColorMath.linearToSrgb(ColorMath.srgbToLinear((rgb shr shift) and 0xFF) * PASTEL_EXPOSURE) + noise.nextInt(-3, 4)).coerceIn(0, 255)
        return StickerSample.of(channel(16), channel(8), channel(0))
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
            fun load(
                name: String,
                n: Int = 3,
                classify: (StickerSample) -> CubeColor = LiveClassifier::classify,
                grid: GridRegion = STICKERS,
            ): Frame {
                val stream = checkNotNull(Frame::class.java.getResourceAsStream("/scan/frame_$name.jpg")) { "Missing frame $name" }
                val bitmap = stream.use { BitmapFactory.decodeStream(it) }
                val samples = GridSampler.sample(BitmapSource(bitmap), grid, 0, n)
                return Frame(bitmap.asImageBitmap(), samples.map(classify))
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

        /**
         * The sticker grid of the mosaic frames (other sizes, pastel): the whole face square. They
         * are frame_green.jpg with the face replaced by an N×N mosaic of sticker tiles cut from the
         * user's cube photos (core/src/test/resources/photos), each tile recolored for the pastel
         * cube keeping its light relative to the sticker.
         */
        val MOSAIC = GridRegion(left = FACE_LEFT.toInt(), top = FACE_TOP.toInt(), size = FACE_SIZE.toInt())

        /** Design colors of the pastel knock-off in frame_pastel.jpg: warm white, lemon, mint, baby blue, pink, peach. */
        val PastelDesign: Map<CubeColor, Int> = mapOf(
            CubeColor.WHITE to 0xF7F5EE, CubeColor.YELLOW to 0xF3E58A, CubeColor.GREEN to 0x9EDDB0,
            CubeColor.BLUE to 0x93BFEA, CubeColor.RED to 0xF2A0B4, CubeColor.ORANGE to 0xF7BE92,
        )

        /** How a camera exposes them (linear light), as in frame_pastel.jpg. */
        const val PASTEL_EXPOSURE = 0.66
    }
}
