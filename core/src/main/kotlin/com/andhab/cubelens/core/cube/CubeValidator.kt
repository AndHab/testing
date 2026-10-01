package com.andhab.cubelens.core.cube

/** A reason why a scanned/entered cube cannot be a real, solvable cube. */
sealed class CubeError {
    /** Stickers the UI should highlight for this problem (may be empty for global problems). */
    abstract val facelets: Set<Int>

    /** Short, user-facing explanation. */
    abstract val message: String

    /** The six centers are not six different colors. */
    data object CentersNotDistinct : CubeError() {
        override val facelets: Set<Int> = Face.entries.map { Facelets.center(it) }.toSet()
        override val message = "Each face needs a different center color."
    }

    /** A color appears a number of times other than nine. */
    data class WrongColorCount(val face: Face, val count: Int) : CubeError() {
        override val facelets: Set<Int> = emptySet()
        override val message = "Found $count stickers matching the ${face.name} center (should be 9)."
    }

    /** The stickers at a corner position cannot belong to a real corner piece. */
    data class ImpossibleCorner(val position: Int) : CubeError() {
        override val facelets: Set<Int> = CubieCube.CORNER_FACELET[position].toSet()
        override val message = "The ${CubieCube.CORNER_NAMES[position]} corner has a color combination that doesn't exist."
    }

    /** The stickers at an edge position cannot belong to a real edge piece. */
    data class ImpossibleEdge(val position: Int) : CubeError() {
        override val facelets: Set<Int> = CubieCube.EDGE_FACELET[position].toSet()
        override val message = "The ${CubieCube.EDGE_NAMES[position]} edge has a color combination that doesn't exist."
    }

    /** The same corner piece was found at several positions. */
    data class DuplicateCorner(val cubie: Int, val positions: List<Int>) : CubeError() {
        override val facelets: Set<Int> = positions.flatMap { CubieCube.CORNER_FACELET[it].toList() }.toSet()
        override val message = "The ${CubieCube.CORNER_NAMES[cubie]} corner piece appears ${positions.size} times."
    }

    /** The same edge piece was found at several positions. */
    data class DuplicateEdge(val cubie: Int, val positions: List<Int>) : CubeError() {
        override val facelets: Set<Int> = positions.flatMap { CubieCube.EDGE_FACELET[it].toList() }.toSet()
        override val message = "The ${CubieCube.EDGE_NAMES[cubie]} edge piece appears ${positions.size} times."
    }

    /** Corner twists don't add up: a single corner has been twisted in place. */
    data object TwistedCorner : CubeError() {
        override val facelets: Set<Int> = emptySet()
        override val message = "One corner looks twisted. Check the corner sticker colors."
    }

    /** Edge flips don't add up: a single edge has been flipped in place. */
    data object FlippedEdge : CubeError() {
        override val facelets: Set<Int> = emptySet()
        override val message = "One edge looks flipped. Check the edge sticker colors."
    }

    /** Corner and edge permutation parities differ: two pieces have been swapped. */
    data object Parity : CubeError() {
        override val facelets: Set<Int> = emptySet()
        override val message = "Two pieces appear swapped. Check the sticker colors."
    }
}

data class ValidationResult(val errors: List<CubeError>, val cubie: CubieCube?) {
    val isValid: Boolean get() = errors.isEmpty() && cubie != null

    /** All stickers flagged by any error. */
    val flaggedFacelets: Set<Int> get() = errors.flatMap { it.facelets }.toSet()
}

object CubeValidator {

    /** Validates 54 sticker colors in facelet order. */
    fun validate(colors: List<CubeColor>): ValidationResult {
        val cube = FaceletCube.fromColors(colors)
            ?: return ValidationResult(listOf(CubeError.CentersNotDistinct), null)
        return validate(cube)
    }

    /** Full check: counts, piece identity, duplicates, twist, flip and permutation parity. */
    fun validate(cube: FaceletCube): ValidationResult {
        val errors = mutableListOf<CubeError>()

        val counts = IntArray(6)
        for (i in 0 until Facelets.COUNT) counts[cube[i].ordinal]++
        for (face in Face.entries) {
            if (counts[face.ordinal] != 9) errors += CubeError.WrongColorCount(face, counts[face.ordinal])
        }

        val cp = IntArray(8) { -1 }
        val co = IntArray(8)
        val ep = IntArray(12) { -1 }
        val eo = IntArray(12)
        for (i in 0 until 8) {
            val id = CubieCubeConverter.identifyCorner(cube, i)
            if (id == null) errors += CubeError.ImpossibleCorner(i) else {
                cp[i] = id.first; co[i] = id.second
            }
        }
        for (i in 0 until 12) {
            val id = CubieCubeConverter.identifyEdge(cube, i)
            if (id == null) errors += CubeError.ImpossibleEdge(i) else {
                ep[i] = id.first; eo[i] = id.second
            }
        }

        for (j in 0 until 8) {
            val at = (0 until 8).filter { cp[it] == j }
            if (at.size > 1) errors += CubeError.DuplicateCorner(j, at)
        }
        for (j in 0 until 12) {
            val at = (0 until 12).filter { ep[it] == j }
            if (at.size > 1) errors += CubeError.DuplicateEdge(j, at)
        }

        if (errors.isNotEmpty()) return ValidationResult(errors, null)

        val cubie = CubieCube(cp, co, ep, eo)
        if (co.sum() % 3 != 0) errors += CubeError.TwistedCorner
        if (eo.sum() % 2 != 0) errors += CubeError.FlippedEdge
        if (cubie.cornerParity() != cubie.edgeParity()) errors += CubeError.Parity

        return ValidationResult(errors, if (errors.isEmpty()) cubie else null)
    }
}
