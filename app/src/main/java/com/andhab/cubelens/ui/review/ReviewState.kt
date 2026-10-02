package com.andhab.cubelens.ui.review

import androidx.compose.runtime.Immutable
import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.CubeValidator
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNError
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNValidator
import com.andhab.cubelens.core.nxn.PieceKind
import com.andhab.cubelens.core.vision.NxNScanAnalysis
import com.andhab.cubelens.ui.DEFAULT_CUBE_SIZE
import com.andhab.cubelens.ui.SupportedCubeSizes
import java.util.concurrent.ConcurrentHashMap

/** Where the colors on the review screen came from. */
enum class ReviewSource {
    /** Resolved from the six camera scans; the user only fixes what was misread. */
    Scan,

    /** Typed in by the user, sticker by sticker, starting from an empty cube. */
    Manual,
}

/** A short-lived tip shown in response to something the user just tried. */
enum class ReviewHint {
    /**
     * The user tapped a fixed center sticker while painting with a brush: the brush never paints
     * a center (it decides its face's color), so a stray tap can't change one.
     */
    CenterLocked,

    /**
     * The user selected a fixed center sticker: it decides its face's color, so it is only worth
     * changing when it is wrong (a misread, or a cube whose colors are arranged differently).
     */
    CenterSelected,
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
     * Every sticker of a 3×3 has a color but no real cube looks like this.
     *
     * @param errors every problem found, most basic first (never empty).
     * @param flagged stickers involved in any of the [errors]; empty when none of them can be pinned
     *   to particular stickers (e.g. a twisted corner).
     */
    data class Invalid(val errors: List<CubeError>, val flagged: Set<Int>) : ReviewCheck {
        init {
            require(errors.isNotEmpty()) { "An invalid cube has at least one problem" }
        }

        /** The problem to tell the user about. */
        val error: CubeError get() = errors.first()

        /** How many problems of the same kind as [error] there are (e.g. two impossible edges). */
        val sameKindCount: Int get() = errors.count { it::class == error::class }
    }

    /**
     * Every sticker of a 2×2 or of a 4×4 and larger has a color but no real cube of that size looks
     * like this.
     *
     * @param errors every problem found, each with a friendly message (never empty).
     * @param flagged stickers involved in any of the [errors]; empty when none of them can be pinned
     *   to particular stickers.
     */
    data class InvalidNxN(val errors: List<NxNError>, val flagged: Set<Int>) : ReviewCheck {
        init {
            require(errors.isNotEmpty()) { "An invalid cube has at least one problem" }
        }

