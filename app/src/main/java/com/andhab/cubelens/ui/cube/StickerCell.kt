package com.andhab.cubelens.ui.cube

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import com.andhab.cubelens.ui.theme.StickerFinish
import kotlin.math.min

/**
 * One glossy, optionally tappable sticker of a 2D cube view ([CubeNet], [FaceEditor]).
 *
 * The sticker is drawn in the colors of [LocalStickerPalette], centered in the cell and inset by
 * [insetFraction] of the cell's side on every edge (the whole cell is the touch target). Unknown
 * (`null`) stickers are hollow with a dashed rim, the selected one lifts slightly inside a white
 * ring, and a flagged one gets a [Brand.Danger] ring: on the sticker's edge in the compact style,
 * or separated from the sticker by a dark gap plus a "!" badge in the [roomy] style used where
 * stickers are big.
 *
 * @param onClick called with [index] when tapped; `null` makes the sticker read-only (taps then
 *   reach whatever lies underneath).
 */
@Composable
internal fun StickerCell(
    index: Int,
    color: CubeColor?,
    description: String,
    flagged: Boolean,
    selected: Boolean,
    onClick: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    insetFraction: Float = 0f,
    roomy: Boolean = false,
) {
    val palette = LocalStickerPalette.current
    val fill = palette.color(color)
    val finish = palette.finish(color)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val stickerScale by animateFloatAsState(
        targetValue = when {
            pressed -> PRESSED_SCALE
            selected -> SELECTED_SCALE
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "stickerScale",
    )
    val selection = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "stickerSelection",
    )

    Box(
        modifier
            .zIndex(if (selected) 2f else if (flagged) 1f else 0f)
            .graphicsLayer {
                scaleX = stickerScale
                scaleY = stickerScale
            }
            .semantics {
                contentDescription = description
                this.selected = selected
                if (flagged) stateDescription = "Needs checking"
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Change color",
                    ) { onClick(index) }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val side = (size.minDimension * (1f - 2 * insetFraction)).coerceAtLeast(1f)
                val look = StickerLook(side, finish, this)
                val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
                val center = Offset(size.width / 2, size.height / 2)
                // The selection ring straddles the sticker's edge, so even at SELECTED_SCALE it ends
                // inside the gap to the next sticker. The body shrinks inside it, like a picked swatch.
                val ringWidth = SELECTION_RING_WIDTH.dp.toPx()
                val ring = Stroke(ringWidth)
                val bodyInset = ringWidth / 2 + SELECTION_RING_GAP.dp.toPx()
                val selectedBodyScale = 1f - 2 * bodyInset / side
                onDrawBehind {
                    translate(origin.x, origin.y) {
                        val amount = selection.value
                        if (amount > 0.01f) {
                            drawRoundRect(
                                color = Color.White,
                                size = look.stickerSize,
                                cornerRadius = look.radius,
                                style = ring,
                                alpha = amount.coerceAtMost(1f),
                            )
                        }
                        // Scaling the canvas (rather than the rects) keeps every brush at one size, so
                        // no shader is rebuilt while the selection animates.
                        scale(1f + (selectedBodyScale - 1f) * amount, pivot = Offset(side / 2, side / 2)) {
                            with(look) {
                                if (flagged && roomy) drawGappedFlag(fill, color != null) else drawBody(fill, color != null, flagged)
                            }
                        }
                    }
                    if (flagged && roomy) with(look) { drawFlagBadge(center + Offset(side / 2, -side / 2)) }
                }
            },
    )
}

/**
 * Brushes, strokes and metrics for drawing one sticker of [side] px with [finish], built once per
 * size and color inside `drawWithCache`.
 */
