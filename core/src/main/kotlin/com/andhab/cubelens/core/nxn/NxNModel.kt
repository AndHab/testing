package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the validator and the solvers need to know about one cube size, computed once per
 * size and shared (all members are immutable after construction, so instances are thread-safe):
 *
 *  - move codes with their sticker permutations ([NxNModel.codeCount] codes);
 *  - the 3×3 "frame" (corners, middle edges and fixed centers) in Kociemba order;
 *  - the orbits of wings and movable centers, each with 24 piece slots.
 *
 * ## Move codes
 * Layers are counted along three axes from the faces U, R and F ([AXIS_FACES]): layer 0 is the
 * outer U/R/F layer, layer `n - 1` the outer D/L/B layer, and turns are always clockwise as seen
 * from the U/R/F side. Codes `0 until 9n` ([singleCodeCount]) are quarter, half and three-quarter
 * turns of one single layer; every [LayerMove] is a product of commuting single-layer codes on one
 * axis. Even sizes have nine more codes: the turns of layers `0..n-2` of each axis (`3Uw` on a
 * 4×4). Such a turn is an outer D, L or B turn followed by a whole-cube rotation, so it gives the
 * solver the power of those turns without moving the DBL corner.
 *
 * ## Wing handedness
 * A wing can never be flipped in place: whatever the moves, the two stickers of a wing slot are
 * always carried to the two stickers of another slot in a fixed order. The slots of a wing orbit
 * are stored with their stickers in that order (derived from the moves themselves, see
 * [buildWingOrbit]), so the ordered pair of colors in a slot names the piece sitting there, and the
 * two wings of one edge (positions k and N-1-k) are the two orders of the same color pair.
 */
internal class NxNModel private constructor(val n: Int) {

    val geometry: NxNGeometry = NxNGeometry.of(n)
    val stickerCount: Int = geometry.stickerCount

    // ---------------------------------------------------------------------------------------------
    // Move codes

    /** Number of single-layer move codes: 3 axes × n layers × 3 turn amounts. */
    val singleCodeCount: Int = 9 * n

    /** Number of move codes: the single-layer ones, plus the `0..n-2` block turns for even sizes. */
    val codeCount: Int = singleCodeCount + if (n % 2 == 0) 9 else 0

    /** The single-layer code turning [layer] of [axis] by [turns] quarter turns. */
    fun code(axis: Int, layer: Int, turns: Int): Int = (axis * n + layer) * 3 + (turns - 1)

    /** Even sizes: the code turning layers `0..n-2` of [axis] by [turns] quarter turns. */
    fun blockCode(axis: Int, turns: Int): Int {
        check(n % 2 == 0) { "Only even sizes have block codes" }
        return singleCodeCount + axis * 3 + (turns - 1)
    }

    fun isSingle(code: Int): Boolean = code < singleCodeCount
    fun axisOf(code: Int): Int = if (isSingle(code)) code / (3 * n) else (code - singleCodeCount) / 3
    fun fromLayer(code: Int): Int = if (isSingle(code)) (code / 3) % n else 0
    fun toLayer(code: Int): Int = if (isSingle(code)) (code / 3) % n else n - 2
    fun turnsOf(code: Int): Int = code % 3 + 1

    /** The code undoing [code]: same layers, opposite direction (half turns are their own inverse). */
    fun inverse(code: Int): Int = code - code % 3 + (2 - code % 3)

    /** The [LayerMove] of a code (always written from the U, R or F face). */
    fun layerMove(code: Int): LayerMove =
        LayerMove(AXIS_FACES[axisOf(code)], fromLayer(code) + 1, toLayer(code) + 1, turnsOf(code))

    /** `perm(code)[i]` = the sticker position whose content moves to `i` (as [NxNGeometry.permutation]). */
    private val perms: Array<IntArray> = Array(codeCount) { geometry.permutation(layerMove(it)) }

    /** `dest(code)[i]` = where the sticker at position `i` goes. */
    private val dests: Array<IntArray> = Array(codeCount) { c ->
        val p = perms[c]
        IntArray(stickerCount).also { d -> for (i in p.indices) d[p[i]] = i }
    }

    fun perm(code: Int): IntArray = perms[code]
    fun dest(code: Int): IntArray = dests[code]

    /** Stickers moved by each code, as a bit set over sticker indices. */
    val supportBits: Array<LongArray> = Array(codeCount) { c ->
        val p = perms[c]
        LongArray((stickerCount + 63) / 64).also { bits ->
            for (i in p.indices) if (p[i] != i) bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
        }
    }

    /** Whether [code] leaves the DBL corner in place, i.e. does not turn the outer D, L or B layer. */
    fun keepsDblCorner(code: Int): Boolean = toLayer(code) != n - 1

