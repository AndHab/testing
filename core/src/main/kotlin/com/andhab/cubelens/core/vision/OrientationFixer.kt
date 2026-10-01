package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeValidator
import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets

/**
 * Finds face rotations that turn a mis-oriented scan into a valid cube.
 *
 * Users hold the cube at any angle while scanning a face, so each face's 3x3 grid may be off by a
 * quarter, half or three-quarter turn. Centers don't move under such a rotation, and usually exactly
 * one combination of the 4^6 face rotations yields a real, solvable cube.
 *
 * Sometimes a second combination is valid too, and colors alone cannot tell them apart: about 1 in
 * 150 random scrambles scanned at random angles, and almost every cube scrambled with half turns
 * only (each face then shows just two opposite colors). The reading needing the least correction
 * wins (fewest rotated faces, then fewest quarter turns), which is always right when every face was
 * scanned in the reference orientation of [Facelets] (standard color scheme):
 *
 *  - side faces (F green, R red, B blue, L orange): U (white) at the top of the screen;
 *  - U (white): B (blue) at the top of the screen, F (green) at the bottom;
 *  - D (yellow): F (green) at the top of the screen, B (blue) at the bottom.
 *
 * Scan flows should ask for exactly this. [ScanResolver] additionally reports every sticker whose
 * color differs between the chosen and another valid reading as uncertain, so a face held at an
 * unexpected angle is flagged for review instead of silently producing the wrong cube.
 */
object OrientationFixer {

    /**
     * Rotates one face's 3x3 grid by [quarterTurns] clockwise quarter turns (center unchanged).
     *
     * The rotation is in the face's own row-major frame as listed in [Facelets] (looking at the face
     * from outside the cube), so one clockwise quarter turn moves its stickers exactly like the face
     * move of the same face does. Negative values turn counter-clockwise.
     */
    fun rotateFace(colors: List<CubeColor>, face: Face, quarterTurns: Int): List<CubeColor> {
        require(colors.size == Facelets.COUNT) { "Need 54 colors, got ${colors.size}" }
        val k = Math.floorMod(quarterTurns, 4)
        if (k == 0) return colors.toList()
        val base = face.ordinal * 9
        val source = ROTATION[k]
        return List(Facelets.COUNT) { i ->
            if (i / 9 == face.ordinal) colors[base + source[i - base]] else colors[i]
        }
    }

    /**
     * Searches all combinations of face rotations for a valid cube, preferring the fewest rotated
     * faces. Returns the fixed colors and the rotation applied to each face, or null if none is valid.
     *
     * Ties are broken by the smallest total rotation (a three-quarter turn counts as one quarter
     * turn the other way), then by enumeration order. Other valid combinations, if any, are not
     * reported here; see [ScanResolver] for how they surface as uncertain stickers.
     */
    fun fix(colors: List<CubeColor>): Pair<List<CubeColor>, Map<Face, Int>>? =
        orient(colors)?.let { it.colors to it.rotations }

    /** Outcome of [orient]: the preferred valid reading and where other valid readings disagree with it. */
    internal class Orientation(
        /** The preferred valid colors (what [fix] returns). */
        val colors: List<CubeColor>,
        /** Clockwise quarter turns applied to each face (what [fix] returns). */
        val rotations: Map<Face, Int>,
        /**
         * Facelets (indices into [colors]) whose color is different in at least one other valid
         * combination of face rotations; empty when the reading is unambiguous. Faces of a single
         * color, or symmetric ones, can be valid at several rotations without changing any color:
         * those are not ambiguous.
         */
        val ambiguous: Set<Int>,
    )

    /**
     * [fix] plus the ambiguity analysis. The search is a depth-first enumeration over the faces that
     * rejects a partial combination as soon as a completed corner or edge is not a real piece or
     * repeats one already seen, so it usually inspects only a few hundred states.
     */
    internal fun orient(colors: List<CubeColor>): Orientation? {
        require(colors.size == Facelets.COUNT) { "Need 54 colors, got ${colors.size}" }
        val scheme = FaceletCube.schemeOf(colors) ?: return null
        val base = IntArray(Facelets.COUNT) { scheme.faceOf(colors[it]).ordinal }
        val search = Search(base)
        search.run(0, 0, 0, 0, 0)
        val turns = search.best ?: return null
        var fixed = colors.toList()
        for (face in Face.entries) fixed = rotateFace(fixed, face, turns[face.ordinal])
        if (!CubeValidator.validate(fixed).isValid) return null
        return Orientation(fixed, Face.entries.associateWith { turns[it.ordinal] }, search.ambiguousFacelets(turns))
    }

    /**
     * The facelet index that the sticker at [index] moves to when its face is rotated by
     * [quarterTurns] clockwise quarter turns with [rotateFace].
     */
    internal fun destinationOf(index: Int, quarterTurns: Int): Int {
        val k = Math.floorMod(quarterTurns, 4)
        val base = index / 9 * 9
        val source = ROTATION[k]
        for (p in 0 until 9) if (source[p] == index - base) return base + p
        error("Unreachable: ROTATION is a permutation")
    }

