package com.andhab.cubelens.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.ui.components.ButtonHeight
import com.andhab.cubelens.ui.components.CircleIconButton
import com.andhab.cubelens.ui.components.HeroCardShape
import com.andhab.cubelens.ui.components.SecondaryButton
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.glassSurface
import com.andhab.cubelens.ui.cube.EditorMetrics
import com.andhab.cubelens.ui.cube.FaceEditor
import com.andhab.cubelens.ui.cube.FaceGrid
import com.andhab.cubelens.ui.theme.Brand

/**
 * The face editor of a big cube: one face of [review] at a size that is comfortable to tap, on a
 * sheet that rises from the bottom over a dimmed screen while [ReviewState.focusedFace] is set.
 *
 * The sheet shows the face's name over live thumbnails of all six faces (tap one to jump there),
 * between buttons to the previous and next face; then the face as a big N×N grid (fixed centers
 * locked, unsure stickers with an amber dot, problems ringed in red), the six color swatches with
 * their live counters, the editing tip, undo and "Done". Both ways of editing work as on the net: select a sticker, then a color; or
 * pick a color up and paint. When manual entry hops on to another face, the sheet slides over to
 * it. Tapping the dim area closes the sheet too.
 *
 * Fills its [BoxScope] parent; place it after the screen's content so it draws on top.
 *
 * @param onStickerTap called with the cube-wide index of a tapped sticker.
 * @param onStepFace called with +1 for the next face and -1 for the previous one.
 * @param onFaceTap called with a face picked from the strip of all six.
 * @param enabled false while solving: nothing reacts to taps.
 */
@Composable
internal fun BoxScope.FaceSheet(
    review: ReviewState,
    onStickerTap: (Int) -> Unit,
    onColorTap: (CubeColor) -> Unit,
    onStepFace: (Int) -> Unit,
    onFaceTap: (Face) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    enabled: Boolean = true,
) {
    val visible = review.focusedFace != null
    // Keeps showing the last face while the sheet slides away.
    val shown = remember { ShownFace() }
    val face = review.focusedFace ?: shown.face
    shown.face = face

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
        label = "faceScrim",
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brand.Ink.copy(alpha = ScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.review_face_done),
                    onClick = onClose,
                ),
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(340)) { it / 2 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(220)) { it / 2 } + fadeOut(tween(160)),
        label = "faceSheet",
    ) {
        val title = faceName(face)
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(12.dp)
                .fillMaxWidth()
                .drawBehind {
                    // Warm light spilling from behind the sheet's top edge.
                    drawSoftGlow(
                        Brand.Magenta,
                        alpha = 0.16f,
                        center = Offset(size.width / 2f, 0f),
                        radiusX = size.width * 0.6f,
                        radiusY = size.height * 0.3f,
                    )
                }
                .glassSurface(HeroCardShape, fill = Brand.SurfaceHigh)
                .semantics { paneTitle = title }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetHeader(
                review = review,
                face = face,
                title = title,
                onStepFace = onStepFace,
                onFaceTap = onFaceTap,
                enabled = enabled,
            )
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = face,
                transitionSpec = {
                    val order = ReviewState.FACE_ORDER
                    val direction = if (order.indexOf(targetState) >= order.indexOf(initialState)) 1 else -1
                    (slideInHorizontally(tween(300)) { direction * it / 3 } + fadeIn(tween(240, delayMillis = 40))) togetherWith
                        (slideOutHorizontally(tween(260)) { -direction * it / 3 } + fadeOut(tween(160)))
                },
                // Shrinks the grid on short screens so everything else keeps its height.
                modifier = Modifier.weight(1f, fill = false),
                contentAlignment = Alignment.Center,
                label = "faceEditorFace",
            ) { current ->
                FaceGridEditor(
                    review = review,
                    face = current,
                    onStickerTap = if (enabled) onStickerTap else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(14.dp))
            ColorPalette(
                counts = review.counts,
                perColor = review.stickersPerColor,
                brush = review.brush,
                onColorTap = onColorTap,
                enabled = enabled,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = reviewTip(review),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = stringResource(R.string.review_undo),
                    onClick = onUndo,
                    size = ButtonHeight,
                    enabled = review.canUndo && enabled,
                )
                Spacer(Modifier.width(12.dp))
                SecondaryButton(
                    text = stringResource(R.string.review_face_done),
                    onClick = onClose,
                    icon = Icons.Rounded.Check,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Dims the screen behind the sheet, lightly enough that the 3D preview still shows the face. */
private const val ScrimAlpha = 0.62f

/** The face the sheet showed last, so it can keep showing it while sliding away. */
private class ShownFace {
    var face: Face = Face.U
}

/**
 * "Front face" over a strip of all six faces as live thumbnails (the open one ringed in sunset;
 * tap one to jump there), between previous and next buttons.
 */
@Composable
private fun SheetHeader(
    review: ReviewState,
    face: Face,
    title: String,
    onStepFace: (Int) -> Unit,
    onFaceTap: (Face) -> Unit,
    enabled: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.review_face_previous),
            onClick = { onStepFace(-1) },
            size = 44.dp,
            enabled = enabled,
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            FaceStrip(review = review, current = face, onFaceTap = onFaceTap, enabled = enabled)
        }
        CircleIconButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = stringResource(R.string.review_face_next),
            onClick = { onStepFace(1) },
            size = 44.dp,
            enabled = enabled,
        )
    }
}

