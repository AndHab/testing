package com.andhab.cubelens.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeError
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
    is ReviewCheck.Invalid -> errorStatus(check.error, review.colors)
}

@Composable
private fun errorStatus(error: CubeError, colors: List<CubeColor?>): ReviewStatus = when (error) {
    CubeError.CentersNotDistinct -> ReviewStatus(
        BannerKind.Error,
        stringResource(R.string.review_error_centers),
        stringResource(R.string.review_error_centers_message),
    )
    is CubeError.WrongColorCount -> {
        val name = colorName(colors[Facelets.center(error.face)]).lowercase()
        ReviewStatus(
            BannerKind.Error,
            stringResource(if (error.count > 9) R.string.review_error_too_many else R.string.review_error_too_few, name),
            stringResource(R.string.review_error_count_message, error.count),
        )
    }
    is CubeError.ImpossibleCorner -> ReviewStatus(
        BannerKind.Error,
        stringResource(R.string.review_error_corner),
        stringResource(R.string.review_error_corner_message),
    )
    is CubeError.ImpossibleEdge -> ReviewStatus(
        BannerKind.Error,
        stringResource(R.string.review_error_edge),
        stringResource(R.string.review_error_edge_message),
    )
    is CubeError.DuplicateCorner -> ReviewStatus(
        BannerKind.Error,
        stringResource(R.string.review_error_duplicate_corner),
        stringResource(R.string.review_error_duplicate_message),
    )
    is CubeError.DuplicateEdge -> ReviewStatus(
        BannerKind.Error,
        stringResource(R.string.review_error_duplicate_edge),
        stringResource(R.string.review_error_duplicate_message),
    )
    CubeError.TwistedCorner -> ReviewStatus(
        BannerKind.Warning,
        stringResource(R.string.review_error_twisted),
        stringResource(R.string.review_error_twisted_message),
    )
    CubeError.FlippedEdge -> ReviewStatus(
        BannerKind.Warning,
        stringResource(R.string.review_error_flipped),
        stringResource(R.string.review_error_flipped_message),
    )
    CubeError.Parity -> ReviewStatus(
        BannerKind.Warning,
        stringResource(R.string.review_error_swapped),
        stringResource(R.string.review_error_swapped_message),
    )
}

/** The one-line editing tip under the palette, following what the user is doing. */
@Composable
internal fun reviewTip(review: ReviewState): String {
    val brush = review.brush
    return when {
        review.hint == ReviewHint.CenterLocked -> stringResource(R.string.review_tip_center)
        brush != null -> stringResource(R.string.review_tip_brush, colorName(brush).lowercase())
        review.selected != null -> stringResource(R.string.review_tip_selected)
        else -> stringResource(R.string.review_tip_idle)
    }
}
