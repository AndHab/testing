package com.andhab.cubelens.ui.solve

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNSolver
import java.util.Locale

/**
 * A move in plain words, as the person holding the cube needs it.
 *
 * @property layers which layers turn: "Right face", "Right two layers", "Right 3 layers",
 *   "2nd layer from the right", "Layers 2–3 from the right" or "Whole cube".
 * @property direction how they turn, lowercase to follow [layers]: "clockwise",
 *   "counter-clockwise" or "half turn", as seen looking at the side the layers are counted from.
 * @property viewpoint where to look from for [direction] to read right, when the person does not
 *   already face that side ("Seen from behind", "Seen from below", "Seen from the left"; for a turn
 *   of the whole cube, every side); null otherwise.
 */
@Immutable
data class MoveWords(val layers: String, val direction: String, val viewpoint: String?)

/** [MoveWords] for [move] on an [n]×[n] cube, in the current configuration's language. */
@Composable
@ReadOnlyComposable
fun moveWords(move: LayerMove, n: Int): MoveWords {
    // Read the configuration so a locale change recomposes.
    LocalConfiguration.current
    return LocalResources.current.moveWords(move, n)
}

/** One-line description of a move without notation: "Right two layers · clockwise". */
@Composable
@ReadOnlyComposable
fun moveDescription(move: LayerMove, n: Int): String {
    LocalConfiguration.current
    return LocalResources.current.moveDescription(move, n)
}

/** Friendly name of a face as the person holds the cube: "Top face", "Right face", ... */
@Composable
@ReadOnlyComposable
fun faceName(face: Face): String {
    LocalConfiguration.current
    return LocalResources.current.faceName(face)
}

/**
 * Cube notation as a screen reader should say it: "R", "Rw prime", "2R two", "2-3Rw prime"
 * (localized), never "apostrophe" and never mistaking a slice number for a half turn.
 */
@Composable
@ReadOnlyComposable
fun spokenMove(move: LayerMove): String {
    LocalConfiguration.current
    return LocalResources.current.spokenMove(move)
}

/**
 * Localized name of a solution stage ([NxNSolver.STAGE_SOLVE], [NxNSolver.STAGE_CORNERS], ...);
 * an unknown stage keeps its own name.
 */
@Composable
@ReadOnlyComposable
fun stageName(stage: String): String {
    LocalConfiguration.current
    return LocalResources.current.stageName(stage)
}

/** [MoveWords] for [move] on an [n]×[n] cube. */
internal fun Resources.moveWords(move: LayerMove, n: Int): MoveWords {
    val wholeCube = move.fromDepth == 1 && move.toDepth >= n
    return MoveWords(
        layers = layerName(move, wholeCube),
        direction = getString(
            when (move.turns) {
                1 -> R.string.solve_turn_clockwise
                2 -> R.string.solve_turn_half
                else -> R.string.solve_turn_counter_clockwise
            },
        ),
        viewpoint = when {
            wholeCube || move.face == Face.B || move.face == Face.D || move.face == Face.L -> getString(viewpointOf(move.face))
            else -> null
        },
    )
}

/** "Right two layers · clockwise". */
internal fun Resources.moveDescription(move: LayerMove, n: Int): String {
    val words = moveWords(move, n)
    return getString(R.string.solve_move_description, words.layers, words.direction)
}

internal fun Resources.faceName(face: Face): String = getString(
    when (face) {
        Face.U -> R.string.solve_face_u
        Face.D -> R.string.solve_face_d
        Face.F -> R.string.solve_face_f
        Face.B -> R.string.solve_face_b
        Face.L -> R.string.solve_face_l
        Face.R -> R.string.solve_face_r
    },
)

internal fun Resources.spokenMove(move: LayerMove): String {
    val layers = move.copy(turns = 1).notation
    return when (move.turns) {
        1 -> layers
        2 -> getString(R.string.notation_double, layers)
        else -> getString(R.string.notation_prime, layers)
    }
}

internal fun Resources.stageName(stage: String): String = when (stage) {
    NxNSolver.STAGE_SOLVE -> getString(R.string.solve_stage_solve)
    NxNSolver.STAGE_CORNERS -> getString(R.string.solve_stage_corners)
    NxNSolver.STAGE_FRAME -> getString(R.string.solve_stage_frame)
    NxNSolver.STAGE_EDGES -> getString(R.string.solve_stage_edges)
    NxNSolver.STAGE_CENTERS -> getString(R.string.solve_stage_centers)
    else -> stage
}

private fun Resources.layerName(move: LayerMove, wholeCube: Boolean): String {
    val face = move.face
    return when {
        wholeCube -> getString(R.string.solve_whole_cube)
        move.isOuter -> faceName(face)
        move.fromDepth == 1 && move.toDepth == 2 -> getString(
            when (face) {
                Face.U -> R.string.solve_wide2_u
                Face.D -> R.string.solve_wide2_d
                Face.F -> R.string.solve_wide2_f
                Face.B -> R.string.solve_wide2_b
                Face.L -> R.string.solve_wide2_l
                Face.R -> R.string.solve_wide2_r
            },
        )
        move.fromDepth == 1 -> getString(
            when (face) {
                Face.U -> R.string.solve_wide_u
                Face.D -> R.string.solve_wide_d
                Face.F -> R.string.solve_wide_f
                Face.B -> R.string.solve_wide_b
                Face.L -> R.string.solve_wide_l
                Face.R -> R.string.solve_wide_r
            },
            move.toDepth,
        )
        move.fromDepth == move.toDepth -> getString(
            when (face) {
                Face.U -> R.string.solve_slice_u
                Face.D -> R.string.solve_slice_d
                Face.F -> R.string.solve_slice_f
                Face.B -> R.string.solve_slice_b
                Face.L -> R.string.solve_slice_l
                Face.R -> R.string.solve_slice_r
            },
            ordinal(move.fromDepth),
        )
        else -> getString(
            when (face) {
                Face.U -> R.string.solve_range_u
                Face.D -> R.string.solve_range_d
                Face.F -> R.string.solve_range_f
                Face.B -> R.string.solve_range_b
                Face.L -> R.string.solve_range_l
                Face.R -> R.string.solve_range_r
            },
            move.fromDepth,
            move.toDepth,
        )
    }
}

private fun viewpointOf(face: Face): Int = when (face) {
    Face.U -> R.string.solve_view_u
    Face.D -> R.string.solve_view_d
    Face.F -> R.string.solve_view_f
    Face.B -> R.string.solve_view_b
    Face.L -> R.string.solve_view_l
    Face.R -> R.string.solve_view_r
}

/** "2nd", "3rd", ... for a layer [depth]; the plain number beyond the localized list. */
private fun Resources.ordinal(depth: Int): String =
    getStringArray(R.array.solve_ordinals).getOrNull(depth - 1) ?: depth.toString()

/** The user's primary locale, for case changes of localized text. */
@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Uppercases the first letter, for a lowercase phrase shown on its own line. */
internal fun String.capitalizeFirst(locale: Locale): String = replaceFirstChar { it.titlecase(locale) }
