package com.andhab.cubelens.core.cube

/**
 * Immutable cube state at sticker level. Each of the 54 facelets (see [Facelets] for the layout)
 * holds the [Face] whose center color it matches, i.e. the Kociemba facelet string
 * "UUUUUUUUURRRRRRRRR..." for a solved cube.
 */
class FaceletCube private constructor(private val faces: Array<Face>) {

    operator fun get(index: Int): Face = faces[index]

    fun toList(): List<Face> = faces.toList()

    /** Kociemba facelet string, 54 characters from "URFDLB". */
    fun toFaceletString(): String = buildString(Facelets.COUNT) { faces.forEach { append(it.name) } }

    fun apply(move: Move): FaceletCube {
        val p = move.permutation
        return FaceletCube(Array(Facelets.COUNT) { faces[p[it]] })
    }

    fun apply(moves: Iterable<Move>): FaceletCube = moves.fold(this) { cube, m -> cube.apply(m) }

    val isSolved: Boolean
        get() = faces.indices.all { faces[it] == Facelets.faceOf(it) }

    /** Sticker colors for display, given which color each face's center has. */
    fun toColors(scheme: ColorScheme = ColorScheme.STANDARD): List<CubeColor> = faces.map { scheme.colorOf(it) }

    override fun equals(other: Any?): Boolean = other is FaceletCube && faces.contentEquals(other.faces)
    override fun hashCode(): Int = faces.contentHashCode()
    override fun toString(): String = toFaceletString()

    companion object {
        val SOLVED: FaceletCube = FaceletCube(Array(Facelets.COUNT) { Facelets.faceOf(it) })

        /** Parses a 54-character facelet string over the letters U, R, F, D, L, B. */
        fun parse(s: String): FaceletCube {
            require(s.length == Facelets.COUNT) { "Facelet string must have 54 characters, got ${s.length}" }
            return FaceletCube(Array(Facelets.COUNT) { Face.fromChar(s[it]) })
        }

        fun of(faces: List<Face>): FaceletCube {
            require(faces.size == Facelets.COUNT) { "Need 54 facelets, got ${faces.size}" }
            return FaceletCube(faces.toTypedArray())
        }

        /**
         * Builds a state from 54 sticker colors (in facelet order). The center stickers define which
         * color belongs to which face. Returns null if the six centers are not six distinct colors.
         */
        fun fromColors(colors: List<CubeColor>): FaceletCube? {
            require(colors.size == Facelets.COUNT) { "Need 54 colors, got ${colors.size}" }
            val scheme = schemeOf(colors) ?: return null
            return FaceletCube(Array(Facelets.COUNT) { scheme.faceOf(colors[it]) })
        }

        /** The color scheme implied by the center stickers, or null if centers repeat. */
        fun schemeOf(colors: List<CubeColor>): ColorScheme? {
            val centers = Face.entries.associateWith { colors[Facelets.center(it)] }
            return if (centers.values.toSet().size == 6) ColorScheme(centers) else null
        }

        /** Applies [moves] to a solved cube. */
        fun scrambled(moves: Iterable<Move>): FaceletCube = SOLVED.apply(moves)
    }
}
