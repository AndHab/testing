package com.andhab.cubelens.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNProblem
import com.andhab.cubelens.ui.components.BannerKind
import com.andhab.cubelens.ui.components.colorName

/** What the review's status banner says. */
@Immutable
internal data class ReviewStatus(val kind: BannerKind, val title: String, val message: String?)

/**
 * The status banner for [review]: how many stickers are left, what is wrong in plain words, or
 * that the cube is good to go. Never uses cube jargon.
 */
@Composable
internal fun reviewStatus(review: ReviewState): ReviewStatus = when (val check = review.check) {
    is ReviewCheck.Incomplete -> ReviewStatus(
        kind = BannerKind.Info,
        title = pluralStringResource(R.plurals.review_remaining, check.remaining, check.remaining),
        message = stringResource(R.string.review_remaining_message),
    )
    ReviewCheck.Valid -> ReviewStatus(
        kind = BannerKind.Success,
        title = stringResource(R.string.review_ok_title),
        message = stringResource(
            if (review.uncertain.isEmpty()) R.string.review_ok_message else R.string.review_ok_message_uncertain,
        ),
    )
    is ReviewCheck.Invalid -> errorStatus(check, review)
    is ReviewCheck.InvalidNxN -> errorStatus(check, review)
}

/**
 * A plain-words title for the first problem found on a 3×3 (counting others of the same kind), with
 * advice on where to look. Problems that can't be pinned to particular stickers point at the
 * hard-to-read ones instead, which [ReviewState.flagged] marks for exactly that reason.
 */
@Composable
private fun errorStatus(check: ReviewCheck.Invalid, review: ReviewState): ReviewStatus {
    val count = check.sameKindCount
    val marked = stringResource(R.string.review_error_marked)
    val startWithUncertain = check.flagged.isEmpty() && review.uncertain.isNotEmpty()
    return when (val error = check.error) {
        CubeError.CentersNotDistinct -> ReviewStatus(
            BannerKind.Error,
            stringResource(R.string.review_error_centers),
            stringResource(R.string.review_error_centers_message),
        )
        is CubeError.WrongColorCount -> {
            val name = colorName(review.colors[review.geometry.index(error.face, 1, 1)]).lowercase()
            ReviewStatus(
                BannerKind.Error,
                stringResource(if (error.count > 9) R.string.review_error_too_many else R.string.review_error_too_few, name),
                if (startWithUncertain) {
                    stringResource(R.string.review_error_count_uncertain, error.count)
                } else {
                    stringResource(R.string.review_error_count_message, error.count)
                },
            )
        }
        is CubeError.ImpossibleCorner -> ReviewStatus(
            BannerKind.Error,
            pluralStringResource(R.plurals.review_error_corner, count, count),
            stringResource(R.string.review_error_impossible_message),
        )
        is CubeError.ImpossibleEdge -> ReviewStatus(
            BannerKind.Error,
            pluralStringResource(R.plurals.review_error_edge, count, count),
            stringResource(R.string.review_error_impossible_message),
        )
        is CubeError.DuplicateCorner -> ReviewStatus(
            BannerKind.Error,
            pluralStringResource(R.plurals.review_error_duplicate_corner, count, count),
            stringResource(R.string.review_error_duplicate_message),
        )
        is CubeError.DuplicateEdge -> ReviewStatus(
            BannerKind.Error,
            pluralStringResource(R.plurals.review_error_duplicate_edge, count, count),
            stringResource(R.string.review_error_duplicate_message),
        )
        CubeError.TwistedCorner -> ReviewStatus(
            BannerKind.Warning,
            stringResource(R.string.review_error_twisted),
            if (startWithUncertain) marked else stringResource(R.string.review_error_twisted_message),
        )
        CubeError.FlippedEdge -> ReviewStatus(
            BannerKind.Warning,
            stringResource(R.string.review_error_flipped),
            if (startWithUncertain) marked else stringResource(R.string.review_error_flipped_message),
        )
        CubeError.Parity -> ReviewStatus(
            BannerKind.Warning,
            stringResource(R.string.review_error_swapped),
            if (startWithUncertain) marked else stringResource(R.string.review_error_swapped_message),
        )
    }
}

/**
 * The first problem found on a 2×2 or a 4×4 and larger, worded as on a 3×3: a short title for its
 * kind (counting others of the same kind) and advice on where to look. Problems that can't be
 * pinned to particular stickers point at the hard-to-read ones instead, and those that point
 * nowhere at all (a twisted corner, two swapped pieces) are a softer warning, as on a 3×3.
 */
