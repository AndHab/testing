package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube

/**
 * Immutable sticker-level state of an N×N×N cube: the [CubeColor] of each of the 6·N² stickers in
 * [NxNGeometry] order.
 *
 * Colors are labels, not a fixed arrangement: any six distinct colors in any scheme work. A cube is
 * solved when every face is a single color (for even sizes there are no fixed centers, so a solved
 * cube may be in any orientation).
 */
class NxNCube private constructor(val n: Int, private val colors: Array<CubeColor>) {

    val geometry: NxNGeometry get() = NxNGeometry.of(n)

    operator fun get(index: Int): CubeColor = colors[index]

    fun toColors(): List<CubeColor> = colors.toList()

    fun apply(move: LayerMove): NxNCube {
        val p = geometry.permutation(move)
        return NxNCube(n, Array(colors.size) { colors[p[it]] })
    }

    fun apply(moves: Iterable<LayerMove>): NxNCube = moves.fold(this) { c, m -> c.apply(m) }

    /** Every face shows a single color. */
    val isSolved: Boolean
        get() {
            val per = n * n
            for (f in 0 until 6) {
                val first = colors[f * per]
                for (i in 1 until per) if (colors[f * per + i] != first) return false
            }
            return true
        }

    /** For a 3×3 cube: the equivalent [FaceletCube] (centers define faces), or null if centers repeat. */
    fun toFaceletCube(): FaceletCube? {
        require(n == 3) { "Only a 3x3 cube converts to FaceletCube" }
        return FaceletCube.fromColors(colors.toList())
    }

    /** Stickers of one face, row-major. */
    fun face(face: Face): List<CubeColor> {
        val per = n * n
        return colors.copyOfRange(face.ordinal * per, (face.ordinal + 1) * per).toList()
    }

    override fun equals(other: Any?): Boolean = other is NxNCube && n == other.n && colors.contentEquals(other.colors)
    override fun hashCode(): Int = 31 * n + colors.contentHashCode()
    override fun toString(): String = "NxNCube(${n}x$n, ${colors.joinToString("") { it.letter.toString() }})"

    companion object {
        /** A solved cube colored with [scheme] (standard colors by default). */
        fun solved(n: Int, scheme: Map<Face, CubeColor> = com.andhab.cubelens.core.cube.ColorScheme.STANDARD.centers): NxNCube {
            val g = NxNGeometry.of(n)
            return NxNCube(n, Array(g.stickerCount) { scheme.getValue(g.faceOf(it)) })
        }

        fun of(n: Int, colors: List<CubeColor>): NxNCube {
            val g = NxNGeometry.of(n)
            require(colors.size == g.stickerCount) { "A ${n}x$n cube has ${g.stickerCount} stickers, got ${colors.size}" }
            return NxNCube(n, colors.toTypedArray())
        }

        /** Parses one color letter (W, Y, G, B, R, O) per sticker. */
        fun parse(n: Int, letters: String): NxNCube = of(n, letters.map { CubeColor.fromLetter(it) })

        fun fromFaceletCube(cube: FaceletCube): NxNCube = of(3, cube.toColors())
    }
}
