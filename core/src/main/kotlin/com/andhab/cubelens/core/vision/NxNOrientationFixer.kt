package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNChecks
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNModel
import com.andhab.cubelens.core.nxn.NxNValidator

/**
 * Finds face rotations that turn a mis-oriented N×N scan into a valid cube: [OrientationFixer] for
 * every size (2..10).
 *
 * Each face may have been captured turned by 0 to 3 quarter turns, so there are 4^6 = 4096 readings.
 * Validating 4096 big cubes one by one would take seconds, but validity splits along the pieces
 * ([NxNValidator]):
 *
 *  - Color counts, fixed centers and the movable centers do not depend on the face rotations: a face
 *    rotation keeps every center orbit on itself. They are checked once, up front.
 *  - Every other piece depends only on the rotations of the two or three faces it lies on. The
 *    search assigns rotations face by face (U, R, F, D, L, B) and rejects a partial assignment as
 *    soon as a completed corner, middle edge or wing is not a real piece or repeats one already
 *    seen. Odd sizes know the color arrangement from the fixed centers, so every piece is checked as
 *    it completes. Even sizes infer it from the corners: while searching, a completed corner must
 *    show three different colors and a color set no other corner has, and a completed wing two
 *    different colors in an order no other wing of its orbit has (a wing's ordered pair of colors
 *    names the piece, see [NxNModel]); at the end, the arrangement is read from the corners
 *    (opposite colors never share a corner; the corner at DBL is home, as [NxNValidator] holds the
 *    cube) and the corners and wings are checked against it.
 *  - Only the preferred reading is validated in full ([NxNValidator.validate]), as a safeguard: what
 *    it checks beyond the pieces above is rotation-independent.
 *
 * Rotations that leave a face's colors unchanged (a single-colored face, or a symmetric pattern) are
 * the same reading and searched once. The search typically visits a few hundred partial states, under
 * a few milliseconds even for a 7x7 cube.
 *
 * Preference and ambiguity are as in [OrientationFixer]: the fewest rotated faces wins, then the
 * fewest quarter turns (a three-quarter turn counts as one), then the first in search order; every
 * sticker whose color differs in another valid reading is reported as ambiguous.
 */
internal object NxNOrientationFixer {

    /** Outcome of [orient]: the preferred valid reading and where other valid readings disagree with it. */
    class Orientation(
        /** The preferred valid colors. */
        val colors: List<CubeColor>,
        /** Clockwise quarter turns applied to each face. */
        val rotations: Map<Face, Int>,
        /**
         * Stickers (indices into [colors]) whose color is different in at least one other valid
         * combination of face rotations; empty when the reading is unambiguous.
         */
        val ambiguous: Set<Int>,
        /** Number of distinct valid readings (1 when unambiguous). */
        val readings: Int,
    )

    /**
     * Rotates one face's [n]x[n] grid by [quarterTurns] clockwise quarter turns, in the face's own
     * row-major frame (looking at the face from outside the cube), like the face move of that face.
     */
    fun rotateFace(n: Int, colors: List<CubeColor>, face: Face, quarterTurns: Int): List<CubeColor> {
        val t = tables(n)
        require(colors.size == 6 * t.perFace) { "Need ${6 * t.perFace} colors, got ${colors.size}" }
        val k = Math.floorMod(quarterTurns, 4)
        if (k == 0) return colors.toList()
        val base = face.ordinal * t.perFace
        val source = t.rotation[k]
        return List(colors.size) { i ->
            if (i / t.perFace == face.ordinal) colors[base + source[i - base]] else colors[i]
        }
    }

    /** The index that the sticker at [index] moves to when its face is rotated by [quarterTurns] clockwise quarter turns. */
    fun destinationOf(n: Int, index: Int, quarterTurns: Int): Int {
        val t = tables(n)
        val base = index / t.perFace * t.perFace
        return base + t.destination[Math.floorMod(quarterTurns, 4)][index - base]
    }

    /**
     * Searches all combinations of face rotations of the [n]x[n] cube [colors] for valid cubes. Returns
     * the preferred one with the ambiguity analysis, or null if none is valid (or [colors] is not a
     * cube's worth of colors).
     */
    fun orient(n: Int, colors: List<CubeColor>): Orientation? {
        val t = tables(n)
        if (colors.size != 6 * t.perFace) return null
        val base = IntArray(colors.size) { colors[it].ordinal }
        if (!invariantsHold(t, base)) return null
        val search = Search(t, base)
        if (!search.prepare()) return null
        search.run(0, 0, 0, 0, 0, 0L)
        val best = search.best ?: return null
        val fixed = search.colorsOf(best)
        if (!NxNValidator.validate(NxNCube.of(n, fixed)).isValid) return null
        return Orientation(fixed, Face.entries.associateWith { best[it.ordinal] }, search.ambiguousStickers(best), search.validCount)
    }

