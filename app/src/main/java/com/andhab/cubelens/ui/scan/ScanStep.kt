package com.andhab.cubelens.ui.scan

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNGeometry

/**
 * The six guided scanning steps, in order: which face of the cube is scanned and how it is held.
 *
 * Steps are positional, so they work for every size. The first face the user shows becomes the
 * front ([Face.F]); turning the cube left three times brings the right, back and left faces round;
 * then the top is tipped toward the camera and finally the bottom. Each step starts from where the
 * previous one left the cube, and every face ends up read the way [NxNGeometry] numbers it: the
 * side faces with the top face up, the top face with the back face up, the bottom face with the
 * front face up. The cube's rotation from the starting hold to each step's hold is [rotation].
 *
 * Cubes with an odd size have fixed centers, so for them the steps also name colors: the standard
 * color scheme (white on top, green in front) tells which center belongs in front ([color]) and on
 * top ([topColor]), the reference orientation the scan resolver assumes for an odd cube. Cubes
 * whose colors are arranged differently show other colors as they are turned; from then on the
 * steps go by position (see [ScanUiState.guidedByColor]).
 *
 * @property face the face of the cube (relative to the first face shown) scanned in this step.
 */
enum class ScanStep(val face: Face) {
    Front(Face.F),
    Right(Face.R),
    Back(Face.B),
    Left(Face.L),
    Top(Face.U),
    Bottom(Face.D),
    ;

    /** The center color that faces the camera in this step, on a cube with fixed centers. */
    val color: CubeColor get() = ColorScheme.STANDARD.colorOf(face)

    /** The face that is on top while this face is scanned (see the class documentation). */
    val topFace: Face
        get() = when (face) {
            Face.U -> Face.B
            Face.D -> Face.F
            else -> Face.U
        }

    /** The center color on top while scanning, on a cube with fixed centers, e.g. white for the sides. */
    val topColor: CubeColor get() = ColorScheme.STANDARD.colorOf(topFace)

    /** Position in the guided order, counting from 1, e.g. to say "face 2". */
    val number: Int get() = ordinal + 1

    /** The step before this one in the guided order, or null for the first. */
    val previous: ScanStep? get() = entries.getOrNull(ordinal - 1)

    /**
     * The whole-cube rotation that turns the cube from the first step's hold into this step's hold,
     * for a cube of [size] (null for [Front]): `y` for the right face (the cube turned left once),
     * `y2` and `y'` for the back and left faces, `x'` for the top (tipped toward the viewer) and `x`
     * for the bottom (flipped over).
     */
    fun rotation(size: Int): LayerMove? = when (this) {
        Front -> null
        Right -> LayerMove(Face.U, 1, size, 1)
        Back -> LayerMove(Face.U, 1, size, 2)
        Left -> LayerMove(Face.U, 1, size, 3)
        Top -> LayerMove(Face.R, 1, size, 3)
        Bottom -> LayerMove(Face.R, 1, size, 1)
    }

    /**
     * [colors] (6·N² stickers in [NxNGeometry] order, as the cube sits in the first step's hold)
     * as they sit while the cube is held for this step: this step's face in front, [topFace] on top.
     */
    fun <T> held(colors: List<T>, size: Int): List<T> {
        val move = rotation(size) ?: return colors
        val permutation = NxNGeometry.of(size).permutation(move)
        return List(colors.size) { colors[permutation[it]] }
    }

    /**
     * A solved standard cube of [size] as the user should hold it for this step: this step's [color]
     * in front and [topColor] on top. For the illustrations of cubes with fixed centers.
     */
    fun heldSolvedCube(size: Int): List<CubeColor> = held(NxNCube.solved(size).toColors(), size)

    companion object {
        /** The step that scans the face whose center is [color], on a cube with fixed centers. */
        fun forColor(color: CubeColor): ScanStep = entries.first { it.color == color }
    }
}