@Composable
private fun errorStatus(check: ReviewCheck.InvalidNxN, review: ReviewState): ReviewStatus {
    val problem = check.error.problem
    val count = check.errors.count { it.problem.group == problem.group }
    val startWithUncertain = check.flagged.isEmpty() && review.uncertain.isNotEmpty()
    val marked = stringResource(R.string.review_error_marked)
    val lookAt = stringResource(if (startWithUncertain) R.string.review_error_marked else R.string.review_error_check_marked)
    val error = BannerKind.Error
    return when (problem) {
        is NxNProblem.MissingColors -> ReviewStatus(
            error,
            stringResource(R.string.review_error_missing_colors, problem.present),
            stringResource(R.string.review_error_missing_colors_message),
        )
        is NxNProblem.WrongCount -> ReviewStatus(
            error,
            stringResource(
                if (problem.found > problem.expected) R.string.review_error_too_many else R.string.review_error_too_few,
                colorName(problem.color).lowercase(),
            ),
            if (startWithUncertain) {
                stringResource(R.string.review_error_count_uncertain_n, problem.found, problem.expected)
            } else {
                stringResource(
                    R.string.review_error_count_message_n,
                    problem.found,
                    stringResource(R.string.cube_size_label, review.n),
                    problem.expected,
                )
            },
        )
        NxNProblem.CentersNotDistinct -> ReviewStatus(
            error,
            stringResource(R.string.review_error_centers),
            stringResource(R.string.review_error_centers_message),
        )
        NxNProblem.CentersMismatch -> ReviewStatus(
            error,
            stringResource(R.string.review_error_centers_mismatch),
            stringResource(R.string.review_error_centers_mismatch_message),
        )
        is NxNProblem.ImpossiblePiece -> ReviewStatus(
            error,
            if (problem.piece == NxNProblem.Piece.CORNER) {
                pluralStringResource(R.plurals.review_error_corner, count, count)
            } else {
                pluralStringResource(R.plurals.review_error_edge, count, count)
            },
            stringResource(R.string.review_error_impossible_message),
        )
        is NxNProblem.DuplicatePiece -> ReviewStatus(
            error,
            if (problem.piece == NxNProblem.Piece.CORNER) {
                pluralStringResource(R.plurals.review_error_duplicate_corner, count, count)
            } else {
                pluralStringResource(R.plurals.review_error_duplicate_edge, count, count)
            },
            stringResource(R.string.review_error_duplicate_message),
        )
        NxNProblem.TwistedCorner -> ReviewStatus(
            BannerKind.Warning,
            stringResource(R.string.review_error_twisted),
            if (startWithUncertain) marked else stringResource(R.string.review_error_twisted_message),
        )
        NxNProblem.FlippedEdge -> ReviewStatus(
            BannerKind.Warning,
            stringResource(R.string.review_error_flipped),
            if (startWithUncertain) marked else stringResource(R.string.review_error_flipped_message),
        )
        NxNProblem.Swapped -> ReviewStatus(
            BannerKind.Warning,
            stringResource(R.string.review_error_swapped),
            if (startWithUncertain) marked else stringResource(R.string.review_error_swapped_message),
        )
        NxNProblem.CentersDontAddUp -> ReviewStatus(error, stringResource(R.string.review_error_center_pieces), lookAt)
        NxNProblem.Other -> ReviewStatus(
            if (check.flagged.isNotEmpty()) error else BannerKind.Warning,
            stringResource(R.string.review_error_other),
            if (check.flagged.isNotEmpty() || startWithUncertain) lookAt else stringResource(R.string.review_error_swapped_message),
        )
    }
}

/** Problems of one group are counted together in a title ("2 edges can't exist"). */
private val NxNProblem.group: Any
    get() = when (this) {
        is NxNProblem.ImpossiblePiece -> "impossible" to (piece == NxNProblem.Piece.CORNER)
        is NxNProblem.DuplicatePiece -> "duplicate" to (piece == NxNProblem.Piece.CORNER)
        else -> this::class
    }

/**
 * The one-line editing tip under the palette, following what the user is doing. On a cube edited
 * face by face, the tips for the net (face editor closed) point at the faces instead of stickers.
 */
@Composable
internal fun reviewTip(review: ReviewState): String {
    val brush = review.brush
    val selected = review.selected
    val onNet = review.usesFaceEditor && review.focusedFace == null
    return when {
        review.hint == ReviewHint.CenterLocked -> stringResource(R.string.review_tip_center)
        review.hint == ReviewHint.CenterSelected -> stringResource(R.string.review_tip_center_selected)
        brush != null && onNet -> stringResource(R.string.review_tip_brush_net, colorName(brush).lowercase())
        brush != null -> stringResource(R.string.review_tip_brush, colorName(brush).lowercase())
        selected != null -> selectedTip(review, selected)
        onNet -> stringResource(R.string.review_tip_idle_net)
        else -> stringResource(R.string.review_tip_idle)
    }
}

/**
 * How to hold the real cube to find sticker [index] in the same spot as on screen: by the colors of
 * the fixed centers on odd cubes ("Hold green toward you, white on top"), by face and row on even
 * cubes, which have no fixed centers.
 */
@Composable
private fun selectedTip(review: ReviewState, index: Int): String {
    val geometry = review.geometry
    val face = geometry.faceOf(index)
    if (review.n % 2 == 1) {
        val middle = review.n / 2
        val toward = review.colors[geometry.index(face, middle, middle)]
        val onTop = review.colors[geometry.index(face.netTop, middle, middle)]
        if (toward != null && onTop != null) {
            return stringResource(R.string.review_tip_selected, colorName(toward).lowercase(), colorName(onTop).lowercase())
        }
    }
    return stringResource(R.string.review_tip_selected_row, faceName(face), geometry.rowOf(index) + 1)
}

/** The face's plain name: "Top face", "Front face"… */
@Composable
internal fun faceName(face: Face): String = stringResource(
    when (face) {
        Face.U -> R.string.review_face_top
        Face.D -> R.string.review_face_bottom
        Face.F -> R.string.review_face_front
        Face.B -> R.string.review_face_back
        Face.L -> R.string.review_face_left
        Face.R -> R.string.review_face_right
    },
)

/**
 * The face that sits above [this] one when it is drawn on the net (and held toward the user to
 * read it in the same orientation): the back above the top, the front above the bottom, and the
 * top above every side.
 */
private val Face.netTop: Face
    get() = when (this) {
        Face.U -> Face.B
        Face.D -> Face.F
        Face.F, Face.R, Face.B, Face.L -> Face.U
    }