        /** The problem to tell the user about. */
        val error: NxNError get() = errors.first()
    }

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
 * Everything the review screen shows and edits: the 6·N² sticker colors of an N×N cube (any
 * supported size, see [SupportedCubeSizes]) plus the editing session around them. Immutable; edits
 * return a new state (see [tapSticker], [tapColor], [undo] and [focusFace]).
 *
 * Editing works two ways, and both are always available:
 *  - **Select, then color**: tap a sticker to select it, then tap a color to paint it. In manual
 *    entry, painting an empty sticker hops on to the next empty one in [entryOrder], so a face can
 *    be copied with nothing but taps on the palette.
 *  - **Paint**: with nothing selected, tap a color to pick it up as a [brush], then tap stickers to
 *    paint them. Tapping the same color again puts the brush down.
 *
 * On a 4×4 and larger the stickers of the net are too small to tap ([usesFaceEditor]): tapping a face
 * opens it big in a face editor ([focusedFace]), which follows the selection as manual entry hops
 * from face to face.
 *
 * The fixed centers of odd sizes are locked ([isLocked]) against accidental edits, since they decide
 * which color each face has: the brush never paints them, but a center can still be selected and
 * given another color (e.g. one the camera misread, or on a cube whose colors are arranged
 * differently from the standard scheme). Even sizes have no fixed centers.
 *
 * @property colors 6·N² sticker colors in [NxNGeometry] order; `null` means not entered yet.
 * @property uncertain stickers the scanner was unsure about; each leaves the set once edited.
 * @property straightenedFaces how many scanned faces were turned upright automatically.
 * @property selected the sticker waiting for a color, if any (never set while [brush] is).
 * @property brush the color being painted with, if any (never set while [selected] is).
 * @property focusedFace the face open in the face editor, if any.
 * @property history undoable edits, oldest first (at most [MAX_HISTORY]).
 * @property solving a solution is being computed; edits are paused meanwhile.
 * @property confirmingLeave the user asked to leave and is being asked whether to drop this cube.
 */
@Immutable
data class ReviewState(
    val colors: List<CubeColor?>,
    val source: ReviewSource,
    val uncertain: Set<Int> = emptySet(),
    val straightenedFaces: Int = 0,
    val selected: Int? = null,
    val brush: CubeColor? = null,
    val focusedFace: Face? = null,
    val history: List<ReviewEdit> = emptyList(),
    val hint: ReviewHint? = null,
    val solving: Boolean = false,
    val confirmingLeave: Boolean = false,
) {
    /** The cube's size N (3 for a 3×3), from the 6·N² [colors]; any other count is rejected. */
    val n: Int = sizeOf(colors.size)

    /** Sticker layout of an [n]×[n] cube. */
    val geometry: NxNGeometry get() = NxNGeometry.of(n)

    /** How many stickers of each color a finished cube has: N². */
    val stickersPerColor: Int get() = n * n

    /** Whether faces are edited in the big face editor because the net's stickers are too small. */
    val usesFaceEditor: Boolean get() = n >= FACE_EDITOR_MIN_SIZE

    /** How many stickers currently have each color. */
    val counts: Map<CubeColor, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CubeColor.entries.associateWith { color -> colors.count { it == color } }
    }

    /** Validation of the current colors. */
    val check: ReviewCheck by lazy(LazyThreadSafetyMode.PUBLICATION) { checkColors(n, colors) }

    /** Whether the cube can be solved as it stands. */
    val canSolve: Boolean get() = check == ReviewCheck.Valid

    /** Whether there is an edit to take back. */
    val canUndo: Boolean get() = history.isNotEmpty()

    /** Whether leaving now would throw away work worth a second thought: any edit, or a scan. */
    val hasWorkToLose: Boolean get() = canUndo || source == ReviewSource.Scan

    /**
     * Stickers to highlight strongly: those involved in a validation problem. When the problem
     * can't be pinned to particular stickers (a twisted corner, a wrong color count…), the stickers
     * the scanner was unsure about stand in: they are the likeliest culprits.
     */
    val flagged: Set<Int>
        get() = when (val check = check) {
            is ReviewCheck.Invalid -> check.flagged.ifEmpty { uncertain }
            is ReviewCheck.InvalidNxN -> check.flagged.ifEmpty { uncertain }
            else -> emptySet()
        }

    /** The fixed centers of an odd cube, locked against accidental edits; empty for even sizes. */
    val locked: Set<Int> get() = lockedStickers(n)

    /** Whether sticker [index] is a fixed center, locked against accidental edits. */
    fun isLocked(index: Int): Boolean = index in locked

    /** The order manual entry walks the stickers of this cube in; see [entryOrder]. */
    val entryOrder: List<Int> get() = entryOrder(n)

