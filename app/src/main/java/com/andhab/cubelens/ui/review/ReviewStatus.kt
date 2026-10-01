package com.andhab.cubelens.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
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
}

/**
 * A plain-words title for the first problem found (counting others of the same kind), with advice
 * on where to look. Problems that can't be pinned to particular stickers point at the hard-to-read
 * ones instead, which [ReviewState.flagged] marks for exactly that reason.
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
            val name = colorName(review.colors[Facelets.center(error.face)]).lowercase()
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

/** The one-line editing tip under the palette, following what the user is doing. */
@Composable
internal fun reviewTip(review: ReviewState): String {
    val brush = review.brush
    val selected = review.selected
    return when {
        review.hint == ReviewHint.CenterLocked -> stringResource(R.string.review_tip_center)
        brush != null -> stringResource(R.string.review_tip_brush, colorName(brush).lowercase())
        selected != null -> {
            // How to hold the real cube to find this sticker in the same spot as on the net.
            val face = Facelets.faceOf(selected)
            val toward = colorName(ColorScheme.STANDARD.colorOf(face)).lowercase()
            val onTop = colorName(ColorScheme.STANDARD.colorOf(face.netTop)).lowercase()
            stringResource(R.string.review_tip_selected, toward, onTop)
        }
        else -> stringResource(R.string.review_tip_idle)
    }
}

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