    /** ROTATION[k][p]: the position (row * 3 + col) whose sticker lands on p after k clockwise quarter turns. */
    private val ROTATION: Array<IntArray> = Array(4) { k ->
        IntArray(9) { p ->
            var r = p / 3
            var c = p % 3
            // Clockwise: new(r, c) = old(2 - c, r); apply k times.
            repeat(k) {
                val nr = 2 - c
                val nc = r
                r = nr
                c = nc
            }
            r * 3 + c
        }
    }

    private val CORNER_LOOKUP: IntArray = IntArray(216) { -1 }.also { table ->
        for (j in 0 until 8) {
            val col = CubieCube.CORNER_COLOR[j]
            for (twist in 0 until 3) {
                val faces = IntArray(3)
                for (n in 0 until 3) faces[(twist + n) % 3] = col[n].ordinal
                table[faces[0] * 36 + faces[1] * 6 + faces[2]] = j * 3 + twist
            }
        }
    }

    private val EDGE_LOOKUP: IntArray = IntArray(36) { -1 }.also { table ->
        for (j in 0 until 12) {
            val col = CubieCube.EDGE_COLOR[j]
            table[col[0].ordinal * 6 + col[1].ordinal] = j * 2
            table[col[1].ordinal * 6 + col[0].ordinal] = j * 2 + 1
        }
    }

    /** Corners / edges whose facelets all lie on faces 0..f, with f the largest of their faces. */
    private val CORNERS_COMPLETED_AT: Array<IntArray> = Array(6) { f ->
        (0 until 8).filter { i -> CubieCube.CORNER_FACELET[i].maxOf { it / 9 } == f }.toIntArray()
    }
    private val EDGES_COMPLETED_AT: Array<IntArray> = Array(6) { f ->
        (0 until 12).filter { i -> CubieCube.EDGE_FACELET[i].maxOf { it / 9 } == f }.toIntArray()
    }

    private class Search(private val base: IntArray) {
        private val current = IntArray(Facelets.COUNT)
        private val turns = IntArray(6)
        private val cp = IntArray(8)
        private val ep = IntArray(12)
        var best: IntArray? = null
        private var bestRotated = Int.MAX_VALUE
        private var bestTotal = Int.MAX_VALUE

        /** Every valid combination found, packed two bits per face (face f in bits 2f..2f+1). */
        private val valid = IntArray(4096)
        private var validCount = 0

        fun run(face: Int, usedCorners: Int, usedEdges: Int, twist: Int, flip: Int) {
            if (face == 6) {
                if (twist % 3 == 0 && flip % 2 == 0 &&
                    CubieCube.permutationParity(cp) == CubieCube.permutationParity(ep)
                ) {
                    record()
                }
                return
            }
            val offset = face * 9
            for (k in 0 until 4) {
                val source = ROTATION[k]
                for (p in 0 until 9) current[offset + p] = base[offset + source[p]]
                turns[face] = k
                var corners = usedCorners
                var edges = usedEdges
                var tw = twist
                var fl = flip
                var ok = true
                for (i in CORNERS_COMPLETED_AT[face]) {
                    val fl3 = CubieCube.CORNER_FACELET[i]
                    val id = CORNER_LOOKUP[current[fl3[0]] * 36 + current[fl3[1]] * 6 + current[fl3[2]]]
                    if (id < 0 || corners and (1 shl (id / 3)) != 0) {
                        ok = false
                        break
                    }
                    corners = corners or (1 shl (id / 3))
                    cp[i] = id / 3
                    tw += id % 3
                }
                if (ok) {
                    for (i in EDGES_COMPLETED_AT[face]) {
                        val fl2 = CubieCube.EDGE_FACELET[i]
                        val id = EDGE_LOOKUP[current[fl2[0]] * 6 + current[fl2[1]]]
                        if (id < 0 || edges and (1 shl (id / 2)) != 0) {
                            ok = false
                            break
                        }
                        edges = edges or (1 shl (id / 2))
                        ep[i] = id / 2
                        fl += id % 2
                    }
                }
                if (ok) run(face + 1, corners, edges, tw, fl)
            }
        }

        private fun record() {
            var packed = 0
            for (f in 0 until 6) packed = packed or (turns[f] shl (2 * f))
            valid[validCount++] = packed
            var rotated = 0
            var total = 0
            for (k in turns) {
                if (k != 0) rotated++
                total += minOf(k, 4 - k)
            }
            if (rotated < bestRotated || (rotated == bestRotated && total < bestTotal)) {
                bestRotated = rotated
                bestTotal = total
                best = turns.copyOf()
            }
        }

        /**
         * Facelets (in the frame rotated by [chosen]) whose color differs between [chosen] and some
         * other valid combination.
         */
        fun ambiguousFacelets(chosen: IntArray): Set<Int> {
            val result = sortedSetOf<Int>()
            for (n in 0 until validCount) {
                val packed = valid[n]
                for (f in 0 until 6) {
                    val k = (packed shr (2 * f)) and 3
                    if (k == chosen[f]) continue
                    val mine = ROTATION[chosen[f]]
                    val other = ROTATION[k]
                    val offset = f * 9
                    for (p in 0 until 9) {
                        if (base[offset + mine[p]] != base[offset + other[p]]) result += offset + p
                    }
                }
            }
            return result
        }
    }
}
