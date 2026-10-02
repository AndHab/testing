package com.andhab.cubelens.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.ui.components.AuroraBackground
import com.andhab.cubelens.ui.components.BannerKind
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
import com.andhab.cubelens.ui.theme.CubePalette
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import com.andhab.cubelens.ui.theme.StickerPalette

/**
 * "Check your cube": the colors about to be solved, for any cube size, as a draggable 3D preview
 * and a large editable net, with a six-color palette, live per-color counters, a status banner that
 * says in plain words what (if anything) is wrong, undo, and the "Solve it" button, enabled once the
 * cube is valid. Every sticker is drawn in [LocalStickerPalette], so a pastel cube shows its own
 * colors (a small chip says so).
 *
 * Scanned stickers the camera was unsure about carry a subtle amber dot; stickers involved in a
 * problem get a strong red ring, on the net (with a "!" badge) and on the 3D cube. Fixed centers carry
 * a lock: the brush skips them, but one selected on purpose can take another color. The 3D
 * preview turns to show the face being edited.
 *
 * Up to 3×3, stickers are tapped right on the net. From 4×4 on they are too small for that: tapping
 * a face opens it big in a [FaceSheet] (see [ReviewState.focusedFace]), with the palette at hand.
 *
 * The palette, the status and the buttons stay pinned at the bottom, so a color is always one tap
 * away. Above them the preview and the net share the remaining height; when that part doesn't fit
 * it scrolls (the selected sticker's face is kept in view). On short screens the header shrinks to
 * one row with a small preview, the net shrinks to fit the height left, so all six faces show at
 * once, and of the editing tip and the status's explanation only the one that matters shows: the
 * explanation of a problem, else the tip. While [ReviewState.confirmingLeave] is set, a sheet asks
 * whether to drop the cube.
 *
 * @param onRescan shown as a camera action in the top bar for scanned cubes.
 * @param onFaceTap a face of a big cube's net was tapped: open it in the face editor.
 * @param onFaceStep move the face editor to the next (+1) or previous (-1) face.
 * @param onFaceClose close the face editor.
 * @param onLeave the user confirmed leaving (dropping the cube).
 * @param onStay the user chose to keep editing instead of leaving.
 */