    /**
     * The checks that no face rotation can change: every color [n]² times; odd sizes: six different
     * fixed centers; every orbit of movable centers holds each color four times.
     */
    private fun invariantsHold(t: Tables, colors: IntArray): Boolean {
        val counts = IntArray(6)
        for (c in colors) counts[c]++
        if (counts.any { it != t.perFace }) return false
        t.model.fixedCenters?.let { fixed -> if (fixed.map { colors[it] }.toSet().size != 6) return false }
        for (orbit in t.model.centerOrbits) {
            counts.fill(0)
            for (slot in orbit.slots) counts[colors[slot[0]]]++
            if (counts.any { it != orbit.size / 6 }) return false
        }
        return true
    }

    /** Per-size lookup tables. */
    private class Tables(val n: Int) {
        val perFace = n * n
        val model: NxNModel = NxNModel.of(n)

        /** rotation[k][p]: the position (row * n + col) whose sticker lands on p after k clockwise quarter turns. */
        val rotation: Array<IntArray> = Array(4) { k ->
            IntArray(perFace) { p ->
                var r = p / n
                var c = p % n
                // Clockwise: new(r, c) = old(n - 1 - c, r); apply k times.
                repeat(k) {
                    val nr = n - 1 - c
                    val nc = r
                    r = nr
                    c = nc
                }
                r * n + c
            }
        }

        /** destination[k][p]: where the sticker at position p goes under k clockwise quarter turns. */
        val destination: Array<IntArray> = Array(4) { k -> IntArray(perFace).also { d -> for (p in 0 until perFace) d[rotation[k][p]] = p } }

        private fun faceOf(sticker: Int) = sticker / perFace

        /** Corners whose stickers all lie on faces 0..f, with f the largest of their faces. */
        val cornersAt: Array<IntArray> = Array(6) { f -> (0 until 8).filter { i -> model.corners[i].maxOf { faceOf(it) } == f }.toIntArray() }

        /** Odd sizes: middle edges completed at each face, as [cornersAt]. */
        val midgesAt: Array<IntArray> = Array(6) { f ->
            model.midges?.let { midges -> (0 until 12).filter { i -> midges[i].maxOf { faceOf(it) } == f }.toIntArray() } ?: IntArray(0)
        }

        /** Wing slots completed at each face, encoded `orbit * 24 + slot`. */
        val wingsAt: Array<IntArray> = Array(6) { f ->
            model.wingOrbits.indices.flatMap { o ->
                model.wingOrbits[o].slots.indices.filter { s -> model.wingOrbits[o].slots[s].maxOf { faceOf(it) } == f }.map { o * WING_SLOTS + it }
            }.toIntArray()
        }
    }

    private const val WING_SLOTS = 24

    /** 3x3 edge cubie and flip by the faces of its two stickers, keyed `f0 * 6 + f1`: `cubie * 2 + flip`, or -1. */
    private val EDGE_LOOKUP: IntArray = IntArray(36) { -1 }.also { table ->
        for (j in 0 until 12) {
            val col = CubieCube.EDGE_COLOR[j]
            table[col[0].ordinal * 6 + col[1].ordinal] = j * 2
            table[col[1].ordinal * 6 + col[0].ordinal] = j * 2 + 1
        }
    }

    private val tableCache = arrayOfNulls<Tables>(NxNScanResolver.MAX_SIZE + 1)

    private fun tables(n: Int): Tables {
        require(n in NxNScanResolver.MIN_SIZE..NxNScanResolver.MAX_SIZE) { "Cube size must be in ${NxNScanResolver.MIN_SIZE}..${NxNScanResolver.MAX_SIZE}, got $n" }
        // Racing threads may both build the tables; either result is the same.
        return tableCache[n] ?: Tables(n).also { tableCache[n] = it }
    }

    /** The depth-first search over face rotations (see the class documentation). */
    private class Search(private val t: Tables, private val base: IntArray) {
        private val n = t.n
        private val perFace = t.perFace
        private val odd = n % 2 == 1
        private val model = t.model
        private val corners = model.corners
        private val wingOrbits = model.wingOrbits

        /** rotated[f][k]: face f's colors after k clockwise quarter turns. */
        private val rotated: Array<Array<IntArray>> = Array(6) { f ->
            Array(4) { k -> IntArray(perFace) { p -> base[f * perFace + t.rotation[k][p]] } }
        }

