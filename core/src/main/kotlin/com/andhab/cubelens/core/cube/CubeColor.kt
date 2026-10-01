package com.andhab.cubelens.core.cube

/** The six sticker colors of a standard cube. */
enum class CubeColor(val letter: Char) {
    WHITE('W'),
    YELLOW('Y'),
    GREEN('G'),
    BLUE('B'),
    RED('R'),
    ORANGE('O');

    val displayName: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun fromLetter(c: Char): CubeColor =
            entries.firstOrNull { it.letter == c.uppercaseChar() }
                ?: throw IllegalArgumentException("Not a color letter: '$c'")
    }
}

/**
 * Which color sits on which face (i.e. the center colors).
 *
 * The app always presents the cube in [STANDARD] orientation: white on top (U), green in front (F),
 * red on the right (R). Scanning instructions and solution playback both use this orientation.
 */
data class ColorScheme(val centers: Map<Face, CubeColor>) {
    init {
        require(centers.size == 6 && centers.values.toSet().size == 6) {
            "A color scheme needs six distinct center colors, got $centers"
        }
    }

    fun colorOf(face: Face): CubeColor = centers.getValue(face)

    fun faceOf(color: CubeColor): Face = centers.entries.first { it.value == color }.key

    companion object {
        /** Western color scheme: white top, green front, red right, yellow bottom, blue back, orange left. */
        val STANDARD = ColorScheme(
            mapOf(
                Face.U to CubeColor.WHITE,
                Face.R to CubeColor.RED,
                Face.F to CubeColor.GREEN,
                Face.D to CubeColor.YELLOW,
                Face.L to CubeColor.ORANGE,
                Face.B to CubeColor.BLUE,
            )
        )
    }
}
