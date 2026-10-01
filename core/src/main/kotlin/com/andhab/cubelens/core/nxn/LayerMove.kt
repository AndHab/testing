package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Move

/**
 * A turn of one or more adjacent layers of an N×N×N cube.
 *
 * Layers are counted from [face]: depth 1 is the outer layer of [face]. The move turns layers
 * [fromDepth]..[toDepth] by [turns] clockwise quarter turns as seen looking at [face] from outside
 * (3 = counter-clockwise). Outer-layer moves are exactly the 3×3 [Move]s.
 *
 * Notation (WCA for outer/wide moves, SiGN for inner slices):
 *  - `R` outer layer, `Rw` two outer layers, `3Rw` three outer layers;
 *  - `2R` only the second layer, `3R` only the third;
 *  - `2-3Rw` layers two to three;
 *  - suffix `2` for a half turn, `'` for counter-clockwise.
 */
data class LayerMove(val face: Face, val fromDepth: Int, val toDepth: Int, val turns: Int) {
    init {
        require(fromDepth >= 1 && toDepth >= fromDepth) { "Bad layer range $fromDepth..$toDepth" }
        require(turns in 1..3) { "A move needs 1..3 quarter turns, got $turns" }
    }

    /** Number of layers turned. */
    val width: Int get() = toDepth - fromDepth + 1

    /** True for a plain outer-layer turn (a 3×3-style move). */
    val isOuter: Boolean get() = fromDepth == 1 && toDepth == 1

    val inverse: LayerMove get() = copy(turns = 4 - turns)

    /** Same layers, combined with [other]'s turns; null if they cancel. Requires the same layers. */
    fun combine(other: LayerMove): LayerMove? {
        require(sameLayers(other)) { "Can only combine moves of the same layers" }
        val t = (turns + other.turns) % 4
        return if (t == 0) null else copy(turns = t)
    }

    fun sameLayers(other: LayerMove): Boolean =
        face == other.face && fromDepth == other.fromDepth && toDepth == other.toDepth

    val notation: String
        get() {
            val base = when {
                fromDepth == 1 && toDepth == 1 -> face.name
                fromDepth == 1 && toDepth == 2 -> "${face.name}w"
                fromDepth == 1 -> "$toDepth${face.name}w"
                fromDepth == toDepth -> "$fromDepth${face.name}"
                else -> "$fromDepth-$toDepth${face.name}w"
            }
            return base + when (turns) {
                1 -> ""
                2 -> "2"
                else -> "'"
            }
        }

    /** The equivalent 3×3 [Move] for an outer-layer turn, else null. */
    fun toMove(): Move? = if (isOuter) Move.of(face, turns) else null

    override fun toString(): String = notation

    companion object {
        private val TOKEN = Regex("""^(?:(\d+)(?:-(\d+))?)?([URFDLBurfdlb])(w)?(2'|2|'|3|1)?$""")

        fun outer(face: Face, turns: Int): LayerMove = LayerMove(face, 1, 1, turns)

        fun of(move: Move): LayerMove = LayerMove(move.face, 1, 1, move.turns)

        /**
         * Parses one token, e.g. "R", "U2", "F'", "Rw", "3Rw'", "2R2", "2-3Lw", or lowercase "r"
         * (= "Rw").
         */
        fun parse(token: String): LayerMove {
            val t = token.trim().replace('’', '\'')
            val m = TOKEN.matchEntire(t) ?: throw IllegalArgumentException("Bad move: '$token'")
            val (a, b, letter, w, suffix) = m.destructured
            val lower = letter[0].isLowerCase()
            val face = Face.fromChar(letter[0].uppercaseChar())
            val wide = w.isNotEmpty() || lower
            val (from, to) = when {
                b.isNotEmpty() -> {
                    require(wide) { "A layer range needs 'w': '$token'" }
                    a.toInt() to b.toInt()
                }
                a.isNotEmpty() && wide -> 1 to a.toInt()
                a.isNotEmpty() -> a.toInt() to a.toInt()
                wide -> 1 to 2
                else -> 1 to 1
            }
            val turns = when (suffix) {
                "", "1" -> 1
                "2", "2'" -> 2
                else -> 3
            }
            return LayerMove(face, from, to, turns)
        }

        fun parseSequence(sequence: String): List<LayerMove> =
            sequence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::parse)

        fun format(moves: List<LayerMove>): String = moves.joinToString(" ") { it.notation }
    }
}

/** This outer-layer move as a [LayerMove]. */
fun Move.toLayerMove(): LayerMove = LayerMove.of(this)