    // ---------------------------------------------------------------------------------------------
    // The 3×3 frame

    /**
     * The sticker of this cube that plays the role of 3×3 facelet [facelet]. Middle rows and
     * columns exist only for odd sizes.
     */
    fun frameSticker(facelet: Int): Int =
        geometry.index(Facelets.faceOf(facelet), frameLine(Facelets.rowOf(facelet)), frameLine(Facelets.colOf(facelet)))

    private fun frameLine(k: Int): Int = when (k) {
        0 -> 0
        2 -> n - 1
        else -> {
            require(n % 2 == 1) { "A ${n}x$n cube has no middle row" }
            n / 2
        }
    }

    /** Stickers of each corner position (Kociemba order URF..DRB), U/D sticker first, then clockwise. */
    val corners: List<IntArray> = CubieCube.CORNER_FACELET.map { f -> IntArray(3) { frameSticker(f[it]) } }

    /** Odd sizes: stickers of each middle-edge position (Kociemba order UR..BR); null for even sizes. */
    val midges: List<IntArray>? =
        if (n % 2 == 1) CubieCube.EDGE_FACELET.map { f -> IntArray(2) { frameSticker(f[it]) } } else null

    /** Odd sizes: the fixed center sticker of each face (in [Face] order); null for even sizes. */
    val fixedCenters: IntArray? =
        if (n % 2 == 1) IntArray(6) { frameSticker(Facelets.center(Face.entries[it])) } else null

    // ---------------------------------------------------------------------------------------------
    // Orbits

    /** Wing orbits (N >= 4), ordered by [Orbit.depth]: the wings next to the corners first. */
    val wingOrbits: List<Orbit>

    /** Orbits of movable centers (N >= 4, 24 stickers each), ordered by their smallest sticker index. */
    val centerOrbits: List<Orbit>

    /** For every sticker: index into [wingOrbits] or [centerOrbits] (by kind), or -1. */
    private val orbitOfSticker = IntArray(stickerCount) { -1 }

    init {
        val quarterTurns = (0 until 3).flatMap { axis -> (0 until n).map { code(axis, it, 1) } }
        val wingStickers = (0 until stickerCount).filter { geometry.kindOf(it) == PieceKind.WING }
        val centerStickers = (0 until stickerCount).filter { geometry.kindOf(it) == PieceKind.CENTER }
        val cubieOf = IntArray(stickerCount)
        val cubieStickers = geometry.cubies
        cubieStickers.forEachIndexed { index, stickers -> stickers.forEach { cubieOf[it] = index } }
        // Handedness keeps the "first" and "second" stickers of wings in separate sticker classes,
        // so a wing orbit is the union of the classes of a wing's two stickers.
        val partner = IntArray(stickerCount) { s -> cubieStickers[cubieOf[s]].firstOrNull { it != s } ?: s }
        val wingClasses = stickerClasses(wingStickers, quarterTurns, partner)
        val centerClasses = stickerClasses(centerStickers, quarterTurns, null)

        wingOrbits = wingClasses
            .map { buildWingOrbit(it, cubieStickers, cubieOf, quarterTurns) }
            .sortedBy { it.depth }
        centerOrbits = centerClasses
            .map { stickers ->
                orbit(PieceKind.CENTER, stickers.sorted().map { intArrayOf(it) }, centerDepth(stickers.min()))
            }
            .sortedBy { it.slots[0][0] }
        wingOrbits.forEachIndexed { o, orbit -> orbit.slots.forEach { s -> s.forEach { orbitOfSticker[it] = o } } }
        centerOrbits.forEachIndexed { o, orbit -> orbit.slots.forEach { s -> s.forEach { orbitOfSticker[it] = o } } }
    }

    /** An [Orbit] with its slot lookup and the slot permutation of every move code. */
    private fun orbit(kind: PieceKind, slots: List<IntArray>, depth: Int): Orbit {
        val slotOfSticker = IntArray(stickerCount) { -1 }
        slots.forEachIndexed { index, stickers -> stickers.forEach { slotOfSticker[it] = index } }
        val slotDest = Array(codeCount) { code ->
            val d = dests[code]
            IntArray(slots.size) { s ->
                // Every sticker of the slot must land in the same slot, in the same order.
                val target = slotOfSticker[d[slots[s][0]]]
                check(target >= 0 && slots[s].indices.all { slots[target][it] == d[slots[s][it]] }) {
                    "Move ${layerMove(code)} breaks the orbit structure of the ${n}x$n cube"
                }
                target
            }
        }
        return Orbit(kind, slots, depth, slotOfSticker, slotDest)
    }

