package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import kotlin.random.Random

/** Helpers for building test cubes. */
internal object NxNTestCubes {

    fun scrambled(n: Int, random: Random, scheme: Map<Face, CubeColor> = ColorScheme.STANDARD.centers): NxNCube =
        NxNCube.solved(n, scheme).apply(NxNScrambler.randomMoves(n, random))

    /** The cube with the colors of stickers [i] and [j] exchanged. */
    fun swap(cube: NxNCube, i: Int, j: Int): NxNCube {
        val colors = cube.toColors().toMutableList()
        val t = colors[i]
        colors[i] = colors[j]
        colors[j] = t
        return NxNCube.of(cube.n, colors)
    }

    /** The cube with sticker [i] recolored. */
    fun recolor(cube: NxNCube, i: Int, color: CubeColor): NxNCube {
        val colors = cube.toColors().toMutableList()
        colors[i] = color
        return NxNCube.of(cube.n, colors)
    }

    /** The cube with the colors of stickers [stickers] rotated by one place (last gets first's color). */
    fun rotate(cube: NxNCube, stickers: IntArray): NxNCube {
        val colors = cube.toColors().toMutableList()
        val first = colors[stickers[0]]
        for (k in 0 until stickers.size - 1) colors[stickers[k]] = colors[stickers[k + 1]]
        colors[stickers.last()] = first
        return NxNCube.of(cube.n, colors)
    }

    /** A random color scheme: any assignment of the six colors to faces (real or knock-off). */
    fun randomScheme(random: Random): Map<Face, CubeColor> {
        val colors = CubeColor.entries.shuffled(random)
        return Face.entries.associateWith { colors[it.ordinal] }
    }

    /** The stickers of the DBL corner (D, B, L). */
    fun dblStickers(n: Int): IntArray = NxNModel.of(n).corners[CubieCube.DBL]
}