/**
 * The six faces of [review] in [ReviewState.FACE_ORDER] as small live thumbnails, so progress on
 * every face shows at a glance; [current] wears the sunset ring. Each is a tab that opens its face.
 */
@Composable
private fun FaceStrip(review: ReviewState, current: Face, onFaceTap: (Face) -> Unit, enabled: Boolean) {
    val perFace = review.n * review.n
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(StripGap),
    ) {
        for (face in ReviewState.FACE_ORDER) {
            val name = faceName(face)
            val first = face.ordinal * perFace
            Box(
                Modifier
                    .size(StripThumbnail)
                    .selectable(
                        selected = face == current,
                        enabled = enabled,
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onFaceTap(face) }
                    // The tab's name replaces the thumbnail's long list of colors.
                    .clearAndSetSemantics { contentDescription = name },
            ) {
                FaceGrid(
                    colors = review.colors.subList(first, first + perFace),
                    active = face == current,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Size of one face thumbnail in the sheet's header, and the gap between them. */
private val StripThumbnail = 30.dp
private val StripGap = 4.dp

/**
 * [face] of [review] as a [FaceEditor] centered in the available width, with the same marks as the
 * net drawn on top: a lock on the fixed center and an amber dot on each unsure sticker.
 */
@Composable
private fun FaceGridEditor(
    review: ReviewState,
    face: Face,
    onStickerTap: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val n = review.n
    val first = face.ordinal * n * n
    val stickers = first until first + n * n
    val colors = review.colors.subList(first, first + n * n)
    val flagged = review.flagged.local(stickers)
    val dots = review.uncertain.local(stickers) - flagged
    val locked = review.locked.local(stickers)
    val selected = review.selected?.takeIf { it in stickers }?.minus(first)
    val lock = rememberVectorPainter(Icons.Rounded.Lock)
    val placement = remember { EditorPlacement() }

    Layout(
        content = {
            FaceEditor(
                colors = colors,
                highlightStickers = flagged,
                selectedSticker = selected,
                onStickerClick = onStickerTap?.let { tap -> { local: Int -> tap(first + local) } },
            )
        },
        modifier = modifier.drawWithContent {
            drawContent()
            val metrics = placement.metrics ?: return@drawWithContent
            translate(left = placement.offsetX) {
                for (index in locked) {
                    drawLock(lock, Offset(metrics.stickerX(index), metrics.stickerY(index)), metrics.sticker)
                }
                val radius = (metrics.sticker * DOT_FRACTION).coerceAtMost(EditorDotRadius.toPx())
                val inset = (metrics.sticker * DOT_INSET_FRACTION).coerceAtMost(EditorDotInset.toPx())
                for (index in dots) {
                    val corner = Offset(metrics.stickerX(index) + metrics.sticker, metrics.stickerY(index))
                    drawUncertainDot(corner + Offset(-radius - inset, radius + inset), radius)
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // The same metrics FaceEditor computes from the same constraints, so the marks line up exactly.
        val metrics = EditorMetrics.fit(loose, n, this)
        val editor = measurables.single().measure(loose)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else editor.width
        val offsetX = (width - editor.width) / 2
        placement.metrics = metrics
        placement.offsetX = offsetX.toFloat()
        layout(width, editor.height) { editor.place(offsetX, 0) }
    }
}

/** The face-local indices (0 until N²) of those of these cube-wide stickers that lie in [face]. */
private fun Set<Int>.local(face: IntRange): Set<Int> = filterTo(mutableSetOf()) { it in face }.mapTo(mutableSetOf()) { it - face.first }

/** Where the editor was last laid out; written by the measure pass and read by the draw pass. */
private class EditorPlacement {
    var metrics: EditorMetrics? = null
    var offsetX: Float = 0f
}

/** The uncertain dot is a little bigger on the editor's big stickers than on the net. */
private val EditorDotRadius = 5.dp
private val EditorDotInset = 4.dp
