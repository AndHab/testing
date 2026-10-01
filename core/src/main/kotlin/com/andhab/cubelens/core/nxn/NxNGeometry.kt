package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Vec3
import java.util.concurrent.ConcurrentHashMap

/**
 * Sticker indexing and 3D geometry of an N×N×N cube, generalizing [Facelets] (which is the N = 3 case).
 *
 * Stickers are numbered face by face in the order U, R, F, D, L, B, each face row-major with N×N
 * stickers, read the same way as the 3×3 layout documented in [Facelets]: F, R, B, L with U on
 * top; U with B at the top of the image; D with F at the top of the image.
 *
 * Coordinates are doubled so they stay integral for every N: along each axis a layer sits at
 * `2k - (N - 1)` for k in 0 until N, so the outer layers are at ±(N - 1). x points right (R),
 * y up (U), z toward the viewer (F). For N = 3 positions are exactly twice those of [Facelets].
 */
class NxNGeometry private constructor(val n: Int) {

    val stickersPerFace: Int = n * n
    val stickerCount: Int = 6 * n * n

    fun index(face: Face, row: Int, col: Int): Int {
        require(row in 0 until n && col in 0 until n) { "Row/col out of range for ${n}x$n: $row,$col" }
        return face.ordinal * stickersPerFace + row * n + col
    }

    fun faceOf(index: Int): Face = Face.entries[index / stickersPerFace]
    fun rowOf(index: Int): Int = (index % stickersPerFace) / n
    fun colOf(index: Int): Int = index % n

    /** Doubled layer coordinate of row/column [k]. */
    private fun u(k: Int) = 2 * k - (n - 1)

    /** Cubie position of each sticker (doubled coordinates, outer layers at ±(N-1)). */
    val position: List<Vec3> = List(stickerCount) { i ->
        val r = rowOf(i)
        val c = colOf(i)
        val o = n - 1
        when (faceOf(i)) {
            Face.U -> Vec3(u(c), o, u(r))
            Face.R -> Vec3(o, -u(r), -u(c))
            Face.F -> Vec3(u(c), -u(r), o)
            Face.D -> Vec3(u(c), -o, -u(r))
            Face.L -> Vec3(-o, -u(r), u(c))
            Face.B -> Vec3(-u(c), -u(r), -o)
        }
    }

    /** Outward normal of each sticker. */
    val normal: List<Vec3> = List(stickerCount) { faceOf(it).normal }

    private val lookup: Map<Pair<Vec3, Vec3>, Int> = (0 until stickerCount).associateBy { position[it] to normal[it] }

    /** The sticker on the cubie at [position] facing [normal]. */
    fun indexOf(position: Vec3, normal: Vec3): Int =
        lookup[position to normal] ?: throw IllegalArgumentException("No sticker at $position facing $normal")

    /**
     * Layer depth of sticker [index] as seen from [face]: 1 for the outer layer of [face], N for the
     * outer layer of the opposite face.
     */
    fun depthOf(index: Int, face: Face): Int = ((n - 1) - (position[index] dot face.normal)) / 2 + 1

    /** Kind of piece a sticker belongs to. */
    fun kindOf(index: Int): PieceKind {
        val r = rowOf(index)
        val c = colOf(index)
        val rEdge = r == 0 || r == n - 1
        val cEdge = c == 0 || c == n - 1
        return when {
            rEdge && cEdge -> PieceKind.CORNER
            rEdge || cEdge -> {
                val k = if (rEdge) c else r
                if (n % 2 == 1 && k == n / 2) PieceKind.MIDGE else PieceKind.WING
            }
            n % 2 == 1 && r == n / 2 && c == n / 2 -> PieceKind.FIXED_CENTER
            else -> PieceKind.CENTER
        }
    }

    /** Stickers grouped by cubie: each inner list holds the 1 to 3 stickers of one visible cubie. */
    val cubies: List<List<Int>> by lazy {
        (0 until stickerCount).groupBy { position[it] }.values.map { it.sorted() }
    }

    private val permutations = ConcurrentHashMap<LayerMove, IntArray>()

    /**
     * Sticker permutation of [move]: after the move, sticker `i` holds what was at
     * `permutation(move)[i]` before. Derived from the 3D geometry (same convention as
     * [com.andhab.cubelens.core.cube.Move.permutation]).
     */
    fun permutation(move: LayerMove): IntArray {
        require(move.toDepth <= n) { "Move ${move.notation} needs at least ${move.toDepth} layers, cube is ${n}x$n" }
        return permutations.getOrPut(move) { buildPermutation(move) }
    }

    /** Whether sticker [index] turns with [move]. */
    fun isMovedBy(index: Int, move: LayerMove): Boolean = depthOf(index, move.face) in move.fromDepth..move.toDepth

    private fun buildPermutation(move: LayerMove): IntArray {
        val axis = move.face.normal
        val forward = IntArray(stickerCount) { i ->
            if (isMovedBy(i, move)) {
                indexOf(Facelets.rotateClockwise(position[i], axis), Facelets.rotateClockwise(normal[i], axis))
            } else {
                i
            }
        }
        var dest = IntArray(stickerCount) { it }
        repeat(move.turns) { dest = IntArray(stickerCount) { i -> forward[dest[i]] } }
        val perm = IntArray(stickerCount)
        for (i in 0 until stickerCount) perm[dest[i]] = i
        return perm
    }

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 10

        private val cache = ConcurrentHashMap<Int, NxNGeometry>()

        fun of(n: Int): NxNGeometry {
            require(n in MIN_SIZE..MAX_SIZE) { "Cube size must be in $MIN_SIZE..$MAX_SIZE, got $n" }
            return cache.getOrPut(n) { NxNGeometry(n) }
        }
    }
}

/** What kind of piece a sticker belongs to. */
enum class PieceKind {
    /** Three stickers; every size has eight. */
    CORNER,

    /** Edge sticker of an even-length edge orbit (N >= 4). */
    WING,

    /** Middle edge sticker of an odd cube (the 3×3's edges). */
    MIDGE,

    /** A movable center sticker (N >= 4). */
    CENTER,

    /** The fixed middle center of an odd cube. */
    FIXED_CENTER,
}
