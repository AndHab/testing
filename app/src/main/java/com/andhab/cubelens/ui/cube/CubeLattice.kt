package com.andhab.cubelens.ui.cube

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNGeometry
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The cubie grid of an N×N×N cube as the renderer sees it, cached per size (see [of]).
 *
 * Cubies sit on an N×N×N grid of layer indices `0 until n` along x, y and z (index 0 is the L, D
 * and B side). The whole cube spans [-HALF_EXTENT, HALF_EXTENT] in world units for every size, so
 * one cubie is [cell] wide. Stickers follow [NxNGeometry] (index = face·N² + row·N + col).
 */
internal class CubeLattice private constructor(val n: Int) {
    val geometry: NxNGeometry = NxNGeometry.of(n)

    /** Number of stickers, 6·N². */
    val stickerCount: Int = geometry.stickerCount

    /** Stickers per face, N². */
    val stickersPerFace: Int = n * n

    /** Edge length of one cubie in world units. */
    val cell: Float = 2f * CubeGeometry.HALF_EXTENT / n

    /** Sticker index on face `d` of the cubie at layer indices (x, y, z), or -1 (see [sticker]). */
    private val stickerTable = IntArray(n * n * n * 6) { -1 }.also { table ->
        for (i in 0 until stickerCount) {
            val p = geometry.position[i]
            val cubie = cubieIndex(layerOfDoubled(p.x), layerOfDoubled(p.y), layerOfDoubled(p.z))
            table[cubie * 6 + geometry.faceOf(i).ordinal] = i
        }
    }

    /** World coordinate of the lower boundary plane of layer [k] (`k == n` gives the upper face of the cube). */
    fun plane(k: Int): Float = k * cell - CubeGeometry.HALF_EXTENT

    /** Index of the cubie at layer indices (x, y, z) in an N³ array. */
    fun cubieIndex(x: Int, y: Int, z: Int): Int = (x * n + y) * n + z

    /**
     * The sticker shown on face [face] (a [Face] ordinal) of the cubie at layer indices (x, y, z), or
     * -1 for an inner face (one that is not on the outside of the cube).
     */
    fun sticker(x: Int, y: Int, z: Int, face: Int): Int = stickerTable[cubieIndex(x, y, z) * 6 + face]

    /**
     * Layer index along the positive axis of [face] (see [CubeGeometry.axisOf]) of the layer at
     * [depth] counted from [face] (1 = the outer layer of [face], as in
     * [com.andhab.cubelens.core.nxn.LayerMove]).
     */
    fun layerAtDepth(face: Face, depth: Int): Int = if (CubeGeometry.signOf(face) > 0) n - depth else depth - 1

    /** Layer index of a doubled [NxNGeometry] coordinate (outer layers at ±(N-1)). */
    private fun layerOfDoubled(u: Int): Int = (u + n - 1) / 2

    companion object {
        private val cache = arrayOfNulls<CubeLattice>(NxNGeometry.MAX_SIZE + 1)

        /** The lattice of an [n]×[n] cube, [NxNGeometry.MIN_SIZE] ≤ n ≤ [NxNGeometry.MAX_SIZE]. */
        fun of(n: Int): CubeLattice {
            require(n in NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE) {
                "Cube size must be in ${NxNGeometry.MIN_SIZE}..${NxNGeometry.MAX_SIZE}, got $n"
            }
            return cache[n] ?: synchronized(cache) { cache[n] ?: CubeLattice(n).also { cache[n] = it } }
        }
    }
}

/**
 * Cube size inferred from sticker counts, so callers can pass plain color lists of any size.
 */
internal object CubeSizes {
    /** N of a whole cube given its 6·N² sticker colors; throws for any other count. */
    fun ofCube(stickerCount: Int): Int {
        val n = exactRoot(stickerCount, 6)
        require(n != null && n in NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE) {
            "A cube needs 6·N² sticker colors with N in ${NxNGeometry.MIN_SIZE}..${NxNGeometry.MAX_SIZE}, got $stickerCount"
        }
        return n
    }

    /** N of a single face given its N² sticker colors; throws for any other count. */
    fun ofFace(stickerCount: Int): Int {
        val n = exactRoot(stickerCount, 1)
        require(n != null && n in NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE) {
            "A face needs N² sticker colors with N in ${NxNGeometry.MIN_SIZE}..${NxNGeometry.MAX_SIZE}, got $stickerCount"
        }
        return n
    }

    /** The n with `factor·n² == count`, or null. */
    private fun exactRoot(count: Int, factor: Int): Int? {
        if (count <= 0 || count % factor != 0) return null
        val n = sqrt((count / factor).toDouble()).roundToInt()
        return n.takeIf { factor * n * n == count }
    }
}
