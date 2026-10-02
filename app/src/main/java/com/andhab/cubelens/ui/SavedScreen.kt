package com.andhab.cubelens.ui

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.core.nxn.SolveStage
import com.andhab.cubelens.ui.review.ReviewEdit
import com.andhab.cubelens.ui.review.ReviewSource
import com.andhab.cubelens.ui.review.ReviewState
import com.andhab.cubelens.ui.theme.StickerPalette

/**
 * The [Screen] on show as a compact int array, so that it survives process death in the view
 * model's saved state (a few thousand ints at most, for a 7×7 solution with its review behind it).
 *
 * Kept: which screen; a scan's size and the review it returns to; a review's colors, unsure
 * stickers, edits (for undo), selection, brush and open face, where the colors came from and the
 * palette they are drawn in; a solution's start colors and its moves stage by stage, as they were
 * (solving again could give another solution, and the user may be halfway through this one). Left
 * out: what only lasts a moment (a hint, a solve under way, the "Leave this cube?" question).
 */
internal object SavedScreen {

    private const val VERSION = 1
    private const val NONE = -1

    private const val HOME = 0
    private const val SCAN = 1
    private const val REVIEW = 2
    private const val SOLVE = 3

    /** [screen] as an int array; see [decode]. */
    fun encode(screen: Screen): IntArray {
        val out = Writer()
        out += VERSION
        when (screen) {
            Screen.Home -> out += HOME
            is Screen.Scan -> {
                out += SCAN
                out += screen.size
                out.optionalReview(screen.returnTo)
            }
            is Screen.Review -> {
                out += REVIEW
                out.review(screen)
            }
            is Screen.Solve -> {
                out += SOLVE
                out.colors(screen.startColors)
                out.solution(screen.solution)
                out.optionalReview(screen.returnTo)
                out.palette(screen.palette)
            }
        }
        return out.toIntArray()
    }

    /** The screen [encode] saved in [saved], or null for anything it doesn't recognize. */
    fun decode(saved: IntArray): Screen? = try {
        val input = Reader(saved)
        require(input.next() == VERSION)
        val screen = when (input.next()) {
            HOME -> Screen.Home
            SCAN -> Screen.Scan(size = input.next().also { require(it in SupportedCubeSizes) }, returnTo = input.optionalReview())
            REVIEW -> input.review()
            SOLVE -> {
                val start = input.colors().requireNoNulls()
                val solution = input.solution()
                require(start.size == 6 * solution.n * solution.n) { "Start colors of another size" }
                val returnTo = input.optionalReview()
                Screen.Solve(start, solution, returnTo, input.palette())
            }
            else -> throw IllegalArgumentException("Unknown screen")
        }
        require(input.done) { "Trailing data" }
        screen
    } catch (_: RuntimeException) {
        null
    }

    private class Writer {
        private var data = IntArray(256)
        private var size = 0

        operator fun plusAssign(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toIntArray(): IntArray = data.copyOf(size)

        fun colors(colors: List<CubeColor?>) {
            this += colors.size
            for (color in colors) this += color?.ordinal ?: NONE
        }

        fun ints(values: Collection<Int>) {
            this += values.size
            for (value in values) this += value
        }

        fun text(text: String) {
            this += text.length
            for (ch in text) this += ch.code
        }

        fun palette(palette: StickerPalette) {
            if (palette == StickerPalette.Standard) {
                this += 0
            } else {
                this += 1
                for (color in CubeColor.entries) this += palette.argb(color)
            }
        }

        fun review(screen: Screen.Review) {
            val review = screen.review
            colors(review.colors)
            this += review.source.ordinal
            ints(review.uncertain.sorted())
            this += review.straightenedFaces
            this += review.selected ?: NONE
            this += review.brush?.ordinal ?: NONE
            this += review.focusedFace?.ordinal ?: NONE
            this += review.history.size
            for (edit in review.history) {
                this += edit.index
                this += edit.previous?.ordinal ?: NONE
                this += if (edit.wasUncertain) 1 else 0
            }
            palette(screen.palette)
        }

        fun optionalReview(screen: Screen.Review?) {
            if (screen == null) {
                this += 0
            } else {
                this += 1
                review(screen)
            }
        }

        fun solution(solution: NxNSolution) {
            this += solution.n
            this += solution.stages.size
            for (stage in solution.stages) {
                text(stage.name)
                this += stage.moves.size
                for (move in stage.moves) {
                    this += move.face.ordinal or (move.fromDepth shl 3) or (move.toDepth shl 8) or (move.turns shl 13)
                }
            }
        }
    }

    private class Reader(private val data: IntArray) {
        private var at = 0

        val done: Boolean get() = at == data.size

        fun next(): Int = data[at++]

        fun count(): Int = next().also { require(it in 0..data.size - at) { "Bad count" } }

        fun colors(): List<CubeColor?> = List(count()) { color(next()) }

        fun color(value: Int): CubeColor? = if (value == NONE) null else CubeColor.entries[value]

        fun text(): String = String(CharArray(count()) { next().toChar() })

        fun palette(): StickerPalette = when (next()) {
            0 -> StickerPalette.Standard
            1 -> StickerPalette.fromArgb(CubeColor.entries.associateWith { next() })
            else -> throw IllegalArgumentException("Bad palette")
        }

        fun review(): Screen.Review {
            val colors = colors()
            val source = ReviewSource.entries[next()]
            val uncertain = List(count()) { next() }.toSet()
            val straightened = next()
            val selected = next().takeIf { it != NONE }
            val brush = color(next())
            val focused = next().takeIf { it != NONE }?.let { Face.entries[it] }
            val history = List(count().also { require(it <= ReviewState.MAX_HISTORY) }) {
                ReviewEdit(index = next(), previous = color(next()), wasUncertain = next() == 1)
            }
            val review = ReviewState(
                colors = colors,
                source = source,
                uncertain = uncertain,
                straightenedFaces = straightened,
                // A selection and a brush never go together.
                selected = selected.takeIf { brush == null },
                brush = brush,
                focusedFace = focused,
                history = history,
            )
            val stickers = colors.indices
            require(uncertain.all { it in stickers } && (selected == null || selected in stickers) && history.all { it.index in stickers })
            require(focused == null || review.usesFaceEditor)
            return Screen.Review(review, palette())
        }

        fun optionalReview(): Screen.Review? = when (next()) {
            0 -> null
            1 -> review()
            else -> throw IllegalArgumentException("Bad review flag")
        }

        fun solution(): NxNSolution {
            val n = next()
            val stages = List(count()) {
                val name = text()
                val moves = List(count()) {
                    val packed = next()
                    LayerMove(Face.entries[packed and 7], (packed shr 3) and 31, (packed shr 8) and 31, (packed shr 13) and 3)
                }
                SolveStage(name, moves)
            }
            return NxNSolution(n, stages)
        }
    }
}
