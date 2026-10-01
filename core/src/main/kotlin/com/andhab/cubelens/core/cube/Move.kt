package com.andhab.cubelens.core.cube

/**
 * The 18 face turns in standard (Singmaster) notation, in Kociemba order:
 * U, U2, U', R, R2, R', F, F2, F', D, D2, D', L, L2, L', B, B2, B'.
 *
 * [turns] is the number of clockwise quarter turns (1, 2 or 3; 3 = counter-clockwise), looking at
 * [face] from outside the cube.
 */
enum class Move(val face: Face, val turns: Int) {
    U1(Face.U, 1), U2(Face.U, 2), U3(Face.U, 3),
    R1(Face.R, 1), R2(Face.R, 2), R3(Face.R, 3),
    F1(Face.F, 1), F2(Face.F, 2), F3(Face.F, 3),
    D1(Face.D, 1), D2(Face.D, 2), D3(Face.D, 3),
    L1(Face.L, 1), L2(Face.L, 2), L3(Face.L, 3),
    B1(Face.B, 1), B2(Face.B, 2), B3(Face.B, 3);

    /** Standard notation, e.g. "R", "U2", "F'". */
    val notation: String
        get() = face.name + when (turns) {
            1 -> ""
            2 -> "2"
            else -> "'"
        }

    val inverse: Move
        get() = of(face, 4 - turns)

    /**
     * Facelet permutation: after this move, facelet `i` holds what was at `permutation[i]` before.
     * Derived from the 3D geometry in [Facelets], so it is consistent with any renderer that uses
     * the same geometry.
     */
    val permutation: IntArray by lazy { buildPermutation(face, turns) }

    override fun toString(): String = notation

    companion object {
        fun of(face: Face, turns: Int): Move {
            val t = ((turns % 4) + 4) % 4
            require(t != 0) { "A move needs 1..3 quarter turns" }
            return entries[face.ordinal * 3 + t - 1]
        }

        /** Parses a single token like "R", "U2", "F'", "B2'", "L3" (case-sensitive face letter). */
        fun parse(token: String): Move {
            val t = token.trim()
            require(t.isNotEmpty()) { "Empty move" }
            val face = Face.fromChar(t[0])
            val suffix = t.substring(1).replace('’', '\'')
            val turns = when (suffix) {
                "", "1" -> 1
                "2", "2'" -> 2
                "'", "3", "1'" -> 3
                else -> throw IllegalArgumentException("Bad move: '$token'")
            }
            return of(face, turns)
        }

        /** Parses a whitespace-separated sequence such as "R U R' U'". */
        fun parseSequence(sequence: String): List<Move> =
            sequence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::parse)

        fun format(moves: List<Move>): String = moves.joinToString(" ") { it.notation }

        private fun buildPermutation(face: Face, turns: Int): IntArray {
            // forward[i] = where facelet i goes after one clockwise quarter turn.
            val forward = IntArray(Facelets.COUNT) { i ->
                if (Facelets.isInLayer(i, face)) {
                    Facelets.indexOf(
                        Facelets.rotateClockwise(Facelets.position[i], face.normal),
                        Facelets.rotateClockwise(Facelets.normal[i], face.normal),
                    )
                } else {
                    i
                }
            }
            var dest = IntArray(Facelets.COUNT) { it }
            repeat(turns) { dest = IntArray(Facelets.COUNT) { i -> forward[dest[i]] } }
            // dest[i] = final location of facelet i; invert to "new[j] = old[perm[j]]".
            val perm = IntArray(Facelets.COUNT)
            for (i in 0 until Facelets.COUNT) perm[dest[i]] = i
            return perm
        }
    }
}
