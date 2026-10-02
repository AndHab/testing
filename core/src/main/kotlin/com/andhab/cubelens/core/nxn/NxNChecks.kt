package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face

/**
 * The checks behind [NxNValidator.validate].
 *
 * Colors are labels: the solved arrangement (which color belongs on which face) is read from the cube
 * itself. Odd sizes take it from the fixed centers. Even sizes have no fixed centers, so it is
 * inferred from the eight corner pieces: opposite colors never share a corner and the clockwise
 * order of the colors around a corner fixes the mirror image. Of the 24 ways to hold such a cube,
 * the one in which the corner at the DBL position is already home is used, so its three colors
 * define the D, B and L faces.
 *
 * With the arrangement known, the cube is real exactly when:
 *  - every color appears N² times;
 *  - the corners are eight different real pieces whose twists add up (sum % 3 == 0);
 *  - odd sizes: the middle edges are twelve different real pieces whose flips add up, and the
 *    corners and middle edges have the same permutation parity (they form a 3×3 cube);
 *  - every wing orbit holds each of its 24 pieces exactly once (a wing's handedness makes its
 *    two orders of colors different pieces, see [NxNModel]);
 *  - every center orbit holds each color exactly four times.
 */
internal object NxNChecks {

    /** All 720 assignments of the six colors to faces: `faceOfColor[color.ordinal] = face.ordinal`. */
    private val ALL_ASSIGNMENTS: List<IntArray> = permutations(6)

    /** Corner cubie for faces (U/D face first, then clockwise), keyed `f0 * 36 + f1 * 6 + f2`, or -1. */
    private val CORNER_BY_FACES = IntArray(216) { -1 }.also { table ->
        CubieCube.CORNER_COLOR.forEachIndexed { j, f -> table[f[0].ordinal * 36 + f[1].ordinal * 6 + f[2].ordinal] = j }
    }

    /** Edge cubie and flip for faces keyed `f0 * 6 + f1`: `cubie * 2 + flip`, or -1. */
    private val EDGE_BY_FACES = IntArray(36) { -1 }.also { table ->
        CubieCube.EDGE_COLOR.forEachIndexed { j, f ->
            table[f[0].ordinal * 6 + f[1].ordinal] = j * 2
            table[f[1].ordinal * 6 + f[0].ordinal] = j * 2 + 1
        }
    }

    private val OPPOSITE = IntArray(6) { Face.entries[it].opposite.ordinal }

    /**
     * Odd sizes: the fixed centers' color arrangement is trusted as long as it leaves at least this
     * many of the eight corner positions holding real pieces. A misread sticker spoils at most one
     * corner, so up to three bad corners are blamed on corner stickers (and flagged there), while
     * centers that contradict the corners spoil at least six of them: of the 720 arrangements,
     * the 24 whole-cube turns of the right one keep all eight corners real and every other one
     * keeps at most two. Four or more spoiled corners, when another arrangement explains more of
     * them, therefore point at the centers.
     */
    private const val MIN_REAL_CORNERS_TO_TRUST_CENTERS = 5

    fun validate(cube: NxNCube): NxNValidation {
        val n = cube.n
        val model = NxNModel.of(n)
        val colors = IntArray(model.stickerCount) { cube[it].ordinal }
        val errors = ArrayList<NxNError>()

        val counts = IntArray(6)
        for (c in colors) counts[c]++
        val present = (0 until 6).count { counts[it] > 0 }
        if (present < 6) {
            errors += NxNError("A cube has six different colors, but only $present were found.", problem = NxNProblem.MissingColors(present))
            return NxNValidation(errors, null)
        }
        for (c in 0 until 6) {
            if (counts[c] != n * n) {
                errors += NxNError(
                    "Found ${counts[c]} ${name(c)} stickers; a ${n}×$n cube has ${n * n} of each color.",
                    problem = NxNProblem.WrongCount(CubeColor.entries[c], counts[c], n * n),
                )
            }
        }

        val (faceOfColor, known) = chooseScheme(model, colors, errors)
        val colorOfFace = IntArray(6).also { for (c in 0 until 6) it[faceOfColor[c]] = c }

        val corners = checkCorners(model, colors, faceOfColor, colorOfFace, errors)
        val midges = model.midges?.let { checkMidges(it, colors, faceOfColor, colorOfFace, errors) }
        if (corners != null && midges != null &&
            CubieCube.permutationParity(corners) != CubieCube.permutationParity(midges)
        ) {
            errors += NxNError("Two pieces appear swapped. Check the sticker colors.", problem = NxNProblem.Swapped)
        }
        for (orbit in model.wingOrbits) checkWings(model, orbit, colors, faceOfColor, colorOfFace, errors)
        for (orbit in model.centerOrbits) checkCenters(orbit, colors, errors)

        val scheme = if (known) Face.entries.associateWith { CubeColor.entries[colorOfFace[it.ordinal]] } else null
        return NxNValidation(errors, scheme)
    }

