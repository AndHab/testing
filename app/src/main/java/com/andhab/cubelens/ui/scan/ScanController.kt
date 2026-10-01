package com.andhab.cubelens.ui.scan

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.vision.LiveClassifier
import com.andhab.cubelens.core.vision.StickerSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Timing of [ScanController]'s live feedback and auto-capture. */
data class ScanTuning(
    /** How long the nine live colors must stay unchanged before auto-capture fires. */
    val stableMillis: Long = 700,
    /** How long the center must stay unchanged before a wrong or repeated face is pointed out. */
    val hintDelayMillis: Long = 450,
    /** Live colors are the per-sticker majority over this many recent frames (hides flicker). */
    val smoothingFrames: Int = 5,
    /** A capture averages up to this many of the latest steady frames. */
    val captureFrames: Int = 5,
    /** A longer pause between frames (camera paused, app in background) restarts the steadiness clock. */
    val maxFrameGapMillis: Long = 500,
) {
    init {
        require(stableMillis > 0 && hintDelayMillis >= 0 && maxFrameGapMillis > 0)
        require(smoothingFrames >= 1 && captureFrames >= 1)
    }
}

/** A gentle heads-up about the face currently in view. */
@Immutable
sealed interface ScanHint {
    /** The face in view is not the one this step asks for. */
    data class WrongFace(val seen: CubeColor, val expected: CubeColor) : ScanHint

    /** The face in view was already captured, at [step]; it won't be auto-captured again. */
    data class AlreadyScanned(val color: CubeColor, val step: ScanStep) : ScanHint
}

/**
 * Everything the scan screen shows, as one immutable snapshot.
 *
 * @property currentStep the face being scanned (or retaken) now.
 * @property captures the classified colors of each captured face, indexed by [ScanStep.ordinal];
 *   null where nothing was captured yet. Nine colors, row-major as seen on screen.
 * @property liveColors the nine colors currently inside the guide, or null before the first frame.
 * @property captureProgress 0..1 progress toward auto-capture: how long the current, correct face
 *   has been held steady. Zero when auto-capture is off or would not fire for this face.
 * @property autoCapture whether a steady, correct face is captured automatically.
 * @property hint a heads-up about the face in view, if any.
 * @property isComplete all six faces are captured.
 * @property captureCount number of captures so far; changes exactly when a capture happens.
 * @property lastCaptured the step of the latest capture.
 * @property torchAvailable whether the camera has a flashlight.
 * @property torchOn whether the flashlight is on.
 */
@Immutable
data class ScanUiState(
    val currentStep: ScanStep = ScanStep.entries.first(),
    val captures: List<List<CubeColor>?> = List(ScanStep.entries.size) { null },
    val liveColors: List<CubeColor>? = null,
    val captureProgress: Float = 0f,
    val autoCapture: Boolean = true,
    val hint: ScanHint? = null,
    val isComplete: Boolean = false,
    val captureCount: Int = 0,
    val lastCaptured: ScanStep? = null,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
) {
    /** Whether [step] has been captured. */
    fun isCaptured(step: ScanStep): Boolean = captures[step.ordinal] != null

    /** How many faces are captured. */
    val capturedCount: Int get() = captures.count { it != null }

    /** Whether the live center is the color this step asks for. */
    val centerMatches: Boolean get() = liveColors?.get(CENTER) == currentStep.color

    /** Whether the current step scans a face again that was captured before. */
    val isRetake: Boolean get() = isCaptured(currentStep)

    /**
     * Whether the cube is presumably still held as the previous step left it, so the step's relative
     * [ScanStep.cue] ("Turn it left again") applies: the first step before any capture, or a step
     * right after its predecessor was captured. False after a retake or a jump to another face.
     */
    val followsPreviousStep: Boolean
        get() = !isRetake && when (val previous = currentStep.previous) {
            null -> captureCount == 0
            else -> lastCaptured == previous
        }
}

