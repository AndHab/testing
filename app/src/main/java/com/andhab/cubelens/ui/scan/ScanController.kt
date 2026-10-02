package com.andhab.cubelens.ui.scan

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.vision.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Timing and tolerances of [ScanController]'s live feedback and auto-capture. */
data class ScanTuning(
    /** How long the live colors must stay unchanged before auto-capture fires. */
    val stableMillis: Long = 700,
    /** How long the face in view must stay the same before a heads-up about it is shown. */
    val hintDelayMillis: Long = 450,
    /** Live colors are the per-sticker majority over this many recent frames (hides flicker). */
    val smoothingFrames: Int = 5,
    /** A capture averages up to this many of the latest steady frames. */
    val captureFrames: Int = 5,
    /** A longer pause between frames (camera paused, app in background) restarts the steadiness clock. */
    val maxFrameGapMillis: Long = 500,
    /**
     * Big faces may have a sticker or two that keeps flickering between two readings (glare, a color
     * right between two others) without the face moving: one such sticker is tolerated per this many
     * stickers of a face, at most [maxFlickeringCells]. See [toleratedFlicker].
     */
    val cellsPerFlickeringCell: Int = 16,
    /** Most stickers allowed to flicker while a face still counts as steady. */
    val maxFlickeringCells: Int = 2,
    /**
     * For this long after an auto-capture (in frame time) the shutter is ignored: a tap meant for
     * the face just captured automatically would otherwise put that same face into the next step.
     */
    val shutterGuardMillis: Long = 400,
) {
    init {
        require(stableMillis > 0 && hintDelayMillis >= 0 && maxFrameGapMillis > 0 && shutterGuardMillis >= 0)
        require(smoothingFrames >= 1 && captureFrames >= 1)
        require(cellsPerFlickeringCell >= 1 && maxFlickeringCells >= 0)
    }

    /**
     * How many stickers of a [size]×[size] face may read differently while it still counts as the same,
     * steady face: none up to 3×3, one for 4×4 and 5×5, two from 6×6 on (with the defaults).
     */
    fun toleratedFlicker(size: Int): Int = (size * size / cellsPerFlickeringCell).coerceAtMost(maxFlickeringCells)
}

/** A gentle heads-up about the face currently in view. */
@Immutable
sealed interface ScanHint {
    /** Cubes with fixed centers: the face in view is not the one this step asks for. */
    data class WrongFace(val seen: CubeColor, val expected: CubeColor) : ScanHint

    /** Cubes with fixed centers: the face in view was already captured, at [step]; it won't be auto-captured again. */
    data class AlreadyScanned(val color: CubeColor, val step: ScanStep) : ScanHint

    /**
     * Cubes without fixed centers: the face in view looks just like the one captured at [step]
     * (perhaps turned in the frame, e.g. after flipping the cube only a quarter turn), so it is
     * most likely that face again; it won't be auto-captured.
     */
    data class SameAsCaptured(val step: ScanStep) : ScanHint
}

/**
 * Two captured faces of a cube without fixed centers that look identical (in some way the face can
 * be turned in the frame): often the same face scanned twice, though two different faces of a
 * scrambled cube can look alike too (most often on a 2×2).
 *
 * @property first the earlier of the two in step order.
 * @property second the later of the two.
 */
@Immutable
data class LookAlike(val first: ScanStep, val second: ScanStep) {
    init {
        require(first.ordinal < second.ordinal) { "$first must come before $second" }
    }

    /** Whether [step] is one of the two faces. */
    operator fun contains(step: ScanStep): Boolean = step == first || step == second

    companion object {
        /** The pair of [a] and [b], in either order. */
        fun of(a: ScanStep, b: ScanStep): LookAlike = if (a.ordinal < b.ordinal) LookAlike(a, b) else LookAlike(b, a)
    }
}