    companion object {
        /** Longest undo history kept. */
        const val MAX_HISTORY = 64

        /** From this size on, faces are edited in the big face editor ([usesFaceEditor]). */
        const val FACE_EDITOR_MIN_SIZE = 4

        /**
         * The faces in the order the net lays them out and manual entry walks them: top; then left,
         * front, right and back, as when turning the cube around; then bottom.
         */
        val FACE_ORDER: List<Face> = listOf(Face.U, Face.L, Face.F, Face.R, Face.B, Face.D)

        private val entryOrders = ConcurrentHashMap<Int, List<Int>>()
        private val lockedSets = ConcurrentHashMap<Int, Set<Int>>()

        /** The order manual entry walks the stickers of an [n]×[n] cube in: face by face in [FACE_ORDER], each face row by row. */
        fun entryOrder(n: Int): List<Int> = entryOrders.getOrPut(n) {
            val geometry = NxNGeometry.of(n)
            FACE_ORDER.flatMap { face -> (0 until n * n).map { geometry.index(face, it / n, it % n) } }
        }

        /**
         * A blank [n]×[n] cube for manual entry, with the first sticker to fill selected. Odd sizes
         * start with their fixed centers set to the standard scheme (white on top, green in front),
         * which a cube arranged otherwise changes like any other sticker; even sizes have no fixed
         * centers and start empty. On a cube with a face editor ([FACE_EDITOR_MIN_SIZE]), the top
         * face opens in it right away.
         */
        fun manual(n: Int = DEFAULT_CUBE_SIZE): ReviewState {
            val geometry = NxNGeometry.of(n)
            val locked = lockedStickers(n)
            val colors = List(geometry.stickerCount) { i ->
                if (i in locked) ColorScheme.STANDARD.colorOf(geometry.faceOf(i)) else null
            }
            val first = entryOrder(n).first { it !in locked }
            return ReviewState(
                colors = colors,
                source = ReviewSource.Manual,
                selected = first,
                focusedFace = if (n >= FACE_EDITOR_MIN_SIZE) geometry.faceOf(first) else null,
            )
        }

        /** The resolved colors of a scan, with its unsure stickers (other than centers) marked for a second look. */
        fun fromScan(analysis: NxNScanAnalysis): ReviewState {
            val locked = lockedStickers(analysis.n)
            return ReviewState(
                colors = analysis.colors,
                source = ReviewSource.Scan,
                uncertain = analysis.uncertain.filterTo(mutableSetOf()) { it in analysis.colors.indices && it !in locked },
                straightenedFaces = analysis.faceRotations.values.count { it.mod(4) != 0 },
            )
        }

        /** N of a cube with [count] stickers. */
        private fun sizeOf(count: Int): Int = SupportedCubeSizes.firstOrNull { 6 * it * it == count }
            ?: throw IllegalArgumentException("Need 6·N² sticker colors for N in $SupportedCubeSizes, got $count")

        private fun lockedStickers(n: Int): Set<Int> = lockedSets.getOrPut(n) {
            if (n % 2 == 0) {
                emptySet()
            } else {
                val geometry = NxNGeometry.of(n)
                (0 until geometry.stickerCount).filterTo(LinkedHashSet()) { geometry.kindOf(it) == PieceKind.FIXED_CENTER }
            }
        }

        private fun checkColors(n: Int, colors: List<CubeColor?>): ReviewCheck {
            val remaining = colors.count { it == null }
            if (remaining > 0) return ReviewCheck.Incomplete(remaining)
            val complete = colors.requireNoNulls()
            if (n == 3) {
                val result = CubeValidator.validate(complete)
                return if (result.isValid) ReviewCheck.Valid else ReviewCheck.Invalid(result.errors, result.flaggedFacelets)
            }
            val result = NxNValidator.validate(NxNCube.of(n, complete))
            return when {
                result.isValid -> ReviewCheck.Valid
                // A cube whose arrangement can't be worked out always reports why, but stay safe.
                result.errors.isEmpty() -> ReviewCheck.InvalidNxN(listOf(UnknownProblem), emptySet())
                else -> ReviewCheck.InvalidNxN(result.errors, result.flaggedStickers)
            }
        }

        private val UnknownProblem = NxNError("Some colors don't fit together. Check the stickers.")
    }
}

/**
 * Handles a tap on sticker [index]: paints it while a brush is up, and otherwise selects it (or
 * deselects it when it already was). A fixed center is never painted by the brush (a tap then only
 * shows a hint), so it changes only on purpose: selected, with a word on what it decides, and then
 * given a color.
 */