    /**
     * Picks the color arrangement to check against: from the fixed centers for odd sizes (when
     * they are usable), else the arrangement that makes the most corner positions hold real pieces,
     * held with the DBL corner home for even sizes. Returns it and whether it is trustworthy.
     */
    private fun chooseScheme(model: NxNModel, colors: IntArray, errors: MutableList<NxNError>): Pair<IntArray, Boolean> {
        val cornerColors = model.corners.map { s -> IntArray(3) { colors[s[it]] } }
        val scores = IntArray(ALL_ASSIGNMENTS.size) { a -> cornerScore(cornerColors, ALL_ASSIGNMENTS[a]) }
        val best = scores.max()
        val bestAssignments = ALL_ASSIGNMENTS.indices.filter { scores[it] == best }.map { ALL_ASSIGNMENTS[it] }

        val fixed = model.fixedCenters
        if (fixed == null) {
            val dbl = cornerColors[CubieCube.DBL]
            val home = bestAssignments.firstOrNull {
                it[dbl[0]] == Face.D.ordinal && it[dbl[1]] == Face.B.ordinal && it[dbl[2]] == Face.L.ordinal
            }
            return (home ?: bestAssignments.first()) to (home != null)
        }

        val centerColors = IntArray(6) { colors[fixed[it]] }
        val closest = bestAssignments.maxBy { a -> (0 until 6).count { f -> a[centerColors[f]] == f } }
        if (centerColors.toSet().size < 6) {
            val repeated = (0 until 6).filter { f -> (0 until 6).count { centerColors[it] == centerColors[f] } > 1 }
            errors += NxNError("Each face needs a different center color.", repeated.map { fixed[it] }.toSet(), NxNProblem.CentersNotDistinct)
            return closest to false
        }
        val fromCenters = IntArray(6).also { for (f in 0 until 6) it[centerColors[f]] = f }
        val centerScore = cornerScore(cornerColors, fromCenters)
        if (centerScore < best && centerScore < MIN_REAL_CORNERS_TO_TRUST_CENTERS) {
            val wrong = (0 until 6).filter { f -> closest[centerColors[f]] != f }
            errors += NxNError("The center colors don't match the corner pieces.", wrong.map { fixed[it] }.toSet(), NxNProblem.CentersMismatch)
            return closest to false
        }
        return fromCenters to true
    }

    /** Number of corner positions holding a real corner piece under [faceOfColor]. */
    private fun cornerScore(cornerColors: List<IntArray>, faceOfColor: IntArray): Int =
        cornerColors.count { identifyCorner(it, faceOfColor) >= 0 }

    /** `cubie * 3 + twist` for a corner with [colors] (U/D sticker first, clockwise), or -1 if no such piece. */
    fun identifyCorner(colors: IntArray, faceOfColor: IntArray): Int {
        val f0 = faceOfColor[colors[0]]
        val f1 = faceOfColor[colors[1]]
        val f2 = faceOfColor[colors[2]]
        val twist = when {
            f0 == Face.U.ordinal || f0 == Face.D.ordinal -> 0
            f1 == Face.U.ordinal || f1 == Face.D.ordinal -> 1
            f2 == Face.U.ordinal || f2 == Face.D.ordinal -> 2
            else -> return -1
        }
        val key = when (twist) {
            0 -> f0 * 36 + f1 * 6 + f2
            1 -> f1 * 36 + f2 * 6 + f0
            else -> f2 * 36 + f0 * 6 + f1
        }
        val cubie = CORNER_BY_FACES[key]
        return if (cubie < 0) -1 else cubie * 3 + twist
    }

