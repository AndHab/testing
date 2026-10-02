package com.andhab.cubelens.ui.scan

import android.content.res.Resources
import androidx.annotation.StringRes
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor

/*
 * The scan screen's words, worked out from its state. Plain functions of the state and the app's
 * resources, so the copy for every situation can be checked without composing the screen.
 */

/** Display name of a sticker color, capitalized for the start of a sentence: "Green". */
internal fun Resources.colorName(color: CubeColor): String = getString(
    when (color) {
        CubeColor.WHITE -> R.string.color_white
        CubeColor.YELLOW -> R.string.color_yellow
        CubeColor.GREEN -> R.string.color_green
        CubeColor.BLUE -> R.string.color_blue
        CubeColor.RED -> R.string.color_red
        CubeColor.ORANGE -> R.string.color_orange
    },
)

/** Display name of a sticker color inside a sentence: "green". */
internal fun Resources.lowerColorName(color: CubeColor): String = getString(
    when (color) {
        CubeColor.WHITE -> R.string.scan_color_white_lower
        CubeColor.YELLOW -> R.string.scan_color_yellow_lower
        CubeColor.GREEN -> R.string.scan_color_green_lower
        CubeColor.BLUE -> R.string.scan_color_blue_lower
        CubeColor.RED -> R.string.scan_color_red_lower
        CubeColor.ORANGE -> R.string.scan_color_orange_lower
    },
)

/** The bar's title: which face this is, or that a face is being redone. */
internal fun scanTitle(state: ScanUiState, res: Resources): String {
    val center = state.expectedCenter
    return when {
        state.isComplete -> res.getString(R.string.scan_title_done)
        state.isRetake && center != null -> res.getString(R.string.scan_title_redo_color, res.lowerColorName(center))
        state.isRetake -> res.getString(R.string.scan_title_redo_face, state.currentStep.number)
        else -> res.getString(R.string.scan_title_face, (state.capturedCount + 1).coerceAtMost(FACES), FACES)
    }
}

/** The line above the face thumbnails: progress, and how to retake a face. */
internal fun progressCaption(state: ScanUiState, res: Resources): String = when {
    state.isComplete -> res.getString(R.string.scan_caption_done)
    state.capturedCount == 0 -> res.getString(R.string.scan_caption_start)
    else -> res.getString(R.string.scan_caption_progress, state.capturedCount, FACES)
}

/**
 * How a face thumbnail is named: by its center color where that is known
 * ([ScanUiState.centerColorOf]), else by number.
 */
internal fun faceName(step: ScanStep, state: ScanUiState, res: Resources): String =
    faceName(step, state.centerColorOf(step), res)

/** A face thumbnail's name: by its [center] color if known, else by number. */
internal fun faceName(step: ScanStep, center: CubeColor?, res: Resources): String =
    if (center != null) {
        res.getString(R.string.scan_thumb_color, res.colorName(center))
    } else {
        res.getString(R.string.scan_thumb_number, step.number)
    }

/** One status message and how to show it. */
internal data class StatusMessage(val text: String, val tone: StatusTone)

internal enum class StatusTone { Neutral, Good, Warning }

/**
 * The face thumbnail this hint asks the user to tap (to redo it), if any: only when the face in
 * view is the right one but was already captured for another step.
 */
internal fun ScanHint?.pointsAtThumbnail(state: ScanUiState): ScanStep? =
    (this as? ScanHint.AlreadyScanned)?.takeIf { it.color == state.expectedCenter }?.step