/**
 * Everything the scan screen shows, as one immutable snapshot.
 *
 * @property size the cube's size N: every face has N×N stickers.
 * @property currentStep the face being scanned (or retaken) now.
 * @property captures the colors of each captured face, indexed by [ScanStep.ordinal]; null where
 *   nothing was captured yet. N² colors, row-major as seen on screen. Read with what the session has
 *   learned about the cube's colors, so they may change as more faces are captured.
 * @property liveColors the N² colors currently inside the guide, or null before the first frame.
 * @property autoCapture whether a steady face that fits this step is captured automatically.
 * @property hint a heads-up about the face in view, if any.
 * @property lookAlikePairs cubes without fixed centers: pairs of captured faces that look identical
 *   (in any of the four ways a face can be turned), so one of them may be the same face scanned
 *   twice. A pair is dropped once one of its faces is redone and still looks like the
 *   other: then they are two different faces that happen to look alike.
 * @property isComplete all six faces are captured.
 * @property captureCount number of captures so far; changes exactly when a capture happens.
 * @property lastCaptured the step of the latest capture.
 * @property torchAvailable whether the camera has a flashlight.
 * @property torchOn whether the flashlight is on.
 * @property stickerColors display colors (0xAARRGGBB) of this cube's stickers as learned from the
 *   captured faces (see [LearnedPalette]); empty while nothing is learned or the cube looks standard.
 */
@Immutable
data class ScanUiState(
    val size: Int = 3,
    val currentStep: ScanStep = ScanStep.Front,
    val captures: List<List<CubeColor>?> = List(ScanStep.entries.size) { null },
    val liveColors: List<CubeColor>? = null,
    val autoCapture: Boolean = true,
    val hint: ScanHint? = null,
    val lookAlikePairs: List<LookAlike> = emptyList(),
    val isComplete: Boolean = false,
    val captureCount: Int = 0,
    val lastCaptured: ScanStep? = null,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val stickerColors: Map<CubeColor, Int> = emptyMap(),
) {
    /** Whether the cube has fixed centers (odd sizes), so faces are told apart by their center color. */
    val hasFixedCenters: Boolean get() = size % 2 == 1

    /** Index of the center sticker in a row-major face (meaningful for [hasFixedCenters] only). */
    val centerIndex: Int get() = size * size / 2

    /** The faces in [lookAlikePairs]. */
    val lookAlikes: Set<ScanStep> get() = lookAlikePairs.flatMapTo(mutableSetOf()) { listOf(it.first, it.second) }

    /** Whether [step] has been captured. */
    fun isCaptured(step: ScanStep): Boolean = captures[step.ordinal] != null

    /** How many faces are captured. */
    val capturedCount: Int get() = captures.count { it != null }

    /**
     * Whether the steps still go by the standard color scheme's colors ([ScanStep.color]): a cube
     * with fixed centers whose every captured face has its step's standard center color. Once a
     * face comes in with another center, the cube's colors are arranged differently (or it was
     * turned another way than asked), and the steps go by position, as for cubes without fixed
     * centers.
     */
    val guidedByColor: Boolean
        get() = hasFixedCenters && ScanStep.entries.all { step -> captures[step.ordinal]?.let { it[centerIndex] == step.color } ?: true }

    /**
     * The center color [step]'s face is known by, on a cube with fixed centers: the standard one
     * while [guidedByColor], else the one it was captured with; null for a face not captured yet
     * once the steps go by position, and always without fixed centers.
     */
    fun centerColorOf(step: ScanStep): CubeColor? = when {
        !hasFixedCenters -> null
        guidedByColor -> step.color
        else -> captures[step.ordinal]?.get(centerIndex)
    }

    /** The center color the current step expects in front, if known (see [centerColorOf]). */
    val expectedCenter: CubeColor? get() = centerColorOf(currentStep)

    /** The center color on top while [step] is scanned, while the steps go by color ([guidedByColor]). */
    fun topColorOf(step: ScanStep): CubeColor? = if (guidedByColor) step.topColor else null

    /**
     * Whether the cube was turned on from the previous step's face, as the step's cue asks ("Turn
     * the cube to the left"), rather than placed anew: the step right after its predecessor was
     * captured.
     */
    val turnedFromPrevious: Boolean get() = followsPreviousStep && currentStep.previous != null

    /**
     * Whether this step would take a face with [center] in the middle (cubes with fixed centers):
     * not a face captured at another step, and the one this step expects ([expectedCenter]); or
     * any other, when the steps go by position or the cube was just turned on as asked
     * ([turnedFromPrevious]): on a cube whose colors are arranged differently, the turn brings
     * round another color than the standard one, and that face is the one to scan. Except the
     * color asked to stay on top, which no turn brings to the front.
     */
    fun takesCenter(center: CubeColor): Boolean {
        if (!hasFixedCenters) return false
        if (ScanStep.entries.any { it != currentStep && captures[it.ordinal]?.get(centerIndex) == center }) return false
        val expected = expectedCenter ?: return true
        return center == expected || (turnedFromPrevious && center != topColorOf(currentStep))
    }

    /** Whether the live center is one this step takes ([takesCenter]; always false without fixed centers). */
    val centerMatches: Boolean get() = liveColors?.get(centerIndex)?.let(::takesCenter) == true

    /** Whether the current step scans a face again that was captured before. */
    val isRetake: Boolean get() = isCaptured(currentStep)

    /**
     * Whether the cube is presumably still held as the previous step left it, so the step's relative
     * cue ("Turn it left again") applies: the first step before any capture, or a step right after
     * its predecessor was captured. False after a retake or a jump to another face.
     */
    val followsPreviousStep: Boolean
        get() = !isRetake && when (val previous = currentStep.previous) {
            null -> captureCount == 0
            else -> lastCaptured == previous
        }
}