    /** Index of the wing or center orbit containing [sticker], or -1 for corners, midges and fixed centers. */
    fun orbitIndexOf(sticker: Int): Int = orbitOfSticker[sticker]

    /**
     * Splits [stickers] into the classes reachable from each other with [generators] (and, when
     * given, by going from a sticker to its [partner] on the same piece).
     */
    private fun stickerClasses(stickers: List<Int>, generators: List<Int>, partner: IntArray?): List<List<Int>> {
        val seen = BooleanArray(stickerCount)
        val classes = ArrayList<List<Int>>()
        for (start in stickers) {
            if (seen[start]) continue
            val members = ArrayList<Int>()
            val queue = ArrayDeque(listOf(start))
            seen[start] = true
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                members += s
                for (g in generators) {
                    val t = dests[g][s]
                    if (!seen[t]) {
                        seen[t] = true
                        queue += t
                    }
                }
                if (partner != null && !seen[partner[s]]) {
                    seen[partner[s]] = true
                    queue += partner[s]
                }
            }
            classes += members
        }
        return classes
    }

    /**
     * Groups the stickers of one wing orbit into slots and orders each slot's two stickers by
     * handedness: starting from one slot in an arbitrary order, every move carries an ordered slot to
     * an ordered slot. The orbit is consistent only if no sequence of moves can bring a slot back to
     * itself in the opposite order, i.e. if wings cannot be flipped in place; that is checked here.
     */
    private fun buildWingOrbit(
        stickers: List<Int>,
        cubies: List<List<Int>>,
        cubieOf: IntArray,
        generators: List<Int>,
    ): Orbit {
        val first = IntArray(stickerCount) { -1 } // sticker -> the first sticker of its ordered slot
        val pairOf = IntArray(stickerCount) { -1 }
        for (s in stickers) pairOf[s] = cubies[cubieOf[s]].first { it != s }
        val start = stickers.min()
        first[start] = start
        first[pairOf[start]] = start
        val queue = ArrayDeque(listOf(start to pairOf[start]))
        val ordered = ArrayList<IntArray>()
        while (queue.isNotEmpty()) {
            val (a, b) = queue.removeFirst()
            ordered += intArrayOf(a, b)
            for (g in generators) {
                val ta = dests[g][a]
                val tb = dests[g][b]
                check(pairOf[ta] == tb) { "Move does not keep wing stickers together" }
                if (first[ta] == -1) {
                    first[ta] = ta
                    first[tb] = ta
                    queue += ta to tb
                } else {
                    check(first[ta] == ta) { "A wing of the ${n}x$n cube could be flipped in place" }
                }
            }
        }
        val slots = ordered.sortedBy { minOf(it[0], it[1]) }
        // Position of the wing along its edge (0 and n-1 are the corners).
        val row = geometry.rowOf(start)
        val position = if (row == 0 || row == n - 1) geometry.colOf(start) else row
        return orbit(PieceKind.WING, slots, minOf(position, n - 1 - position))
    }

    private fun centerDepth(sticker: Int): Int {
        val r = geometry.rowOf(sticker)
        val c = geometry.colOf(sticker)
        return minOf(minOf(r, n - 1 - r), minOf(c, n - 1 - c))
    }

    companion object {
        /** The face each axis is measured from (and whose clockwise direction its turns use). */
        val AXIS_FACES: Array<Face> = arrayOf(Face.U, Face.R, Face.F)

        fun axisOf(face: Face): Int = when (face) {
            Face.U, Face.D -> 0
            Face.R, Face.L -> 1
            Face.F, Face.B -> 2
        }

        private val cache = ConcurrentHashMap<Int, NxNModel>()

        fun of(n: Int): NxNModel {
            NxNGeometry.of(n) // validates the size
            return cache.computeIfAbsent(n) { NxNModel(it) }
        }
    }
}

/**
 * One orbit of wings or movable centers: the 24 slots that pieces of this kind can occupy. Each
 * slot lists its stickers (one for a center; two for a wing, in handedness order, see [NxNModel]).
 * [depth] is the distance from the cube's edge (wings: from the nearest corner along the edge;
 * centers: from the nearest face border).
 */
internal class Orbit(
    val kind: PieceKind,
    val slots: List<IntArray>,
    val depth: Int,
    private val slotOfSticker: IntArray,
    /** `slotDest[code][s]` = the slot the piece in slot `s` moves to under move code `code`. */
    val slotDest: Array<IntArray>,
) {
    init {
        check(slots.size == CycleLibrary.SLOTS) { "An orbit has ${CycleLibrary.SLOTS} slots, found ${slots.size}" }
    }

    val size: Int get() = slots.size

    /** The slot containing [sticker], or -1 if it is not in this orbit. */
    fun slotOf(sticker: Int): Int = slotOfSticker[sticker]
}
