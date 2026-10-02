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
internal fun scanTitle(state: ScanUiState, res: Resources): String = when {
    state.isComplete -> res.getString(R.string.scan_title_done)
    state.isRetake && state.hasFixedCenters -> res.getString(R.string.scan_title_redo_color, res.lowerColorName(state.currentStep.color))
    state.isRetake -> res.getString(R.string.scan_title_redo_face, state.currentStep.number)
    else -> res.getString(R.string.scan_title_face, (state.capturedCount + 1).coerceAtMost(FACES), FACES)
}

/** The line above the face thumbnails: progress, and how to retake a face. */
internal fun progressCaption(state: ScanUiState, res: Resources): String = when {
    state.isComplete -> res.getString(R.string.scan_caption_done)
    state.capturedCount == 0 -> res.getString(R.string.scan_caption_start)
    else -> res.getString(R.string.scan_caption_progress, state.capturedCount, FACES)
}

/** How a face thumbnail is named: by its center color on cubes with fixed centers, else by number. */
internal fun faceName(step: ScanStep, state: ScanUiState, res: Resources): String =
    if (state.hasFixedCenters) {
        res.getString(R.string.scan_thumb_color, res.colorName(step.color))
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
    (this as? ScanHint.AlreadyScanned)?.takeIf { it.color == state.currentStep.color }?.step

/** The status line under the guide: what's happening, or a gentle heads-up about the face in view. */
internal fun statusMessage(state: ScanUiState, res: Resources): StatusMessage {
    val hint = state.hint
    val step = state.currentStep
    return when {
        state.isComplete -> StatusMessage(res.getString(R.string.scan_status_done), StatusTone.Good)
        hint is ScanHint.WrongFace -> StatusMessage(
            res.getString(
                R.string.scan_status_wrong_face,
                res.lowerColorName(hint.seen),
                res.lowerColorName(hint.expected),
                res.lowerColorName(ScanStep.forColor(hint.expected).topColor),
            ),
            StatusTone.Warning,
        )
        // Usually the cube just hasn't been turned yet: say what to show next.
        hint is ScanHint.AlreadyScanned && hint.color != step.color -> StatusMessage(
            res.getString(R.string.scan_status_done_next, res.colorName(hint.color), res.lowerColorName(step.color), res.lowerColorName(step.topColor)),
            StatusTone.Warning,
        )
        // The right face, but it was already captured for another step (by hand, at the wrong step).
        hint is ScanHint.AlreadyScanned -> StatusMessage(
            res.getString(R.string.scan_status_wrong_spot, res.colorName(hint.color), res.lowerColorName(hint.step.color)),
            StatusTone.Warning,
        )
        hint is ScanHint.SameAsCaptured -> StatusMessage(sameFaceText(hint, state, res), StatusTone.Warning)
        state.liveColors == null -> StatusMessage(res.getString(R.string.scan_status_starting), StatusTone.Neutral)
        !state.hasFixedCenters && state.autoCapture -> StatusMessage(res.getString(R.string.scan_status_hold), StatusTone.Good)
        !state.hasFixedCenters -> StatusMessage(res.getString(R.string.scan_status_tap_any), StatusTone.Good)
        state.centerMatches && state.autoCapture -> StatusMessage(res.getString(R.string.scan_status_hold), StatusTone.Good)
        state.centerMatches -> StatusMessage(res.getString(R.string.scan_status_tap), StatusTone.Good)
        else -> StatusMessage(res.getString(R.string.scan_status_fit), StatusTone.Neutral)
    }
}

/** "Same as face 1 — turn the cube left": the face in view was scanned already; what to do next. */
private fun sameFaceText(hint: ScanHint.SameAsCaptured, state: ScanUiState, res: Resources): String {
    @StringRes val next: Int? = if (!state.followsPreviousStep) {
        null
    } else {
        when (state.currentStep) {
            ScanStep.Right, ScanStep.Back, ScanStep.Left -> R.string.scan_next_turn_left
            ScanStep.Top -> R.string.scan_next_tip
            ScanStep.Bottom -> R.string.scan_next_flip
            ScanStep.Front -> null
        }
    }
    return if (next == null) {
        res.getString(R.string.scan_status_same_face_any, hint.step.number)
    } else {
        res.getString(R.string.scan_status_same_face, hint.step.number, res.getString(next))
    }
}

/**
 * A persistent warning about captured faces that look identical (cubes without fixed centers), or
 * null. Not shown while one of them is being redone.
 */
internal fun lookAlikeWarning(state: ScanUiState, res: Resources): String? {
    if (state.isComplete || state.currentStep in state.lookAlikes) return null
    val faces = state.lookAlikes.sortedBy { it.ordinal }
    if (faces.size < 2) return null
    return res.getString(R.string.scan_look_alikes, faces[0].number, faces[1].number)
}

/** What the instruction card says: the step, how to get there, and how to hold the cube. */
internal data class Instruction(val step: ScanStep, val title: String, val cue: String, val hold: Hold)

/** How the cube is held, shown as a chip under the instruction. */
internal sealed interface Hold {
    val text: String

    /** Cubes with fixed centers: this center color goes on top. */
    data class TopColor(val color: CubeColor, override val text: String) : Hold

    /** Cubes without fixed centers: keep the side that is on top now on top. */
    data class SameTop(override val text: String) : Hold

    /** Cubes without fixed centers: the first face scanned goes on top ([onTop]) or at the bottom. */
    data class FirstFace(val onTop: Boolean, override val text: String) : Hold
}

/**
 * The instruction for [step] of a cube of [size].
 *
 * @param followsPreviousStep whether the cube is still held as the previous step left it, so the
 *   short relative cue applies ("Turn it left again"); otherwise it says how to get there from any
 *   hold.
 */
internal fun instructionFor(step: ScanStep, size: Int, followsPreviousStep: Boolean, res: Resources): Instruction =
    if (size % 2 == 1) centerInstruction(step, followsPreviousStep, res) else sideInstruction(step, followsPreviousStep, res)

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