/**
 * The scanning flow's logic, free of Android camera classes so it can be driven by fake frames.
 *
 * Feed it the nine samples of every analyzed frame with [onFrame] (any thread). It classifies them
 * live, smooths out flicker, tracks how long the face has been held steady and captures
 * automatically when the face in view is steady, is the one this step asks for and hasn't been
 * captured already. Manual capture ([capture]) is always allowed. After a capture it moves on to
 * the next face still missing; [selectStep] goes back to retake one. Observe [state].
 *
 * All methods are thread-safe. Use [saver] to keep a scan in progress across activity recreation
 * and process death.
 */
class ScanController(private val tuning: ScanTuning = ScanTuning()) {

    private val lock = Any()
    private val mutableState = MutableStateFlow(ScanUiState())

    /** The current UI state; updated on every frame and every action. */
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private val recentColors = ArrayDeque<List<CubeColor>>()
    private val steadySamples = ArrayDeque<List<StickerSample>>()
    private var smoothed: List<CubeColor>? = null
    private var latestSamples: List<StickerSample>? = null

    /** Whether the latest frame agrees with the smoothed colors (false right after a move). */
    private var latestIsSteady = false

    private var lastFrameMillis = 0L
    private var steadySince = 0L
    private var centerSince = 0L

    /** The color just captured: no hint about it while it is still in view. */
    private var quietColor: CubeColor? = null

    private val capturedSamples = arrayOfNulls<List<StickerSample>>(ScanStep.entries.size)

    /**
     * Processes one analyzed frame.
     *
     * @param samples the nine stickers inside the guide, row-major as seen on screen.
     * @param timestampMillis frame time, from a monotonic clock.
     */
    fun onFrame(samples: List<StickerSample>, timestampMillis: Long) {
        require(samples.size == 9) { "A face has 9 stickers, got ${samples.size}" }
        synchronized(lock) {
            if (mutableState.value.isComplete) return
            val gap = timestampMillis - lastFrameMillis
            if (latestSamples != null && (gap < 0 || gap > tuning.maxFrameGapMillis)) resetTracking()
            val first = latestSamples == null
            lastFrameMillis = timestampMillis
            latestSamples = samples

            val colors = samples.map(LiveClassifier::classify)
            recentColors.addLast(colors)
            while (recentColors.size > tuning.smoothingFrames) recentColors.removeFirst()
            val majority = majorityColors()
            if (first || majority != smoothed) {
                steadySince = timestampMillis
                steadySamples.clear()
            }
            if (first || majority[CENTER] != smoothed?.get(CENTER)) centerSince = timestampMillis
            smoothed = majority
            latestIsSteady = colors == majority
            if (latestIsSteady) {
                steadySamples.addLast(samples)
                while (steadySamples.size > tuning.captureFrames) steadySamples.removeFirst()
            }
            val center = majority[CENTER]
            if (quietColor != null && center != quietColor && heldFor(centerSince) >= tuning.hintDelayMillis) {
                quietColor = null
            }

            if (autoCaptureDue()) captureLocked() else publish()
        }
    }

    /**
     * Captures the face in view for the current step (the shutter). Allowed whatever the face, so
     * the user always stays in control; does nothing before the first frame or once complete.
     *
     * @return whether a face was captured.
     */
    fun capture(): Boolean = synchronized(lock) {
        if (mutableState.value.isComplete || latestSamples == null) return false
        captureLocked()
        true
    }

    /** Makes [step] the current one, e.g. to retake a captured face. Ignored once complete. */
    fun selectStep(step: ScanStep) {
        synchronized(lock) {
            val current = mutableState.value
            if (current.isComplete || current.currentStep == step) return
            mutableState.value = current.copy(currentStep = step)
            quietColor = null
            restartSteadiness()
            publish()
        }
    }