@Composable
fun ReviewScreen(
    review: ReviewState,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onStickerTap: (Int) -> Unit,
    onColorTap: (CubeColor) -> Unit,
    onFaceTap: (Face) -> Unit,
    onFaceStep: (Int) -> Unit,
    onFaceClose: () -> Unit,
    onUndo: () -> Unit,
    onSolve: () -> Unit,
    onLeave: () -> Unit,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = !review.solving
    // While a sheet is up, it is the only thing on screen for accessibility too.
    val covered = review.confirmingLeave || review.focusedFace != null
    AuroraBackground(modifier.fillMaxSize(), intensity = 0.85f) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            val compact = maxHeight < CompactHeight
            val previewSize = if (compact) CompactPreviewSize else (maxHeight * PreviewHeightFraction).coerceIn(MinPreviewSize, MaxPreviewSize)
            val status = reviewStatus(review)
            // Short screens show either the tip or the explanation of a problem, whichever matters.
            val explainProblem = compact && review.hint == null && (status.kind == BannerKind.Error || status.kind == BannerKind.Warning)
            Column(Modifier.fillMaxSize()) {
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
                ReviewBody(
                    review = review,
                    previewSize = previewSize,
                    compact = compact,
                    onStickerTap = onStickerTap.takeIf { editable && !review.usesFaceEditor },
                    onFaceTap = onFaceTap.takeIf { editable && review.usesFaceEditor },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)) {
                    Reveal(index = 2) {
                        ColorPalette(
                            counts = review.counts,
                            perColor = review.stickersPerColor,
                            filled = review.check !is ReviewCheck.Incomplete,
                            brush = review.brush,
                            onColorTap = onColorTap,
                            enabled = editable,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    if (!explainProblem) {
                        Spacer(Modifier.height(10.dp))
                        Reveal(index = 3) { Tip(reviewTip(review)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Reveal(index = 4) { Status(status, showMessage = !compact || explainProblem) }
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
        FaceSheet(
            review = review,
            onStickerTap = onStickerTap,
            onColorTap = onColorTap,
            onStepFace = onFaceStep,
            onFaceTap = onFaceTap,
            onUndo = onUndo,
            onClose = onFaceClose,
            enabled = editable,
        )
        LeaveSheet(
            visible = review.confirmingLeave,
            source = review.source,
            onLeave = onLeave,
            onStay = onStay,
        )
    }
}

/** Below this height (inside the system bars) the review switches to its compact layout. */
private val CompactHeight = 700.dp

/** Size of the 3D preview relative to the screen height, and its bounds. */
private const val PreviewHeightFraction = 0.19f
private val MinPreviewSize = 112.dp
private val MaxPreviewSize = 160.dp

/** The 3D preview in the compact layout's one-row header. */
private val CompactPreviewSize = 60.dp

/** Height the compact header and the spacing around the net take from the net's share. */
private val CompactHeaderAllowance = CompactPreviewSize + 4.dp + 12.dp + 10.dp

/**
 * The preview and the net, sharing the height above the pinned palette. When they don't fit, this
 * part scrolls, with soft fades at the edges that can scroll, and the preview stops taking drags
 * (they scroll the page instead).
 */
@Composable
private fun ReviewBody(
    review: ReviewState,
    previewSize: Dp,
    compact: Boolean,
    onStickerTap: ((Int) -> Unit)?,
    onFaceTap: ((Face) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // maxValue is Int.MAX_VALUE until the first layout, so the preview starts out still.
    val fits = scroll.maxValue == 0
    BoxWithConstraints(modifier.fadingEdges(scroll)) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .heightIn(min = viewport)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Reveal(index = 0) {
                ReviewHeader(review, previewSize = previewSize, compact = compact, previewInteractive = fits)
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.weight(1f))
            Reveal(index = 1) {
                ReviewNet(
                    colors = review.colors,
                    flagged = review.flagged,
                    uncertain = review.uncertain,
                    locked = review.locked,
                    selected = review.selected,
                    onStickerTap = onStickerTap,
                    onFaceTap = onFaceTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Short screens: the whole net in view, sized to the height left.
                        .then(if (compact) Modifier.heightIn(max = (viewport - CompactHeaderAllowance).coerceAtLeast(MinCompactNet)) else Modifier),
                )
            }
            Spacer(Modifier.height(10.dp))
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The compact net never shrinks below this height; below it, the body scrolls instead. */
private val MinCompactNet = 150.dp

/** Fades content out over [length] at the top and bottom edges, wherever there is more to scroll to. */
private fun Modifier.fadingEdges(scroll: ScrollState, length: Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = length.toPx().coerceAtMost(size.height / 2f)
        if (scroll.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = edge),
                size = Size(size.width, edge),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            val top = size.height - edge
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = top, endY = size.height),
                topLeft = Offset(0f, top),
                size = Size(size.width, edge),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * The 3D preview next to a short heading, the editing hint and info pills: how to hold the cube
 * for manual entry, how many faces were straightened, or how many stickers were hard to read;
 * plus, for a cube drawn in its own colors, a chip saying so. When [compact], just a small preview
 * next to the overline and a one-line heading.
 */
@Composable
private fun ReviewHeader(review: ReviewState, previewSize: Dp, compact: Boolean, previewInteractive: Boolean) {
    val scanned = review.source == ReviewSource.Scan
    val palette = LocalStickerPalette.current
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PreviewCube(review, interactive = previewInteractive, modifier = Modifier.size(previewSize))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Overline(
                    stringResource(
                        if (scanned) R.string.review_overline_scan else R.string.review_overline_manual,
                        stringResource(R.string.cube_size_label, review.n),
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(if (scanned) R.string.review_heading_scan else R.string.review_heading_manual),
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PreviewCube(review, interactive = previewInteractive, modifier = Modifier.size(previewSize))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Overline(
                stringResource(
                    if (scanned) R.string.review_overline_scan else R.string.review_overline_manual,
                    stringResource(R.string.cube_size_label, review.n),
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(if (scanned) R.string.review_heading_scan else R.string.review_heading_manual),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (scanned) {
                    stringResource(R.string.review_body_scan, review.colors.size)
                } else {
                    stringResource(
                        if (review.usesFaceEditor) R.string.review_body_manual_faces else R.string.review_body_manual,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextSecondary,
            )
            val pill = when {
                !scanned -> InfoPill(
                    stringResource(if (review.locked.isEmpty()) R.string.review_hold_manual_even else R.string.review_hold_manual),
                    Icons.Rounded.ViewInAr,
                    Brand.TextSecondary,
                )
                review.straightenedFaces > 0 -> InfoPill(
                    pluralStringResource(R.plurals.review_straightened, review.straightenedFaces, review.straightenedFaces),
                    Icons.Rounded.AutoFixHigh,
                    Brand.Mint,
                )
                review.uncertain.isNotEmpty() -> InfoPill(
                    pluralStringResource(R.plurals.review_hard_to_read, review.uncertain.size, review.uncertain.size),
                    Icons.Rounded.Visibility,
                    Brand.Amber,
                )
                else -> null
            }
            if (pill != null) {
                Spacer(Modifier.height(10.dp))
                Pill(text = pill.text, icon = pill.icon, color = pill.color)
            }
            if (palette != StickerPalette.Standard) {
                Spacer(Modifier.height(8.dp))
                OwnColorsPill(palette)
            }
        }
    }
}

/** The contents of the header's [Pill]. */
private data class InfoPill(val text: String, val icon: ImageVector, val color: Color)

/**
 * "Using your cube's colors": a quiet glass chip led by the cube's own six colors as a fanned row
 * of glossy dots, shown when the cube is drawn in its own (e.g. pastel) colors.
 */
@Composable
private fun OwnColorsPill(palette: StickerPalette) {
    Row(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .background(Brand.TextPrimary.copy(alpha = 0.07f), CircleShape)
            .border(1.dp, Brand.TextPrimary.copy(alpha = 0.16f), CircleShape)
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CubeColor.entries.forEachIndexed { i, color ->
                Box(
                    Modifier
                        .offset(x = DotStep * i)
                        .size(DotSize)
                        .background(CubePalette.Body, CircleShape)
                        .padding(1.5.dp)
                        .background(palette.color(color), CircleShape)
                        .background(DotGloss, CircleShape),
                )
            }
            Spacer(Modifier.width(DotStep * (CubeColor.entries.size - 1) + DotSize))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.review_own_colors),
            style = MaterialTheme.typography.labelMedium,
            color = Brand.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DotSize = 12.dp
private val DotStep = 7.dp
private val DotGloss = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent))

/**
 * The draggable 3D cube, showing the current colors with problem stickers pulsing. When a face is
 * being edited (or a sticker is selected), the cube turns to that face and dims the others.
 */
@Composable
private fun PreviewCube(review: ReviewState, interactive: Boolean, modifier: Modifier = Modifier) {
    val state = rememberCubeViewState(review.colors)
    val focusFace = review.focusedFace ?: review.selected?.let { review.geometry.faceOf(it) }
    LaunchedEffect(focusFace) {
        if (focusFace != null) {
            val (yaw, pitch) = viewAnglesFor(focusFace)
            state.animateView(yaw, pitch)
        }
    }
    Cube3D(
        state = state,
        modifier = modifier,
        interactive = interactive,
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

/**
 * The status banner; changing tone (e.g. from a problem to "ready") swaps it with a soft fade and
 * scale. The banner itself is a polite live region, so screen readers hear every change.
 *
 * @param showMessage false to show the title alone (compact layout).
 */
@Composable
private fun Status(status: ReviewStatus, showMessage: Boolean) {
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
            StatusBanner(
                kind = current.kind,
                title = current.title,
                message = current.message.takeIf { showMessage },
            )
        }
    }
}