        /** Distinct readings of each face: the turns giving each distinct coloring, preferred one per coloring. */
        private val options: Array<IntArray> = Array(6) { f ->
            val reps = ArrayList<Int>()
            for (k in 0 until 4) {
                if (reps.none { rotated[f][it].contentEquals(rotated[f][k]) }) reps += k
            }
            reps.toIntArray()
        }

        private val current = IntArray(base.size)
        private val turns = IntArray(6)
        private val cp = IntArray(8)
        private val ep = IntArray(12)
        private val triple = IntArray(3)

        /**
         * Wings seen so far, per search depth and orbit: odd sizes, a bit per piece; even sizes, a bit
         * per ordered color pair (`first * 6 + second`), which names the piece whatever the arrangement.
         */
        private val usedWings = Array(7) { LongArray(wingOrbits.size) }

        // Odd sizes: the arrangement from the fixed centers.
        private val faceOfColor = IntArray(6)
        private val colorOfFace = IntArray(6)
        private var wingHome: Array<IntArray> = emptyArray()

        var best: IntArray? = null
            private set
        private var bestRotated = Int.MAX_VALUE
        private var bestTotal = Int.MAX_VALUE

        /** Every valid combination found, packed two bits per face. */
        private val valid = ArrayList<Int>()
        val validCount: Int get() = valid.size

        /** Sets up the arrangement for odd sizes; false if the fixed centers repeat a color. */
        fun prepare(): Boolean {
            val fixed = model.fixedCenters ?: return true
            faceOfColor.fill(-1)
            for (f in 0 until 6) {
                val c = base[fixed[f]]
                if (faceOfColor[c] >= 0) return false
                faceOfColor[c] = f
                colorOfFace[f] = c
            }
            wingHome = Array(wingOrbits.size) { NxNChecks.wingHomes(model, wingOrbits[it], colorOfFace) }
            return true
        }

        /** Corner piece (`cubie * 3 + twist`) at corner position [i] of [current] under [faceOfColor], or -1. */
        private fun cornerAt(i: Int, faceOfColor: IntArray): Int {
            val s = corners[i]
            triple[0] = current[s[0]]
            triple[1] = current[s[1]]
            triple[2] = current[s[2]]
            return NxNChecks.identifyCorner(triple, faceOfColor)
        }

        fun run(face: Int, usedCorners: Int, usedMidges: Int, twist: Int, flip: Int, cornerSets: Long) {
            if (face == 6) {
                if (odd) {
                    if (twist % 3 == 0 && flip % 2 == 0 && CubieCube.permutationParity(cp) == CubieCube.permutationParity(ep)) record()
                } else if (evenLeafIsValid()) {
                    record()
                }
                return
            }
            val offset = face * perFace
            for (k in options[face]) {
                rotated[face][k].copyInto(current, offset)
                turns[face] = k
                var cornersUsed = usedCorners
                var midgesUsed = usedMidges
                var tw = twist
                var fl = flip
                var sets = cornerSets
                var ok = true
                for (i in t.cornersAt[face]) {
                    if (odd) {
                        val id = cornerAt(i, faceOfColor)
                        if (id < 0 || cornersUsed and (1 shl (id / 3)) != 0) {
                            ok = false
                            break
                        }
                        cornersUsed = cornersUsed or (1 shl (id / 3))
                        cp[i] = id / 3
                        tw += id % 3
                    } else {
                        // Without the arrangement: three different colors, and a color set no other corner has.
                        val s = corners[i]
                        val a = current[s[0]]
                        val b = current[s[1]]
                        val c = current[s[2]]
                        val set = (1 shl a) or (1 shl b) or (1 shl c)
                        if (a == b || b == c || a == c || sets and (1L shl set) != 0L) {
                            ok = false
                            break
                        }
                        sets = sets or (1L shl set)
                    }
                }
                if (ok && odd) {
                    val midges = model.midges!!
                    for (i in t.midgesAt[face]) {
                        val id = EDGE_LOOKUP[faceOfColor[current[midges[i][0]]] * 6 + faceOfColor[current[midges[i][1]]]]
                        if (id < 0 || midgesUsed and (1 shl (id / 2)) != 0) {
                            ok = false
                            break
                        }
                        midgesUsed = midgesUsed or (1 shl (id / 2))
                        ep[i] = id / 2
                        fl += id % 2
                    }
                }
                if (ok) {
                    // Odd sizes: each wing must be a real piece not seen yet. Even sizes don't know the
                    // arrangement yet, but a wing shows two different colors, and its ordered pair of
                    // colors names the piece, so no pair may repeat within an orbit.
                    val used = usedWings[face + 1]
                    usedWings[face].copyInto(used)
                    for (w in t.wingsAt[face]) {
                        val o = w / WING_SLOTS
                        val slot = wingOrbits[o].slots[w % WING_SLOTS]
                        val first = current[slot[0]]
                        val second = current[slot[1]]
                        val bit = if (odd) wingHome[o][first * 6 + second] else if (first != second) first * 6 + second else -1
                        if (bit < 0 || used[o] and (1L shl bit) != 0L) {
                            ok = false
                            break
                        }
                        used[o] = used[o] or (1L shl bit)
                    }
                }
                if (ok) run(face + 1, cornersUsed, midgesUsed, tw, fl, sets)
            }
        }