/** The status line under the guide: what's happening, or a gentle heads-up about the face in view. */
internal fun statusMessage(state: ScanUiState, res: Resources): StatusMessage {
    val hint = state.hint
    val step = state.currentStep
    val expected = state.expectedCenter
    val top = state.topColorOf(step)
    val center = state.liveColors?.getOrNull(state.centerIndex)
    return when {
        state.isComplete -> StatusMessage(res.getString(R.string.scan_status_done), StatusTone.Good)
        hint is ScanHint.WrongFace -> StatusMessage(
            if (top != null) {
                res.getString(R.string.scan_status_wrong_face, res.lowerColorName(hint.seen), res.lowerColorName(hint.expected), res.lowerColorName(top))
            } else {
                res.getString(R.string.scan_status_wrong_face_short, res.lowerColorName(hint.seen), res.lowerColorName(hint.expected))
            },
            StatusTone.Warning,
        )
        // Usually the cube just hasn't been turned yet: say what to show next.
        hint is ScanHint.AlreadyScanned && hint.color != expected -> StatusMessage(doneNextText(hint, state, res), StatusTone.Warning)
        // The right face, but it was already captured for another step (by hand, at the wrong step).
        hint is ScanHint.AlreadyScanned && state.guidedByColor -> StatusMessage(
            res.getString(R.string.scan_status_wrong_spot, res.colorName(hint.color), res.lowerColorName(hint.step.color)),
            StatusTone.Warning,
        )
        hint is ScanHint.AlreadyScanned -> StatusMessage(res.getString(R.string.scan_status_same_face_any, hint.step.number), StatusTone.Warning)
        hint is ScanHint.SameAsCaptured -> StatusMessage(sameFaceText(hint, state, res), StatusTone.Warning)
        state.liveColors == null -> StatusMessage(res.getString(R.string.scan_status_starting), StatusTone.Neutral)
        !state.hasFixedCenters && state.autoCapture -> StatusMessage(res.getString(R.string.scan_status_hold), StatusTone.Good)
        !state.hasFixedCenters -> StatusMessage(res.getString(R.string.scan_status_tap_any), StatusTone.Good)
        // Another center than the one named, which this step takes all the same (the cube's colors
        // may be arranged differently): say so, so the title's color doesn't seem to be ignored.
        state.centerMatches && center != null && expected != null && center != expected -> StatusMessage(
            res.getString(
                if (state.autoCapture) R.string.scan_status_other_center_hold else R.string.scan_status_other_center_tap,
                res.colorName(center),
            ),
            StatusTone.Good,
        )
        state.centerMatches && state.autoCapture -> StatusMessage(res.getString(R.string.scan_status_hold), StatusTone.Good)
        state.centerMatches -> StatusMessage(res.getString(R.string.scan_status_tap), StatusTone.Good)
        else -> StatusMessage(res.getString(R.string.scan_status_fit), StatusTone.Neutral)
    }
}

/**
 * "Green's done — now show red, white on top": the face in view was captured already (at another
 * step); what to show instead, by color while that is known, else by the turn to make.
 */
private fun doneNextText(hint: ScanHint.AlreadyScanned, state: ScanUiState, res: Resources): String {
    val expected = state.expectedCenter
    val top = state.topColorOf(state.currentStep)
    val done = res.colorName(hint.color)
    return when {
        expected != null && top != null ->
            res.getString(R.string.scan_status_done_next, done, res.lowerColorName(expected), res.lowerColorName(top))
        expected != null -> res.getString(R.string.scan_status_done_next_short, done, res.lowerColorName(expected))
        else -> nextMove(state)?.let { res.getString(R.string.scan_status_done_turn, done, res.getString(it)) }
            ?: res.getString(R.string.scan_status_done_any, done)
    }
}

/** How to get from the previous step's face to this one ("turn the cube left"), while that applies. */
@StringRes
private fun nextMove(state: ScanUiState): Int? =
    if (!state.followsPreviousStep) {
        null
    } else {
        when (state.currentStep) {
            ScanStep.Right, ScanStep.Back, ScanStep.Left -> R.string.scan_next_turn_left
            ScanStep.Top -> R.string.scan_next_tip
            ScanStep.Bottom -> R.string.scan_next_flip
            ScanStep.Front -> null
        }
    }

