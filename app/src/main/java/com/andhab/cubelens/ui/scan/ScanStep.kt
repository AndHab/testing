package com.andhab.cubelens.ui.scan

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets

/**
 * The six guided scanning steps, in order, each with the face to show and how to hold the cube.
 *
 * The holds are the reference orientation the scan resolver assumes when a cube has more than one
 * valid reading: side faces with white on top, the white face with blue on top, the yellow face
 * with green on top. Each step starts from where the previous one left the cube, so following the
 * [cue]s keeps the cube in that hold.
 *
 * @property face the face of the standard orientation (white up, green front) being scanned.
 * @property color the center color of that face: what should be facing the camera.
 * @property topColor the center color that should be on top while scanning.
 * @property rightColor the center color on the right while scanning (for the illustration).
 * @property cue how to get there from the previous step, as a short sentence. Only right while the
 *   cube is still held as the previous step left it; otherwise use [anywhereCue].
 */
enum class ScanStep(
    val face: Face,
    val color: CubeColor,
    val topColor: CubeColor,
    val rightColor: CubeColor,
    val cue: String,
) {
    Green(Face.F, CubeColor.GREEN, CubeColor.WHITE, CubeColor.RED, "Fill the frame with the green face."),
    Red(Face.R, CubeColor.RED, CubeColor.WHITE, CubeColor.BLUE, "Turn the cube to the left."),
    Blue(Face.B, CubeColor.BLUE, CubeColor.WHITE, CubeColor.ORANGE, "Turn it left again."),
    Orange(Face.L, CubeColor.ORANGE, CubeColor.WHITE, CubeColor.GREEN, "One more turn to the left."),
    White(Face.U, CubeColor.WHITE, CubeColor.BLUE, CubeColor.RED, "Turn left once more, then tilt the top toward you."),
    Yellow(Face.D, CubeColor.YELLOW, CubeColor.GREEN, CubeColor.RED, "Flip the cube over toward you."),
    ;

    /** Main instruction, e.g. "Green center facing you". */
    val title: String get() = "${color.displayName} center facing you"

    /** How to hold it, e.g. "White on top". */
    val hold: String get() = "${topColor.displayName} on top"

    /** How to get there from any hold, e.g. "Turn the cube until blue faces you." */
    val anywhereCue: String get() = "Turn the cube until ${color.displayName.lowercase()} faces you."

    /** The step before this one in the guided order, or null for the first. */
    val previous: ScanStep? get() = entries.getOrNull(ordinal - 1)

    /**
     * A solved cube as the user should be holding it for this step, in facelet order: this step's
     * color in front, [topColor] on top and [rightColor] on the right.
     */
    val heldCubeColors: List<CubeColor> by lazy {
        val front = color
        val held = mapOf(
            Face.F to front,
            Face.B to front.opposite,
            Face.U to topColor,
            Face.D to topColor.opposite,
            Face.R to rightColor,
            Face.L to rightColor.opposite,
        )
        List(Facelets.COUNT) { held.getValue(Facelets.faceOf(it)) }
    }

    /** What a not-yet-scanned face looks like: only its center is known. */
    val placeholderColors: List<CubeColor?> get() = List(9) { if (it == 4) color else null }

    companion object {
        /** The step that scans the face whose center is [color]. */
        fun forColor(color: CubeColor): ScanStep = entries.first { it.color == color }
    }
}

/** The color on the opposite face of a standard cube. */
internal val CubeColor.opposite: CubeColor
    get() = when (this) {
        CubeColor.WHITE -> CubeColor.YELLOW
        CubeColor.YELLOW -> CubeColor.WHITE
        CubeColor.GREEN -> CubeColor.BLUE
        CubeColor.BLUE -> CubeColor.GREEN
        CubeColor.RED -> CubeColor.ORANGE
        CubeColor.ORANGE -> CubeColor.RED
    }