    /** Checks the corners; returns their permutation if all eight are distinct real pieces. */
    private fun checkCorners(
        model: NxNModel,
        colors: IntArray,
        faceOfColor: IntArray,
        colorOfFace: IntArray,
        errors: MutableList<NxNError>,
    ): IntArray? {
        val before = errors.size
        val ids = IntArray(8) { -1 }
        for (i in 0 until 8) {
            val stickers = model.corners[i]
            val c = IntArray(3) { colors[stickers[it]] }
            val flagged = stickers.toSet()
            when {
                c[0] == c[1] || c[1] == c[2] || c[0] == c[2] -> {
                    val twice = if (c[0] == c[1] || c[0] == c[2]) c[0] else c[1]
                    errors += NxNError("This corner shows ${name(twice)} twice.", flagged, ImpossibleCorner)
                }
                oppositePair(c, faceOfColor) != null -> {
                    val (x, y) = oppositePair(c, faceOfColor)!!
                    errors += NxNError("This corner has ${name(x)} and ${name(y)}, which belong on opposite sides.", flagged, ImpossibleCorner)
                }
                else -> {
                    val id = identifyCorner(c, faceOfColor)
                    if (id < 0) {
                        errors += NxNError("This corner's colors are in an impossible order (a mirror image).", flagged, ImpossibleCorner)
                    } else {
                        ids[i] = id
                    }
                }
            }
        }
        for (cubie in 0 until 8) {
            val at = (0 until 8).filter { ids[it] >= 0 && ids[it] / 3 == cubie }
            if (at.size > 1) {
                val label = CubieCube.CORNER_COLOR[cubie].joinToString("-") { name(colorOfFace[it.ordinal]) }
                val stickers = at.flatMap { model.corners[it].toList() }.toSet()
                errors += NxNError("The $label corner appears ${at.size} times.", stickers, NxNProblem.DuplicatePiece(NxNProblem.Piece.CORNER, at.size))
            }
        }
        if (errors.size != before) return null
        if (ids.sumOf { it % 3 } % 3 != 0) {
            errors += NxNError("One corner looks twisted. Check the corner sticker colors.", problem = NxNProblem.TwistedCorner)
            return null
        }
        return IntArray(8) { ids[it] / 3 }
    }

    /** Checks the middle edges of an odd cube; returns their permutation if all is well. */
    private fun checkMidges(
        midges: List<IntArray>,
        colors: IntArray,
        faceOfColor: IntArray,
        colorOfFace: IntArray,
        errors: MutableList<NxNError>,
    ): IntArray? {
        val before = errors.size
        val ids = IntArray(12) { -1 }
        for (i in 0 until 12) {
            val (a, b) = midges[i]
            ids[i] = checkEdgePiece(colors[a], colors[b], setOf(a, b), NxNProblem.Piece.EDGE, faceOfColor, errors)
        }
        for (cubie in 0 until 12) {
            val at = (0 until 12).filter { ids[it] >= 0 && ids[it] / 2 == cubie }
            if (at.size > 1) {
                val label = CubieCube.EDGE_COLOR[cubie].joinToString("-") { name(colorOfFace[it.ordinal]) }
                errors += NxNError(
                    "The $label edge appears ${at.size} times.",
                    at.flatMap { midges[it].toList() }.toSet(),
                    NxNProblem.DuplicatePiece(NxNProblem.Piece.EDGE, at.size),
                )
            }
        }
        if (errors.size != before) return null
        if (ids.sumOf { it % 2 } % 2 != 0) {
            errors += NxNError("One edge looks flipped. Check the edge sticker colors.", problem = NxNProblem.FlippedEdge)
            return null
        }
        return IntArray(12) { ids[it] / 2 }
    }