fun ReviewState.tapSticker(index: Int): ReviewState {
    require(index in colors.indices) { "No sticker $index" }
    if (solving) return this
    val brush = brush
    val selectedHint = if (isLocked(index)) ReviewHint.CenterSelected else null
    return when {
        brush != null && isLocked(index) -> copy(hint = ReviewHint.CenterLocked)
        brush != null -> paint(index, brush).copy(hint = null)
        selected == index -> copy(selected = null, hint = null)
        else -> copy(selected = index, hint = selectedHint)
    }
}

/**
 * Handles a tap on [color] in the palette: paints the selected sticker (moving on to the next
 * empty one in manual entry, with the face editor following it to its face), or picks the color up
 * as a brush, or puts it down again. Filling the very last empty sticker closes the face editor,
 * so the verdict on the finished cube (and the solve button) comes into view.
 */
fun ReviewState.tapColor(color: CubeColor): ReviewState {
    if (solving) return this
    val target = selected
    if (target == null) return copy(brush = if (brush == color) null else color, hint = null)
    val wasEmpty = colors[target] == null
    val painted = paint(target, color)
    val next = if (wasEmpty) painted.nextEmptyAfter(target) else null
    val face = when {
        focusedFace == null -> null
        next != null -> geometry.faceOf(next)
        wasEmpty -> null // that was the last empty sticker: the cube is complete
        else -> focusedFace
    }
    return painted.copy(selected = next, focusedFace = face, hint = null)
}

/**
 * Takes back the most recent edit and selects the sticker it changed (unless a brush is up). An
 * open face editor turns to that sticker's face, so the change is in view.
 */
fun ReviewState.undo(): ReviewState {
    if (solving) return this
    val edit = history.lastOrNull() ?: return this
    val restored = colors.toMutableList().also { it[edit.index] = edit.previous }
    return copy(
        colors = restored,
        uncertain = if (edit.wasUncertain) uncertain + edit.index else uncertain,
        history = history.dropLast(1),
        selected = if (brush == null) edit.index else null,
        focusedFace = focusedFace?.let { geometry.faceOf(edit.index) },
        hint = null,
    )
}

/**
 * Opens [face] in the face editor. A selection elsewhere is dropped; in manual entry, the face's
 * first empty sticker is selected instead (unless a brush is up), ready for the next color.
 */
fun ReviewState.focusFace(face: Face): ReviewState {
    if (solving) return this
    val current = selected
    val selection = when {
        brush != null -> null
        current != null && geometry.faceOf(current) == face -> current
        source == ReviewSource.Manual -> entryOrder.firstOrNull { geometry.faceOf(it) == face && colors[it] == null }
        else -> null
    }
    return copy(focusedFace = face, selected = selection, hint = null)
}

/**
 * Moves the face editor [steps] faces on in [ReviewState.FACE_ORDER] (negative to go back),
 * wrapping around; does nothing while no face is open.
 */
fun ReviewState.stepFace(steps: Int): ReviewState {
    val face = focusedFace ?: return this
    val order = ReviewState.FACE_ORDER
    return focusFace(order[(order.indexOf(face) + steps).mod(order.size)])
}

/** Closes the face editor, keeping the selection. */
fun ReviewState.closeFace(): ReviewState = copy(focusedFace = null, hint = null)

/** Puts the brush down, clears the selection and closes the face editor. */
fun ReviewState.clearTools(): ReviewState = copy(selected = null, brush = null, focusedFace = null, hint = null)

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

/** The next sticker without a color after [index] in [ReviewState.entryOrder] (wrapping around), if any. */
private fun ReviewState.nextEmptyAfter(index: Int): Int? {
    val order = entryOrder
    val position = order.indexOf(index)
    return (1 until order.size)
        .asSequence()
        .map { order[(position + it) % order.size] }
        .firstOrNull { colors[it] == null }
}