private class StickerLook(private val side: Float, finish: StickerFinish, density: Density) {
    val stickerSize = Size(side, side)
    val radius = CornerRadius(side * STICKER_CORNER)
    private val gloss = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.42f * finish.glossScale),
        0.42f to Color.White.copy(alpha = 0.08f * finish.glossScale),
        0.6f to Color.Transparent,
        start = Offset.Zero,
        end = Offset(side, side),
    )
    private val shade = Brush.linearGradient(
        0.45f to Color.Transparent,
        1f to finish.shadow.copy(alpha = (0.22f * finish.shadeScale).coerceAtMost(1f)),
        start = Offset.Zero,
        end = Offset(side, side),
    )

    // Hairlines scale down with tiny stickers (a 7×7 net) so they never swamp the color.
    private val fineness = min(1f, side / with(density) { 24.dp.toPx() })
    private val rimWidth = with(density) { 1.5.dp.toPx() } * fineness.coerceAtLeast(0.6f)
    private val emptyRim = Stroke(
        rimWidth,
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(with(density) { 4.dp.toPx() } * fineness, with(density) { 3.dp.toPx() } * fineness),
        ),
    )
    private val flagWidth = with(density) { 2.dp.toPx() } * fineness.coerceAtLeast(0.6f)
    private val flagRing = Stroke(flagWidth)

    // Roomy flags: a danger ring separated from the shrunken sticker by an ink gap, plus a badge.
    private val gappedRingWidth = with(density) { 2.5.dp.toPx() }
    private val gappedInset = gappedRingWidth + with(density) { 2.dp.toPx() }
    private val gappedRing = Stroke(gappedRingWidth)
    private val innerScale = 1f - 2 * gappedInset / side
    private val badgeDiameter = (side * 0.42f).coerceIn(with(density) { 12.dp.toPx() }, with(density) { 18.dp.toPx() })
    private val badgeHalo = with(density) { 1.5.dp.toPx() }

    /** The sticker itself: color, shadow and gloss, or a hollow slot; an edge ring when [flagged]. */
    fun DrawScope.drawBody(fill: Color, known: Boolean, flagged: Boolean) {
        if (known) {
            drawRoundRect(fill, size = stickerSize, cornerRadius = radius)
            drawRoundRect(shade, size = stickerSize, cornerRadius = radius)
            drawRoundRect(gloss, size = stickerSize, cornerRadius = radius)
        } else {
            drawRoundRect(EMPTY_FILL, size = stickerSize, cornerRadius = radius)
            drawRoundRect(
                color = EMPTY_RIM,
                topLeft = Offset(rimWidth / 2, rimWidth / 2),
                size = Size(side - rimWidth, side - rimWidth),
                cornerRadius = radius,
                style = emptyRim,
            )
        }
        if (flagged) {
            drawRoundRect(
                color = Brand.Danger,
                topLeft = Offset(flagWidth / 2, flagWidth / 2),
                size = Size(side - flagWidth, side - flagWidth),
                cornerRadius = radius,
                style = flagRing,
            )
        }
    }

    /**
     * A flagged sticker that never relies on color alone: shrunk inside an ink gap and a
     * [Brand.Danger] ring, so the ring reads on any sticker color, red and orange included.
     */
    fun DrawScope.drawGappedFlag(fill: Color, known: Boolean) {
        drawRoundRect(Brand.Ink, size = stickerSize, cornerRadius = radius)
        drawRoundRect(
            color = Brand.Danger,
            topLeft = Offset(gappedRingWidth / 2, gappedRingWidth / 2),
            size = Size(side - gappedRingWidth, side - gappedRingWidth),
            cornerRadius = CornerRadius(radius.x - gappedRingWidth / 2),
            style = gappedRing,
        )
        scale(innerScale, pivot = Offset(side / 2, side / 2)) { drawBody(fill, known, flagged = false) }
    }

    /** A round "!" badge centered near the sticker's top-right [corner]. */
    fun DrawScope.drawFlagBadge(corner: Offset) {
        val r = badgeDiameter / 2
        val center = corner + Offset(-r * 0.45f, r * 0.45f)
        drawCircle(Brand.Ink, radius = r + badgeHalo, center = center)
        drawCircle(Brand.Danger, radius = r, center = center)
        val stroke = badgeDiameter * 0.15f
        drawLine(
            color = Brand.OnAccent,
            start = center + Offset(0f, -badgeDiameter * 0.24f),
            end = center + Offset(0f, badgeDiameter * 0.05f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(Brand.OnAccent, radius = stroke * 0.58f, center = center + Offset(0f, badgeDiameter * 0.24f))
    }
}

/** Corner radius of a 2D sticker, as a fraction of its side. */
internal const val STICKER_CORNER = 0.2f

/** Sticker scale while pressed and while selected (DESIGN.md: gentle, springy). */
private const val PRESSED_SCALE = 0.92f
private const val SELECTED_SCALE = 1.06f

/** Width of the white ring around the selected sticker, and its gap to the sticker body, in dp. */
private const val SELECTION_RING_WIDTH = 2f
private const val SELECTION_RING_GAP = 1.25f

internal val EMPTY_FILL = Color(0xFF1B1B24)
internal val EMPTY_RIM = Color(0xFF6F6A86)