/** "Same as face 1 — turn the cube left": the face in view was scanned already; what to do next. */
private fun sameFaceText(hint: ScanHint.SameAsCaptured, state: ScanUiState, res: Resources): String {
    // Redoing one of two faces reported as look-alikes, and it still looks like the other: they may
    // well be two different faces that just look alike, and the shutter says so.
    if (hint.step != state.currentStep && state.lookAlikePairs.any { state.currentStep in it && hint.step in it }) {
        return res.getString(R.string.scan_status_same_face_redo, hint.step.number)
    }
    val next = nextMove(state)
    return if (next == null) {
        res.getString(R.string.scan_status_same_face_any, hint.step.number)
    } else {
        res.getString(R.string.scan_status_same_face, hint.step.number, res.getString(next))
    }
}

/**
 * The captured faces that look identical (cubes without fixed centers) to warn about, or null:
 * the first such pair in step order that isn't being redone right now.
 */
internal fun shownLookAlike(state: ScanUiState): LookAlike? =
    if (state.isComplete) null else state.lookAlikePairs.firstOrNull { state.currentStep !in it }

/**
 * A persistent, gentle warning about two captured faces that look identical (cubes without fixed
 * centers), or null. It doesn't claim a mistake: two different faces of a scrambled 2×2 can look
 * the same, and redoing one of them by hand settles it (see [ScanController]).
 */
internal fun lookAlikeWarning(state: ScanUiState, res: Resources): String? =
    shownLookAlike(state)?.let { res.getString(R.string.scan_look_alikes, it.first.number, it.second.number) }

/**
 * What the guide tells a screen reader: the colors seen, sticker by sticker up to 3×3. Bigger faces
 * are summed up per color, most common first ("12 green, 9 white, …"), led by the center color on
 * odd sizes, so a 7×7 face isn't 49 color names.
 */
internal fun guideDescription(n: Int, liveColors: List<CubeColor>?, complete: Boolean, res: Resources): String = when {
    complete -> res.getString(R.string.scan_guide_done)
    liveColors == null -> res.getString(R.string.scan_guide)
    n <= 3 -> res.getString(R.string.scan_guide_seeing, liveColors.joinToString { res.lowerColorName(it) })
    else -> {
        val counts = liveColors.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<CubeColor, Int>> { it.value }.thenBy { it.key.ordinal })
            .joinToString { (color, count) -> res.getString(R.string.scan_guide_count, count, res.lowerColorName(color)) }
        if (n % 2 == 1) {
            res.getString(R.string.scan_guide_center_seeing, res.colorName(liveColors[n * n / 2]), counts)
        } else {
            res.getString(R.string.scan_guide_seeing, counts)
        }
    }
}

/** What the instruction card says: the step, how to get there, and how to hold the cube. */
internal data class Instruction(val step: ScanStep, val title: String, val cue: String, val hold: Hold)

/** How the cube is held, shown as a chip under the instruction. */
internal sealed interface Hold {
    val text: String

    /** Cubes with fixed centers: this center color goes on top. */
    data class TopColor(val color: CubeColor, override val text: String) : Hold

    /** Steps that go by position: keep the side that is on top now on top. */
    data class SameTop(override val text: String) : Hold

    /** Steps that go by position: the first face scanned goes on top ([onTop]) or at the bottom. */
    data class FirstFace(val onTop: Boolean, override val text: String) : Hold
}

/**
 * The instruction for [step] of a cube of [size].
 *
 * @param followsPreviousStep whether the cube is still held as the previous step left it, so the
 *   short relative cue applies ("Turn it left again"); otherwise it says how to get there from any
 *   hold.
 * @param byColor whether the steps go by the standard colors ([ScanUiState.guidedByColor]), which
 *   only cubes with fixed centers (odd sizes) can; otherwise they go by position.
 */
