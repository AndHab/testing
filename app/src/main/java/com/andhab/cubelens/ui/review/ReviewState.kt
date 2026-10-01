package com.andhab.cubelens.ui.review

import androidx.compose.runtime.Immutable
import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.CubeValidator
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.vision.ScanAnalysis

/** Where the colors on the review screen came from. */
enum class ReviewSource {
    /** Resolved from the six camera scans; the user only fixes what was misread. */
    Scan,

    /** Typed in by the user, sticker by sticker, starting from an empty cube. */
    Manual,
}

/** A short-lived tip shown in response to something the user just tried. */
enum class ReviewHint {
    /** The user tapped a center sticker, which cannot change (it decides its face's color). */
    CenterLocked,
}

/**
 * Where the colors stand: still being filled in, impossible for a real cube, or ready to solve.
 * Derived from the colors alone; see [ReviewState.check].
 */
@Immutable
sealed interface ReviewCheck {
    /** Some stickers have no color yet. */
    data class Incomplete(val remaining: Int) : ReviewCheck

    /**
     * Every sticker has a color but no real cube looks like this.
     *
     * @param error the problem to tell the user about first.
     * @param flagged stickers involved in any problem found, to highlight.
     */
    data class Invalid(val error: CubeError, val flagged: Set<Int>) : ReviewCheck

    /** A real, solvable cube. */
    data object Valid : ReviewCheck
}

/**
 * One undoable edit: sticker [index] used to be [previous], and was on the "double-check" list if
 * [wasUncertain].
 */
@Immutable
data class ReviewEdit(val index: Int, val previous: CubeColor?, val wasUncertain: Boolean)

/**
 * Everything the review screen shows and edits: the 54 sticker colors plus the editing session
 * around them. Immutable; edits return a new state (see [tapSticker], [tapColor] and [undo]).
 *
 * Editing works two ways, and both are always available:
 *  - **Select, then color**: tap a sticker to select it, then tap a color to paint it. In manual
 *    entry, painting an empty sticker hops on to the next empty one, so a face can be copied with
 *    nothing but taps on the palette.
 *  - **Paint**: with nothing selected, tap a color to pick it up as a [brush], then tap stickers to
 *    paint them. Tapping the same color again puts the brush down.
 *
 * Centers never change: the app always shows the cube with white on top and green in front, so the
 * center colors are fixed by [ColorScheme.STANDARD].
 *
 * @property colors 54 sticker colors in facelet order; `null` means not entered yet.
 * @property uncertain stickers the scanner was unsure about; each leaves the set once edited.
 * @property straightenedFaces how many scanned faces were turned upright automatically.
 * @property selected the sticker waiting for a color, if any (never set while [brush] is).
 * @property brush the color being painted with, if any (never set while [selected] is).
 * @property history undoable edits, oldest first (at most [MAX_HISTORY]).
 * @property solving a solution is being computed; edits are paused meanwhile.
 */