        /**
         * Even sizes, all faces placed: reads the arrangement from the corners and checks the corners
         * and wings against it.
         */
        private fun evenLeafIsValid(): Boolean {
            // Opposite colors never share a corner; every other pair shares two.
            val adjacent = IntArray(6)
            for (s in corners) {
                val a = current[s[0]]
                val b = current[s[1]]
                val c = current[s[2]]
                adjacent[a] = adjacent[a] or (1 shl b) or (1 shl c)
                adjacent[b] = adjacent[b] or (1 shl a) or (1 shl c)
                adjacent[c] = adjacent[c] or (1 shl a) or (1 shl b)
            }
            val opposite = IntArray(6)
            for (c in 0 until 6) {
                val others = ALL_COLORS and adjacent[c].inv() and (1 shl c).inv()
                if (Integer.bitCount(others) != 1) return false
                opposite[c] = Integer.numberOfTrailingZeros(others)
            }
            for (c in 0 until 6) if (opposite[opposite[c]] != c) return false
            // Held with the corner at DBL home: its colors name D, B and L.
            val dbl = corners[CubieCube.DBL]
            val d = current[dbl[0]]
            val b = current[dbl[1]]
            val l = current[dbl[2]]
            val faceOf = IntArray(6)
            faceOf[d] = Face.D.ordinal
            faceOf[b] = Face.B.ordinal
            faceOf[l] = Face.L.ordinal
            faceOf[opposite[d]] = Face.U.ordinal
            faceOf[opposite[b]] = Face.F.ordinal
            faceOf[opposite[l]] = Face.R.ordinal
            var used = 0
            var twist = 0
            for (i in 0 until 8) {
                val id = cornerAt(i, faceOf)
                if (id < 0 || used and (1 shl (id / 3)) != 0) return false
                used = used or (1 shl (id / 3))
                twist += id % 3
            }
            if (twist % 3 != 0) return false
            val colorOf = IntArray(6).also { for (c in 0 until 6) it[faceOf[c]] = c }
            for (orbit in wingOrbits) {
                val home = NxNChecks.wingHomes(model, orbit, colorOf)
                var pieces = 0
                for (slot in orbit.slots) {
                    val piece = home[current[slot[0]] * 6 + current[slot[1]]]
                    if (piece < 0 || pieces and (1 shl piece) != 0) return false
                    pieces = pieces or (1 shl piece)
                }
            }
            return true
        }

        private fun record() {
            var packed = 0
            for (f in 0 until 6) packed = packed or (turns[f] shl (2 * f))
            valid += packed
            var rotatedFaces = 0
            var total = 0
            for (k in turns) {
                if (k != 0) rotatedFaces++
                total += minOf(k, 4 - k)
            }
            if (rotatedFaces < bestRotated || (rotatedFaces == bestRotated && total < bestTotal)) {
                bestRotated = rotatedFaces
                bestTotal = total
                best = turns.copyOf()
            }
        }

        /** The colors of the reading with face rotations [chosen]. */
        fun colorsOf(chosen: IntArray): List<CubeColor> {
            val out = IntArray(base.size)
            for (f in 0 until 6) rotated[f][chosen[f]].copyInto(out, f * perFace)
            return out.map { COLORS[it] }
        }

        /** Stickers (in the frame rotated by [chosen]) whose color differs between [chosen] and some other valid reading. */
        fun ambiguousStickers(chosen: IntArray): Set<Int> {
            val result = sortedSetOf<Int>()
            for (packed in valid) {
                for (f in 0 until 6) {
                    val k = (packed shr (2 * f)) and 3
                    if (k == chosen[f]) continue
                    val mine = rotated[f][chosen[f]]
                    val other = rotated[f][k]
                    for (p in 0 until perFace) if (mine[p] != other[p]) result += f * perFace + p
                }
            }
            return result
        }
    }

    private const val ALL_COLORS = 0x3F
    private val COLORS = CubeColor.entries
}