/**
 * The scanning flow's logic for a cube of any [size], free of Android camera classes so it can be
 * driven by fake frames.
 *
 * Feed it the N² samples of every analyzed frame with [onFrame] (any thread). It reads them live
 * with the session's [AdaptiveLiveClassifier], smooths out flicker, tracks how long the face has
 * been held steady and captures automatically when the face in view is steady and fits the step:
 *
 *  - Odd sizes have fixed centers: the face must not be one captured already
 *    ([ScanHint.AlreadyScanned]), and must have the center color this step expects, unless the
 *    cube was just turned on as asked or its colors turned out to be arranged differently
 *    ([ScanUiState.takesCenter]; [ScanHint.WrongFace] otherwise).
 *  - Even sizes have no fixed centers, so any face goes, as long as it doesn't look exactly like a
 *    face captured already, however it is turned in the frame ([ScanHint.SameAsCaptured]); faces
 *    captured by hand that look alike are reported in [ScanUiState.lookAlikePairs]. Redoing one of
 *    them by hand while it still looks like the other tells the controller they really are two
 *    faces that look alike: the pair is no longer reported or checked against each other.
 *
 * Steadiness tolerates a sticker or two that keeps flickering on big faces
 * ([ScanTuning.toleratedFlicker]). Manual capture ([capture]) is always allowed, except for a
 * moment right after an auto-capture ([ScanTuning.shutterGuardMillis]). After every capture
 * the classifier learns the cube's colors from all captured faces
 * ([AdaptiveLiveClassifier.learnFaces]); the captured faces are read again with what it learned, and
 * [ScanUiState.stickerColors] gives the cube's own display colors. After a capture the flow moves on
 * to the next face still missing; [selectStep] goes back to retake one. Observe [state].
 *
 * All methods are thread-safe. Use [saver] to keep a scan in progress across activity recreation
 * and process death.
 *
 * @param classifier this scan session's classifier; it is reset here, so pass a fresh session's.
 */