internal fun instructionFor(
    step: ScanStep,
    size: Int,
    followsPreviousStep: Boolean,
    res: Resources,
    byColor: Boolean = size % 2 == 1,
): Instruction =
    if (byColor && size % 2 == 1) centerInstruction(step, followsPreviousStep, res) else sideInstruction(step, followsPreviousStep, res)

private fun centerInstruction(step: ScanStep, followsPreviousStep: Boolean, res: Resources): Instruction {
    val color = res.lowerColorName(step.color)
    val cue = if (!followsPreviousStep) {
        res.getString(R.string.scan_center_anywhere, color)
    } else {
        when (step) {
            ScanStep.Front -> res.getString(R.string.scan_center_cue_front, color)
            ScanStep.Right -> res.getString(R.string.scan_center_cue_right)
            ScanStep.Back -> res.getString(R.string.scan_center_cue_back)
            ScanStep.Left -> res.getString(R.string.scan_center_cue_left)
            ScanStep.Top -> res.getString(R.string.scan_center_cue_top)
            ScanStep.Bottom -> res.getString(R.string.scan_center_cue_bottom)
        }
    }
    return Instruction(
        step = step,
        title = res.getString(R.string.scan_center_title, res.colorName(step.color)),
        cue = cue,
        hold = Hold.TopColor(step.topColor, res.getString(R.string.scan_center_top, res.colorName(step.topColor))),
    )
}

private fun sideInstruction(step: ScanStep, followsPreviousStep: Boolean, res: Resources): Instruction {
    val title = if (followsPreviousStep) {
        when (step) {
            ScanStep.Front -> R.string.scan_side_title_front
            ScanStep.Right -> R.string.scan_side_title_right
            ScanStep.Back -> R.string.scan_side_title_back
            ScanStep.Left -> R.string.scan_side_title_left
            ScanStep.Top -> R.string.scan_side_title_top
            ScanStep.Bottom -> R.string.scan_side_title_bottom
        }
    } else {
        when (step) {
            ScanStep.Front -> R.string.scan_side_anywhere_title_front
            ScanStep.Right -> R.string.scan_side_anywhere_title_right
            ScanStep.Back -> R.string.scan_side_anywhere_title_back
            ScanStep.Left -> R.string.scan_side_anywhere_title_left
            ScanStep.Top -> R.string.scan_side_anywhere_title_top
            ScanStep.Bottom -> R.string.scan_side_anywhere_title_bottom
        }
    }
    val cue = if (followsPreviousStep) {
        when (step) {
            ScanStep.Front -> R.string.scan_side_cue_front
            ScanStep.Right -> R.string.scan_side_cue_right
            ScanStep.Back -> R.string.scan_side_cue_back
            ScanStep.Left -> R.string.scan_side_cue_left
            ScanStep.Top -> R.string.scan_side_cue_top
            ScanStep.Bottom -> R.string.scan_side_cue_bottom
        }
    } else {
        when (step) {
            ScanStep.Front -> R.string.scan_side_anywhere_cue_front
            ScanStep.Right, ScanStep.Back, ScanStep.Left -> R.string.scan_side_anywhere_cue_sides
            ScanStep.Top -> R.string.scan_side_anywhere_cue_top
            ScanStep.Bottom -> R.string.scan_side_anywhere_cue_bottom
        }
    }
    val hold = when (step) {
        ScanStep.Front -> Hold.SameTop(res.getString(if (followsPreviousStep) R.string.scan_side_hold_keep else R.string.scan_side_hold_same))
        ScanStep.Right, ScanStep.Back, ScanStep.Left -> Hold.SameTop(res.getString(R.string.scan_side_hold_same))
        ScanStep.Top -> Hold.FirstFace(onTop = false, res.getString(R.string.scan_side_hold_first_bottom))
        ScanStep.Bottom -> Hold.FirstFace(onTop = true, res.getString(R.string.scan_side_hold_first_top))
    }
    return Instruction(step, res.getString(title), res.getString(cue), hold)
}

/** The number of faces to scan. */
private val FACES = ScanStep.entries.size
