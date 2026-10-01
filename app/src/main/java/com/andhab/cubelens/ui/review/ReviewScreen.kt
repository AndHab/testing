package com.andhab.cubelens.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.ui.components.AuroraBackground
import com.andhab.cubelens.ui.components.ButtonHeight
import com.andhab.cubelens.ui.components.CircleIconButton
import com.andhab.cubelens.ui.components.Overline
import com.andhab.cubelens.ui.components.Pill
import com.andhab.cubelens.ui.components.PrimaryButton
import com.andhab.cubelens.ui.components.Reveal
import com.andhab.cubelens.ui.components.StatusBanner
import com.andhab.cubelens.ui.components.TopBar
import com.andhab.cubelens.ui.cube.Cube3D
import com.andhab.cubelens.ui.cube.rememberCubeViewState
import com.andhab.cubelens.ui.cube.viewAnglesFor
import com.andhab.cubelens.ui.theme.Brand

/**
 * "Check your cube": the colors about to be solved, as a draggable 3D preview and a large editable
 * net, with a six-color palette, live per-color counters, a status banner that says in plain words
 * what (if anything) is wrong, undo, and the "Solve it" button, enabled once the cube is valid.
 *
 * Scanned stickers the camera was unsure about carry a subtle amber dot; stickers involved in a
 * problem get a strong red ring, on the net and on the 3D cube. Centers are locked. The 3D preview
 * turns to show the face of the selected sticker.
 *
 * Fits a typical phone without scrolling; the middle part scrolls on short screens while the status
 * and the buttons stay pinned at the bottom.
 *
 * @param onRescan shown as a camera action in the top bar for scanned cubes.
 */
@Composable
fun ReviewScreen(
    review: ReviewState,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onStickerTap: (Int) -> Unit,
    onColorTap: (CubeColor) -> Unit,
    onUndo: () -> Unit,
    onSolve: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = !review.solving
    AuroraBackground(modifier.fillMaxSize(), intensity = 0.85f) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopBar(
                title = stringResource(R.string.review_title),
                onBack = onBack,
                actions = {
                    if (review.source == ReviewSource.Scan) {
                        CircleIconButton(
                            icon = Icons.Rounded.CameraAlt,
                            contentDescription = stringResource(R.string.review_rescan),
                            onClick = onRescan,
                            size = 44.dp,
                        )
                    }
                },
            )
            // Scrolls on short screens; on taller ones the spare height is shared out between the
            // sections instead of pooling above the status banner.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val viewport = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewport)
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    Reveal(index = 0) { ReviewHeader(review) }
                    Spacer(Modifier.height(16.dp))
                    Spacer(Modifier.weight(1f))
                    Reveal(index = 1) {
                        ReviewNet(
                            colors = review.colors,
                            flagged = review.flagged,
                            uncertain = review.uncertain,
                            selected = review.selected,
                            onStickerTap = if (editable) onStickerTap else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Spacer(Modifier.weight(1f))
                    Reveal(index = 2) {
                        ColorPalette(
                            counts = review.counts,
                            brush = review.brush,
                            onColorTap = onColorTap,
                            enabled = editable,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Reveal(index = 3) { Tip(reviewTip(review)) }
                    Spacer(Modifier.height(8.dp))
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                Reveal(index = 4) { Status(reviewStatus(review)) }
                Spacer(Modifier.height(14.dp))
                Reveal(index = 5) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircleIconButton(
                            icon = Icons.AutoMirrored.Rounded.Undo,
                            contentDescription = stringResource(R.string.review_undo),
                            onClick = onUndo,
                            size = ButtonHeight,
                            enabled = review.canUndo && editable,
                        )
                        Spacer(Modifier.width(12.dp))
                        PrimaryButton(
                            text = stringResource(R.string.review_solve),
                            onClick = onSolve,
                            icon = Icons.Rounded.AutoAwesome,
                            enabled = review.canSolve,
                            loading = review.solving,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** The 3D preview next to a short heading, the editing hint and an info pill. */
@Composable
private fun ReviewHeader(review: ReviewState) {
    val scanned = review.source == ReviewSource.Scan
    Row(verticalAlignment = Alignment.CenterVertically) {
        PreviewCube(review, Modifier.size(160.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Overline(stringResource(if (scanned) R.string.review_overline_scan else R.string.review_overline_manual))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(if (scanned) R.string.review_heading_scan else R.string.review_heading_manual),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(if (scanned) R.string.review_body_scan else R.string.review_body_manual),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextSecondary,
            )
            when {
                !scanned -> {
                    Spacer(Modifier.height(10.dp))
                    Pill(
                        text = stringResource(R.string.review_hold_manual),
                        icon = Icons.Rounded.ViewInAr,
                    )
                }
                review.straightenedFaces > 0 -> {
                    Spacer(Modifier.height(10.dp))
                    Pill(
                        text = pluralStringResource(R.plurals.review_straightened, review.straightenedFaces, review.straightenedFaces),
                        icon = Icons.Rounded.AutoFixHigh,
                        color = Brand.Mint,
                    )
                }
            }
        }
    }
}

/**
 * The draggable 3D cube, showing the current colors with problem stickers pulsing. When a sticker is
 * selected, the cube turns to its face and dims the others.
 */
@Composable
private fun PreviewCube(review: ReviewState, modifier: Modifier = Modifier) {
    val state = rememberCubeViewState(review.colors)
    val focusFace = review.selected?.let { Facelets.faceOf(it) }
    LaunchedEffect(focusFace) {
        if (focusFace != null) {
            val (yaw, pitch) = viewAnglesFor(focusFace)
            state.animateView(yaw, pitch)
        }
    }
    Cube3D(
        state = state,
        modifier = modifier,
        interactive = true,
        highlightFacelets = review.flagged,
        focusFace = focusFace,
    )
}

/** The editing tip, cross-fading as it changes. */
@Composable
private fun Tip(text: String) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
        label = "reviewTip",
    ) { tip ->
        Text(
            text = tip,
            style = MaterialTheme.typography.bodySmall,
            color = Brand.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The status banner; changing tone (e.g. from a problem to "ready") swaps it with a soft fade and scale. */
@Composable
private fun Status(status: ReviewStatus) {
    AnimatedContent(
        targetState = status,
        contentKey = { it.kind },
        transitionSpec = {
            (fadeIn(tween(260)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(140)) using
                SizeTransform(clip = false)
        },
        label = "reviewStatus",
    ) { current ->
        Box(Modifier.fillMaxWidth()) {
            StatusBanner(kind = current.kind, title = current.title, message = current.message)
        }
    }
}