    /** Turns auto-capture on or off. */
    fun setAutoCapture(enabled: Boolean) {
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(autoCapture = enabled)
            publish()
        }
    }

    /** Reports whether the camera has a flashlight; without one the torch is off. */
    fun setTorchAvailable(available: Boolean) {
        synchronized(lock) {
            val current = mutableState.value
            mutableState.value = current.copy(torchAvailable = available, torchOn = available && current.torchOn)
        }
    }

    /** Switches the flashlight on or off (if available). */
    fun setTorch(on: Boolean) {
        synchronized(lock) {
            val current = mutableState.value
            mutableState.value = current.copy(torchOn = on && current.torchAvailable)
        }
    }

    /**
     * The six captured scans in step order, each nine samples row-major as seen on screen, ready for
     * [com.andhab.cubelens.core.vision.ScanResolver.resolve].
     *
     * @throws IllegalStateException if not all faces are captured yet.
     */
    fun scans(): List<List<StickerSample>> = synchronized(lock) {
        capturedSamples.map { checkNotNull(it) { "Not all faces are captured yet" } }
    }

    /**
     * The progress worth keeping (captured samples, current step, auto-capture choice) as a compact
     * array; live tracking and the flashlight start afresh. See [restore].
     */
    private fun save(): IntArray = synchronized(lock) {
        val current = mutableState.value
        val saved = IntArray(SAVED_HEADER + ScanStep.entries.size * SAVED_PER_FACE) { NONE }
        saved[0] = SAVED_VERSION
        saved[1] = current.currentStep.ordinal
        saved[2] = if (current.autoCapture) 1 else 0
        saved[3] = current.captureCount
        saved[4] = current.lastCaptured?.ordinal ?: NONE
        capturedSamples.forEachIndexed { step, samples ->
            samples?.forEachIndexed { sticker, sample ->
                val at = SAVED_HEADER + step * SAVED_PER_FACE + sticker * 3
                saved[at] = sample.r
                saved[at + 1] = sample.g
                saved[at + 2] = sample.b
            }
        }
        saved
    }

    /** Puts back what [save] kept; ignores anything it doesn't recognize. */
    private fun restore(saved: IntArray) {
        if (saved.size != SAVED_HEADER + ScanStep.entries.size * SAVED_PER_FACE || saved[0] != SAVED_VERSION) return
        synchronized(lock) {
            val steps = ScanStep.entries
            for (step in steps.indices) {
                val at = SAVED_HEADER + step * SAVED_PER_FACE
                capturedSamples[step] = if (saved[at] == NONE) {
                    null
                } else {
                    List(9) { sticker ->
                        val rgb = at + sticker * 3
                        StickerSample.of(saved[rgb], saved[rgb + 1], saved[rgb + 2])
                    }
                }
            }
            val captures = capturedSamples.map { samples -> samples?.map(LiveClassifier::classify) }
            mutableState.value = ScanUiState(
                currentStep = steps.getOrElse(saved[1]) { steps.first() },
                captures = captures,
                autoCapture = saved[2] == 1,
                isComplete = captures.all { it != null },
                captureCount = saved[3].coerceAtLeast(0),
                lastCaptured = steps.getOrNull(saved[4]),
            )
        }
    }

    private fun heldFor(since: Long): Long = lastFrameMillis - since

    /** Per sticker, the color seen most often in the recent frames; ties go to the newest frame. */
    private fun majorityColors(): List<CubeColor> = List(9) { sticker ->
        val counts = IntArray(CubeColor.entries.size)
        for (frame in recentColors) counts[frame[sticker].ordinal]++
        val best = counts.max()
        recentColors.asReversed().first { counts[it[sticker].ordinal] == best }[sticker]
    }

    /** The step, other than the current one, that already captured a face with [center], if any. */
    private fun capturedElsewhere(center: CubeColor): ScanStep? {
        val current = mutableState.value
        return ScanStep.entries.firstOrNull { step ->
            step != current.currentStep && current.captures[step.ordinal]?.get(CENTER) == center
        }
    }

    /** Whether the face in view could be auto-captured, steadiness aside. */
    private fun autoCaptureEligible(): Boolean {
        val current = mutableState.value
        val center = smoothed?.get(CENTER) ?: return false
        return current.autoCapture && !current.isComplete && center == current.currentStep.color &&
            capturedElsewhere(center) == null && steadySamples.isNotEmpty()
    }

    private fun autoCaptureDue(): Boolean =
        autoCaptureEligible() && heldFor(steadySince) >= tuning.stableMillis

    private fun captureLocked() {
        // The steady frames, unless the face just changed and the smoothing hasn't caught up yet:
        // then the shutter means what is in view right now.
        val samples = (if (latestIsSteady) averageOf(steadySamples) else null) ?: checkNotNull(latestSamples)
        val colors = samples.map(LiveClassifier::classify)
        val current = mutableState.value
        val step = current.currentStep
        capturedSamples[step.ordinal] = samples
        val captures = current.captures.toMutableList().also { it[step.ordinal] = colors }
        val next = nextMissing(after = step, captures = captures)
        mutableState.value = current.copy(
            currentStep = next ?: step,
            captures = captures,
            isComplete = next == null,
            captureCount = current.captureCount + 1,
            lastCaptured = step,
        )
        quietColor = colors[CENTER]
        restartSteadiness()
        publish()
    }

    /** The first missing step after [after], wrapping around; null when all are captured. */
    private fun nextMissing(after: ScanStep, captures: List<List<CubeColor>?>): ScanStep? {
        val steps = ScanStep.entries
        return (1..steps.size)
            .map { steps[(after.ordinal + it) % steps.size] }
            .firstOrNull { captures[it.ordinal] == null }
    }

    /** Requires the face to be held steady afresh (after a capture or a step change). */
    private fun restartSteadiness() {
        steadySince = lastFrameMillis
        steadySamples.clear()
    }

    /** Forgets the frame history, e.g. after the camera paused. */
    private fun resetTracking() {
        recentColors.clear()
        steadySamples.clear()
        smoothed = null
        latestSamples = null
        latestIsSteady = false
    }

    /** Recomputes the live parts of the state from the tracking fields. */
    private fun publish() {
        val current = mutableState.value
        val live = smoothed
        val center = live?.get(CENTER)
        val hint = when {
            center == null || current.isComplete -> null
            heldFor(centerSince) < tuning.hintDelayMillis || center == quietColor -> null
            else -> capturedElsewhere(center)?.let { ScanHint.AlreadyScanned(center, it) }
                ?: if (center != current.currentStep.color) ScanHint.WrongFace(center, current.currentStep.color) else null
        }
        val progress = if (autoCaptureEligible()) {
            (heldFor(steadySince).toFloat() / tuning.stableMillis).coerceIn(0f, 1f)
        } else {
            0f
        }
        mutableState.value = current.copy(liveColors = live, hint = hint, captureProgress = progress)
    }

    companion object {
        /**
         * Saves a scan in progress with `rememberSaveable`, so it survives activity recreation
         * (rotation, theme or font changes, resizing) and process death.
         */
        fun saver(tuning: ScanTuning = ScanTuning()): Saver<ScanController, IntArray> = Saver(
            save = { it.save() },
            restore = { saved -> ScanController(tuning).apply { restore(saved) } },
        )

        private const val SAVED_VERSION = 1
        private const val SAVED_HEADER = 5
        private const val SAVED_PER_FACE = 9 * 3
        private const val NONE = -1

        /** Each channel averaged over [frames] per sticker; null if there are none. */
        private fun averageOf(frames: Collection<List<StickerSample>>): List<StickerSample>? {
            if (frames.isEmpty()) return null
            return List(9) { sticker ->
                var r = 0
                var g = 0
                var b = 0
                for (frame in frames) {
                    r += frame[sticker].r
                    g += frame[sticker].g
                    b += frame[sticker].b
                }
                val n = frames.size.toFloat()
                StickerSample.of((r / n).roundToInt(), (g / n).roundToInt(), (b / n).roundToInt())
            }
        }
    }
}

/** Index of the center sticker in a row-major face. */
private const val CENTER = 4