@Immutable
data class ReviewState(
    val colors: List<CubeColor?>,
    val source: ReviewSource,
    val uncertain: Set<Int> = emptySet(),
    val straightenedFaces: Int = 0,
    val selected: Int? = null,
    val brush: CubeColor? = null,
    val history: List<ReviewEdit> = emptyList(),
    val hint: ReviewHint? = null,
    val solving: Boolean = false,
) {
    init {
        require(colors.size == Facelets.COUNT) { "Need ${Facelets.COUNT} sticker colors, got ${colors.size}" }
    }

    /** How many stickers currently have each color. */
    val counts: Map<CubeColor, Int> = CubeColor.entries.associateWith { color -> colors.count { it == color } }

    /** Validation of the current colors. */
    val check: ReviewCheck = checkColors(colors)

    /** Whether the cube can be solved as it stands. */
    val canSolve: Boolean get() = check == ReviewCheck.Valid

    /** Whether there is an edit to take back. */
    val canUndo: Boolean get() = history.isNotEmpty()

    /** Stickers to highlight strongly: those involved in a validation problem. */
    val flagged: Set<Int> get() = (check as? ReviewCheck.Invalid)?.flagged.orEmpty()

    companion object {
        /** Longest undo history kept. */
        const val MAX_HISTORY = 64

        /** A blank cube for manual entry: only the centers are set, and the first sticker is selected. */
        fun manual(): ReviewState = ReviewState(
            colors = List(Facelets.COUNT) { i -> if (isCenter(i)) centerColor(i) else null },
            source = ReviewSource.Manual,
            selected = 0,
        )

        /** The resolved colors of a scan, with its unsure stickers marked for a second look. */
        fun fromScan(analysis: ScanAnalysis): ReviewState = ReviewState(
            colors = analysis.colors,
            source = ReviewSource.Scan,
            uncertain = analysis.uncertain.filterTo(mutableSetOf()) { it in 0 until Facelets.COUNT && !isCenter(it) },
            straightenedFaces = analysis.faceRotations.values.count { it.mod(4) != 0 },
        )

        /** Whether facelet [index] is the center of its face. */
        fun isCenter(index: Int): Boolean = index % 9 == 4

        private fun centerColor(index: Int): CubeColor = ColorScheme.STANDARD.colorOf(Facelets.faceOf(index))

        private fun checkColors(colors: List<CubeColor?>): ReviewCheck {
            val remaining = colors.count { it == null }
            if (remaining > 0) return ReviewCheck.Incomplete(remaining)
            val result = CubeValidator.validate(colors.requireNoNulls())
            return if (result.isValid) {
                ReviewCheck.Valid
            } else {
                ReviewCheck.Invalid(result.errors.first(), result.flaggedFacelets)
            }
        }
    }
}

/**
 * Handles a tap on sticker [index]: paints it while a brush is up, and otherwise selects it (or
 * deselects it when it already was). Centers cannot change; tapping one only shows a hint.
 */
fun ReviewState.tapSticker(index: Int): ReviewState {
    require(index in 0 until Facelets.COUNT) { "No sticker $index" }
    if (solving) return this
    if (ReviewState.isCenter(index)) return copy(hint = ReviewHint.CenterLocked)
    val brush = brush
    return when {
        brush != null -> paint(index, brush).copy(hint = null)
        selected == index -> copy(selected = null, hint = null)
        else -> copy(selected = index, hint = null)
    }
}

/**
 * Handles a tap on [color] in the palette: paints the selected sticker (moving on to the next
 * empty one in manual entry), or picks the color up as a brush, or puts it down again.
 */
fun ReviewState.tapColor(color: CubeColor): ReviewState {
    if (solving) return this
    val target = selected
    if (target == null) return copy(brush = if (brush == color) null else color, hint = null)
    val wasEmpty = colors[target] == null
    val painted = paint(target, color)
    val next = if (wasEmpty) painted.nextEmptyAfter(target) else null
    return painted.copy(selected = next, hint = null)
}

/** Takes back the most recent edit and selects the sticker it changed (unless a brush is up). */
fun ReviewState.undo(): ReviewState {
    if (solving) return this
    val edit = history.lastOrNull() ?: return this
    val restored = colors.toMutableList().also { it[edit.index] = edit.previous }
    return copy(
        colors = restored,
        uncertain = if (edit.wasUncertain) uncertain + edit.index else uncertain,
        history = history.dropLast(1),
        selected = if (brush == null) edit.index else null,
        hint = null,
    )
}

/** Puts the brush down and clears the selection. */
fun ReviewState.clearTools(): ReviewState = copy(selected = null, brush = null, hint = null)

/** Paints sticker [index] with [color], recording the edit; a no-op when it already has that color. */
private fun ReviewState.paint(index: Int, color: CubeColor): ReviewState {
    val previous = colors[index]
    if (previous == color) return this
    val edit = ReviewEdit(index, previous, wasUncertain = index in uncertain)
    return copy(
        colors = colors.toMutableList().also { it[index] = color },
        uncertain = uncertain - index,
        history = (history + edit).takeLast(ReviewState.MAX_HISTORY),
    )
}

/** The next sticker without a color after [index] in reading order (wrapping around), if any. */
private fun ReviewState.nextEmptyAfter(index: Int): Int? =
    (1 until Facelets.COUNT)
        .map { (index + it) % Facelets.COUNT }
        .firstOrNull { colors[it] == null }
