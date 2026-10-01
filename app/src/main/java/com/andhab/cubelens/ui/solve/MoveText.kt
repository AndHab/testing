package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Move
import java.util.Locale

/** Friendly name of a face as the person holds the cube: "Top face", "Right face", ... */
@Composable
fun faceName(face: Face): String = stringResource(
    when (face) {
        Face.U -> R.string.solve_face_u
        Face.D -> R.string.solve_face_d
        Face.F -> R.string.solve_face_f
        Face.B -> R.string.solve_face_b
        Face.L -> R.string.solve_face_l
        Face.R -> R.string.solve_face_r
    },
)

/**
 * How a move turns its face, as seen looking straight at that face: "clockwise",
 * "counter-clockwise" or "half turn" (lowercase, to follow a face name).
 */
@Composable
fun turnDirection(move: Move): String = stringResource(
    when (move.turns) {
        1 -> R.string.solve_turn_clockwise
        2 -> R.string.solve_turn_half
        else -> R.string.solve_turn_counter_clockwise
    },
)

/**
 * Where to look from for [turnDirection] to read right, for the faces that point away from someone
 * holding the cube in the standard orientation: "Seen from behind" (back), "Seen from below"
 * (bottom) and "Seen from the left" (left). Null for the top, front and right faces, which they
 * already see.
 */
@Composable
fun turnViewpoint(face: Face): String? = when (face) {
    Face.B -> stringResource(R.string.solve_view_b)
    Face.D -> stringResource(R.string.solve_view_d)
    Face.L -> stringResource(R.string.solve_view_l)
    Face.U, Face.F, Face.R -> null
}

/** One-line description of a move without notation: "Right face · clockwise", "Top face · half turn". */
@Composable
fun moveDescription(move: Move): String =
    stringResource(R.string.solve_move_description, faceName(move.face), turnDirection(move))

/** The user's primary locale, for case changes of localized text. */
@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Uppercases the first letter, for a lowercase phrase shown on its own line. */
internal fun String.capitalizeFirst(locale: Locale): String = replaceFirstChar { it.titlecase(locale) }