    /**
     * Checks one two-colored piece; returns `cubie * 2 + flip` of the matching 3×3 edge, or -1
     * after adding an error.
     */
    private fun checkEdgePiece(
        x: Int,
        y: Int,
        stickers: Set<Int>,
        piece: NxNProblem.Piece,
        faceOfColor: IntArray,
        errors: MutableList<NxNError>,
    ): Int {
        val what = if (piece == NxNProblem.Piece.EDGE) "edge" else "edge piece"
        if (x == y) {
            errors += NxNError("This $what shows ${name(x)} twice.", stickers, NxNProblem.ImpossiblePiece(piece))
            return -1
        }
        if (OPPOSITE[faceOfColor[x]] == faceOfColor[y]) {
            errors += NxNError("This $what has ${name(x)} and ${name(y)}, which belong on opposite sides.", stickers, NxNProblem.ImpossiblePiece(piece))
            return -1
        }
        return EDGE_BY_FACES[faceOfColor[x] * 6 + faceOfColor[y]]
    }

    private fun checkWings(
        model: NxNModel,
        orbit: Orbit,
        colors: IntArray,
        faceOfColor: IntArray,
        colorOfFace: IntArray,
        errors: MutableList<NxNError>,
    ) {
        val home = wingHomes(model, orbit, colorOfFace)
        val at = Array(orbit.size) { ArrayList<Int>() }
        for (s in 0 until orbit.size) {
            val (a, b) = orbit.slots[s]
            if (checkEdgePiece(colors[a], colors[b], setOf(a, b), NxNProblem.Piece.WING, faceOfColor, errors) >= 0) {
                at[home[colors[a] * 6 + colors[b]]] += s
            }
        }
        for (piece in 0 until orbit.size) {
            if (at[piece].size > 1) {
                val (a, b) = orbit.slots[piece]
                val first = colorOfFace[model.geometry.faceOf(a).ordinal]
                val second = colorOfFace[model.geometry.faceOf(b).ordinal]
                val label = "${name(first)}-${name(second)}"
                errors += NxNError(
                    "This $label edge piece appears ${at[piece].size} times; one of them is probably mirrored or misread.",
                    at[piece].flatMap { orbit.slots[it].toList() }.toSet(),
                    NxNProblem.DuplicatePiece(NxNProblem.Piece.WING, at[piece].size),
                )
            }
        }
    }

    /** Home slot of each wing of [orbit], keyed by its ordered colors `first * 6 + second`. */
    fun wingHomes(model: NxNModel, orbit: Orbit, colorOfFace: IntArray): IntArray {
        val home = IntArray(36) { -1 }
        orbit.slots.forEachIndexed { s, (a, b) ->
            home[colorOfFace[model.geometry.faceOf(a).ordinal] * 6 + colorOfFace[model.geometry.faceOf(b).ordinal]] = s
        }
        return home
    }

    private fun checkCenters(orbit: Orbit, colors: IntArray, errors: MutableList<NxNError>) {
        val counts = IntArray(6)
        for (slot in orbit.slots) counts[colors[slot[0]]]++
        val perColor = orbit.size / 6
        if (counts.all { it == perColor }) return
        val wrong = (0 until 6).filter { counts[it] != perColor }.sortedByDescending { counts[it] }
        val summary = wrong.joinToString(" and ") { "${counts[it]} ${name(it)}" }
        val flagged = orbit.slots.map { it[0] }.filter { counts[colors[it]] > perColor }.toSet()
        errors += NxNError(
            "These center pieces don't add up: there are $summary where there should be $perColor of each.",
            flagged,
            NxNProblem.CentersDontAddUp,
        )
    }

    /** Two colors of [c] that belong on opposite faces, if any. */
    private fun oppositePair(c: IntArray, faceOfColor: IntArray): Pair<Int, Int>? {
        for (i in 0 until 3) for (j in i + 1 until 3) {
            if (OPPOSITE[faceOfColor[c[i]]] == faceOfColor[c[j]]) return c[i] to c[j]
        }
        return null
    }

    private fun name(color: Int): String = CubeColor.entries[color].displayName.lowercase()

    private val ImpossibleCorner = NxNProblem.ImpossiblePiece(NxNProblem.Piece.CORNER)

    private fun permutations(k: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        fun rec(prefix: IntArray, used: BooleanArray, depth: Int) {
            if (depth == k) {
                out += prefix.copyOf()
                return
            }
            for (v in 0 until k) {
                if (used[v]) continue
                used[v] = true
                prefix[depth] = v
                rec(prefix, used, depth + 1)
                used[v] = false
            }
        }
        rec(IntArray(k), BooleanArray(k), 0)
        return out
    }
}