class ScanController(
    val size: Int = 3,
    private val tuning: ScanTuning = ScanTuning(),
    private val classifier: AdaptiveLiveClassifier = AdaptiveLiveClassifier(),
) {
    init {
        require(size in NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE) { "Unsupported cube size $size" }
        classifier.reset()
    }

    private val cells = size * size
    private val hasFixedCenters = size % 2 == 1
    private val center = cells / 2
    private val flickerTolerance = tuning.toleratedFlicker(size)

    /** The four ways a face can be turned in the frame, as sticker index maps (see [quarterTurns]). */
    private val turns = quarterTurns(size)

    private val lock = Any()
    private val mutableState = MutableStateFlow(ScanUiState(size = size))

    /**
     * The current UI state; updated on every action, and on a frame that changes what is shown (a
     * face held steady changes nothing, so it doesn't update the state).
     */
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private val mutableProgress = MutableStateFlow(0f)

    /**
     * 0..1 progress toward auto-capture: how long the current face has been held steady. Zero when
     * auto-capture is off or would not fire for the face in view. Kept apart from [state] because
     * it changes on every frame while a face is held steady.
     */
    val captureProgress: StateFlow<Float> = mutableProgress.asStateFlow()

    private val recentSamples = ArrayDeque<List<StickerSample>>()
    private val recentColors = ArrayDeque<List<CubeColor>>()
    private val steadySamples = ArrayDeque<List<StickerSample>>()
    private var smoothed: List<CubeColor>? = null
    private var latestSamples: List<StickerSample>? = null

    /** The smoothed colors when the face was last found to have changed: steadiness is measured against them. */
    private var steadyReference: List<CubeColor>? = null

    /** Whether the latest frame agrees with the smoothed colors (false right after a move). */
    private var latestIsSteady = false

    private var lastFrameMillis = 0L
    private var steadySince = 0L

    /** What a heads-up about the face in view would be about, and since when it is. */
    private var subject: HintSubject? = null
    private var subjectSince = 0L

    /** The face just captured: no heads-up about it while it is still in view. */
    private var quietSubject: HintSubject? = null

    private val capturedSamples = arrayOfNulls<List<StickerSample>>(STEPS)

    /**
     * Pairs of faces that look alike but that the user confirmed are different, by redoing one of
     * them and capturing it by hand while it still looked like the other. Kept only while the two
     * captures still look alike.
     */
    private val distinctPairs = mutableSetOf<LookAlike>()

    /** Frame time of the latest auto-capture, while the shutter is still guarded against a late tap. */
    private var autoCapturedAt: Long? = null

    /**
     * Processes one analyzed frame.
     *
     * @param samples the N² stickers inside the guide, row-major as seen on screen.
     * @param timestampMillis frame time, from a monotonic clock.
     */
    fun onFrame(samples: List<StickerSample>, timestampMillis: Long) {
        require(samples.size == cells) { "A ${size}x$size face has $cells stickers, got ${samples.size}" }
        synchronized(lock) {
            if (mutableState.value.isComplete) return
            val gap = timestampMillis - lastFrameMillis
            if (latestSamples != null && (gap < 0 || gap > tuning.maxFrameGapMillis)) resetTracking()
            val first = latestSamples == null
            lastFrameMillis = timestampMillis
            latestSamples = samples

            val colors = samples.map(classifier::classify)
            recentSamples.addLast(samples)
            recentColors.addLast(colors)
            while (recentColors.size > tuning.smoothingFrames) {
                recentColors.removeFirst()
                recentSamples.removeFirst()
            }
            val majority = majorityColors()
            val reference = steadyReference
            if (first || reference == null || differences(majority, reference) > flickerTolerance) {
                steadySince = timestampMillis
                steadyReference = majority
                steadySamples.clear()
            }
            smoothed = majority
            refreshSubject(restart = first)
            latestIsSteady = differences(colors, majority) <= flickerTolerance
            if (latestIsSteady) {
                steadySamples.addLast(samples)
                while (steadySamples.size > tuning.captureFrames) steadySamples.removeFirst()
            }
            if (quietSubject != null && subject != quietSubject && heldFor(subjectSince) >= tuning.hintDelayMillis) {
                quietSubject = null
            }

            if (autoCaptureDue()) {
                captureLocked()
                autoCapturedAt = timestampMillis
            } else {
                publish()
            }
        }
    }

    /**
     * Captures the face in view for the current step (the shutter). Allowed whatever the face, so
     * the user always stays in control; does nothing before the first frame or once complete, and
     * for [ScanTuning.shutterGuardMillis] of frames after an auto-capture: a tap that lands just
     * after the face was captured automatically was meant for that face, not for the next step.
     *
     * @return whether a face was captured.
     */
    fun capture(): Boolean = synchronized(lock) {
        if (mutableState.value.isComplete || latestSamples == null) return false
        val guardedSince = autoCapturedAt
        if (guardedSince != null && heldFor(guardedSince) < tuning.shutterGuardMillis) return false
        captureLocked()
        autoCapturedAt = null
        true
    }

    /** Makes [step] the current one, e.g. to retake a captured face. Ignored once complete. */
    fun selectStep(step: ScanStep) {
        synchronized(lock) {
            val current = mutableState.value
            if (current.isComplete || current.currentStep == step) return
            mutableState.value = current.copy(currentStep = step)
            quietSubject = null
            // Choosing a face is deliberate: the shutter is meant for it.
            autoCapturedAt = null
            restartSteadiness()
            refreshSubject(restart = false)
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
     * The six captured scans in [ScanStep] order (faces F, R, B, L, U, D), each N² samples
     * row-major as seen on screen, ready for
     * [com.andhab.cubelens.core.vision.ScanResolver.resolve] with those scan positions.
     *
     * @throws IllegalStateException if not all faces are captured yet.
     */
    fun scans(): List<List<StickerSample>> = synchronized(lock) {
        capturedSamples.map { checkNotNull(it) { "Not all faces are captured yet" } }
    }

    /**
     * The progress worth keeping (size, captured samples, current step, auto-capture choice, faces
     * confirmed to be different) as a compact array; live tracking and the flashlight start afresh,
     * and what was learned about the cube's colors is learned again from the samples. See [restore].
     */
    private fun save(): IntArray = synchronized(lock) {
        val current = mutableState.value
        val saved = IntArray(savedLength(size)) { NONE }
        saved[0] = SAVED_VERSION
        saved[1] = size
        saved[2] = current.currentStep.ordinal
        saved[3] = if (current.autoCapture) 1 else 0
        saved[4] = current.captureCount
        saved[5] = current.lastCaptured?.ordinal ?: NONE
        saved[6] = distinctPairs.fold(0) { mask, pair -> mask or (1 shl PAIRS.indexOf(pair)) }
        capturedSamples.forEachIndexed { step, samples ->
            samples?.forEachIndexed { sticker, sample ->
                val at = SAVED_HEADER + step * cells * 3 + sticker * 3
                saved[at] = sample.r
                saved[at + 1] = sample.g
                saved[at + 2] = sample.b
            }
        }
        saved
    }

    /** Puts back what [save] kept; ignores anything it doesn't recognize, or a scan of another size. */
    private fun restore(saved: IntArray) {
        if (saved.size < SAVED_HEADER || saved[0] != SAVED_VERSION || saved[1] != size || saved.size != savedLength(size)) return
        synchronized(lock) {
            val steps = ScanStep.entries
            for (step in steps.indices) {
                val at = SAVED_HEADER + step * cells * 3
                capturedSamples[step] = if (saved[at] == NONE) {
                    null
                } else {
                    List(cells) { sticker ->
                        val rgb = at + sticker * 3
                        StickerSample.of(saved[rgb], saved[rgb + 1], saved[rgb + 2])
                    }
                }
            }
            distinctPairs.clear()
            PAIRS.filterIndexedTo(distinctPairs) { k, _ -> (saved[6] and (1 shl k)) != 0 }
            val learned = learn()
            mutableState.value = ScanUiState(
                size = size,
                currentStep = steps.getOrElse(saved[2]) { steps.first() },
                captures = learned.captures,
                autoCapture = saved[3] == 1,
                lookAlikePairs = learned.lookAlikes,
                isComplete = learned.captures.all { it != null },
                captureCount = saved[4].coerceAtLeast(0),
                lastCaptured = steps.getOrNull(saved[5]),
                stickerColors = learned.stickerColors,
            )
        }
    }

    private fun heldFor(since: Long): Long = lastFrameMillis - since

    /** Per sticker, the color seen most often in the recent frames; ties go to the newest frame. */
    private fun majorityColors(): List<CubeColor> = List(cells) { sticker ->
        val counts = IntArray(CubeColor.entries.size)
        for (frame in recentColors) counts[frame[sticker].ordinal]++
        val best = counts.max()
        recentColors.asReversed().first { counts[it[sticker].ordinal] == best }[sticker]
    }

    /** What a heads-up about a face showing [colors] would be about. */
    private fun subjectOf(colors: List<CubeColor>): HintSubject? {
        if (hasFixedCenters) return HintSubject.Center(colors[center])
        // The face just captured, still in view, is that face, even if it also looks like another one.
        val justCaptured = quietSubject as? HintSubject.Captured
        val captures = mutableState.value.captures
        if (justCaptured != null && captures[justCaptured.step.ordinal]?.let { looksAlike(it, colors) } == true) return justCaptured
        return matchingCapture(colors)?.let(HintSubject::Captured)
    }

    /** Recomputes [subject] for the smoothed colors; its clock restarts when it changes (or on [restart]). */
    private fun refreshSubject(restart: Boolean) {
        val live = smoothed ?: return
        val updated = subjectOf(live)
        if (restart || updated != subject) {
            subject = updated
            subjectSince = lastFrameMillis
        }
    }

    /** The step, other than the current one, that already captured a face with [centerColor], if any. */
    private fun capturedElsewhere(centerColor: CubeColor): ScanStep? {
        val current = mutableState.value
        return ScanStep.entries.firstOrNull { step ->
            step != current.currentStep && current.captures[step.ordinal]?.get(center) == centerColor
        }
    }

    /**
     * The step, other than the current one, whose captured face looks like [colors], if any. Faces
     * the user confirmed are different from the current one don't count.
     */
    private fun matchingCapture(colors: List<CubeColor>): ScanStep? {
        val current = mutableState.value
        return ScanStep.entries.firstOrNull { step ->
            step != current.currentStep &&
                LookAlike.of(step, current.currentStep) !in distinctPairs &&
                current.captures[step.ordinal]?.let { looksAlike(it, colors) } == true
        }
    }

    /**
     * Whether [a] and [b] show the same face: equal but for at most the tolerated flicker, held
     * upright or turned. A face scanned twice by mistake often comes round turned, e.g. the back
     * face upside down after flipping the cube only a quarter turn for the bottom.
     */
    private fun looksAlike(a: List<CubeColor>, b: List<CubeColor>): Boolean =
        turns.any { turn -> differences(a, b, turn) <= flickerTolerance }

    /** Whether the face in view could be auto-captured, steadiness aside. */
    private fun autoCaptureEligible(): Boolean {
        val current = mutableState.value
        val live = smoothed ?: return false
        if (!current.autoCapture || current.isComplete || steadySamples.isEmpty()) return false
        return if (hasFixedCenters) {
            current.takesCenter(live[center])
        } else {
            matchingCapture(live) == null
        }
    }

    private fun autoCaptureDue(): Boolean =
        autoCaptureEligible() && heldFor(steadySince) >= tuning.stableMillis

    private fun captureLocked() {
        // The steady frames, unless the face just changed and the smoothing hasn't caught up yet:
        // then the shutter means what is in view right now.
        val samples = (if (latestIsSteady) averageOf(steadySamples, cells) else null) ?: checkNotNull(latestSamples)
        val current = mutableState.value
        val step = current.currentStep
        // Look-alikes the user was warned about and is redoing one face of now.
        val warned = current.lookAlikePairs.filter { step in it }
        capturedSamples[step.ordinal] = samples
        val learned = learn(confirmed = warned)
        val next = nextMissing(after = step, captures = learned.captures)
        mutableState.value = current.copy(
            currentStep = next ?: step,
            captures = learned.captures,
            lookAlikePairs = learned.lookAlikes,
            isComplete = next == null,
            captureCount = current.captureCount + 1,
            lastCaptured = step,
            stickerColors = learned.stickerColors,
        )
        // The live colors follow what was just learned about the cube's colors.
        for (i in recentSamples.indices) recentColors[i] = recentSamples[i].map(classifier::classify)
        if (recentColors.isNotEmpty()) smoothed = majorityColors()
        quietSubject = if (hasFixedCenters) HintSubject.Center(checkNotNull(learned.captures[step.ordinal])[center]) else HintSubject.Captured(step)
        restartSteadiness()
        refreshSubject(restart = false)
        publish()
    }

    /**
     * Teaches the classifier the cube's colors from every captured face (in step order) and reads the
     * captured faces again with what it learned. On cubes with fixed centers the centers get the
     * names the classifier gave them, which may differ from what an earlier capture was read as.
     *
     * @param confirmed look-alikes that were reported and of which one face was just redone: if
     *   they still look alike, they are two different faces that happen to look alike.
     */
    private fun learn(confirmed: List<LookAlike> = emptyList()): Learned {
        val steps = ScanStep.entries.filter { capturedSamples[it.ordinal] != null }
        val faces = steps.map { checkNotNull(capturedSamples[it.ordinal]) }
        val centerNames = if (faces.isEmpty()) null else classifier.learnFaces(faces, size)
        val captures = MutableList<List<CubeColor>?>(STEPS) { null }
        val labels = faces.mapIndexed { k, face ->
            val colors = face.map(classifier::classify).toMutableList()
            centerNames?.getOrNull(k)?.let { colors[center] = it }
            colors.also { captures[steps[k].ordinal] = it }
        }
        val alike = if (hasFixedCenters) {
            emptyList()
        } else {
            labels.indices.flatMap { k ->
                (k + 1 until labels.size).filter { other -> looksAlike(labels[k], labels[other]) }.map { LookAlike(steps[k], steps[it]) }
            }
        }
        distinctPairs.retainAll(alike.toSet())
        distinctPairs += confirmed.filter { it in alike }
        return Learned(captures, LearnedPalette.estimate(faces, labels), alike.filter { it !in distinctPairs })
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
        steadyReference = smoothed
        steadySamples.clear()
    }

    /** Forgets the frame history, e.g. after the camera paused. */
    private fun resetTracking() {
        recentSamples.clear()
        recentColors.clear()
        steadySamples.clear()
        smoothed = null
        steadyReference = null
        latestSamples = null
        latestIsSteady = false
        autoCapturedAt = null
    }

    /** Recomputes the live parts of the state from the tracking fields. */
    private fun publish() {
        val current = mutableState.value
        val live = smoothed
        val hint = when {
            live == null || current.isComplete -> null
            heldFor(subjectSince) < tuning.hintDelayMillis || subject == quietSubject -> null
            else -> hintFor(live, current)
        }
        val progress = if (autoCaptureEligible()) {
            (heldFor(steadySince).toFloat() / tuning.stableMillis).coerceIn(0f, 1f)
        } else {
            0f
        }
        mutableState.value = current.copy(liveColors = live, hint = hint)
        mutableProgress.value = progress
    }

    private fun hintFor(live: List<CubeColor>, current: ScanUiState): ScanHint? {
        if (!hasFixedCenters) return matchingCapture(live)?.let(ScanHint::SameAsCaptured)
        val seen = live[center]
        capturedElsewhere(seen)?.let { return ScanHint.AlreadyScanned(seen, it) }
        val expected = current.expectedCenter
        return if (expected != null && !current.takesCenter(seen)) ScanHint.WrongFace(seen, expected) else null
    }

    /** What a heads-up is about: the center color in view, or the captured face the view looks like. */
    private sealed interface HintSubject {
        data class Center(val color: CubeColor) : HintSubject

        data class Captured(val step: ScanStep) : HintSubject
    }

    /** The captured faces read with the learned colors, the cube's display colors and faces that look alike. */
    private class Learned(
        val captures: List<List<CubeColor>?>,
        val stickerColors: Map<CubeColor, Int>,
        val lookAlikes: List<LookAlike>,
    )

    companion object {
        /**
         * Saves a scan in progress with `rememberSaveable`, so it survives activity recreation
         * (rotation, theme or font changes, resizing) and process death. A saved scan of another
         * size is not restored.
         */
        fun saver(size: Int = 3, tuning: ScanTuning = ScanTuning()): Saver<ScanController, IntArray> = Saver(
            save = { it.save() },
            restore = { saved -> ScanController(size, tuning).apply { restore(saved) } },
        )

        private val STEPS = ScanStep.entries.size
        private const val SAVED_VERSION = 3
        private const val SAVED_HEADER = 7

        /** Every pair of faces, in a fixed order (bit positions of the saved [distinctPairs]). */
        private val PAIRS: List<LookAlike> = ScanStep.entries.flatMap { a ->
            ScanStep.entries.filter { it.ordinal > a.ordinal }.map { b -> LookAlike(a, b) }
        }
        private const val NONE = -1

        private fun savedLength(size: Int): Int = SAVED_HEADER + STEPS * size * size * 3

        /** How many stickers read differently in [a] and [b]. */
        private fun differences(a: List<CubeColor>, b: List<CubeColor>): Int {
            var count = 0
            for (i in a.indices) if (a[i] != b[i]) count++
            return count
        }

        /** How many stickers read differently in [a] turned by [turn] (an index map) and [b]. */
        private fun differences(a: List<CubeColor>, b: List<CubeColor>, turn: IntArray): Int {
            var count = 0
            for (i in b.indices) if (a[turn[i]] != b[i]) count++
            return count
        }

        /**
         * Index maps of a row-major [size]×[size] face turned by 0, 1, 2 and 3 quarter turns
         * clockwise: sticker `i` of the turned face is sticker `turns[k][i]` of the upright one.
         */
        private fun quarterTurns(size: Int): List<IntArray> {
            val quarter = IntArray(size * size) { i -> (size - 1 - i % size) * size + i / size }
            return generateSequence(IntArray(size * size) { it }) { previous -> IntArray(previous.size) { previous[quarter[it]] } }
                .take(4)
                .toList()
        }

        /** Each channel averaged over [frames] per sticker; null if there are none. */
        private fun averageOf(frames: Collection<List<StickerSample>>, cells: Int): List<StickerSample>? {
            if (frames.isEmpty()) return null
            return List(cells) { sticker ->
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
