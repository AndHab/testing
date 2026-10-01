package com.andhab.cubelens.ui.cube

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The cube unfolded in the standard cross: U above F, the row L F R B, and D below F.
 *
 * The net fills the available width (or the available height, if that is the tighter fit). Each face
 * sits on a dark glass plate; stickers are rounded and glossy, unknown (`null`) stickers are hollow
 * with a dashed rim, flagged stickers pulse with a [Brand.Danger] ring and the selected sticker pops
 * forward with a white ring. When [onStickerClick] is set, stickers are buttons with a springy press.
 *
 * @param colors 54 sticker colors in facelet order.
 * @param highlightFacelets stickers to flag (e.g. [com.andhab.cubelens.core.cube.ValidationResult.flaggedFacelets]).
 * @param selectedFacelet the sticker currently being edited, if any.
 * @param onStickerClick called with the facelet index of a tapped sticker; `null` makes the net read-only.
 */
@Composable
fun CubeNet(
    colors: List<CubeColor?>,
    modifier: Modifier = Modifier,
    highlightFacelets: Set<Int> = emptySet(),
    selectedFacelet: Int? = null,
    onStickerClick: ((Int) -> Unit)? = null,
) {
    require(colors.size == Facelets.COUNT) { "Need ${Facelets.COUNT} sticker colors, got ${colors.size}" }
    val pulse = if (highlightFacelets.isNotEmpty()) rememberHighlightPulse() else null
    // Written by the measure pass, read by the draw pass (which always follows it).
    val measured = remember { MeasuredNet() }

    Layout(
        content = {
            for (i in 0 until Facelets.COUNT) {
                NetSticker(
                    index = i,
                    color = colors[i],
                    flagged = i in highlightFacelets,
                    selected = i == selectedFacelet,
                    onClick = onStickerClick,
                )
            }
        },
        modifier = modifier.drawBehind {
            val metrics = measured.metrics ?: return@drawBehind
            drawFacePlates(metrics)
            // Glows sit behind every sticker so they only bleed into the gaps, never over a neighbor.
            if (pulse != null) {
                for (i in highlightFacelets) {
                    if (i !in 0 until Facelets.COUNT) continue
                    drawFlagGlow(metrics, i, pulse.value)
                }
            }
        },
    ) { measurables, constraints ->
        val metrics = NetMetrics.fit(constraints)
        measured.metrics = metrics
        val sticker = metrics.sticker.roundToInt().coerceAtLeast(1)
        val placeables = measurables.map { it.measure(Constraints.fixed(sticker, sticker)) }
        layout(metrics.width.roundToInt(), metrics.height.roundToInt()) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(metrics.stickerX(i).roundToInt(), metrics.stickerY(i).roundToInt())
            }
        }
    }
}

/**
 * A single face as a 3x3 thumbnail on a dark plate (e.g. the strip of scanned faces).
 *
 * @param colors the nine stickers, row-major as in [Facelets]; `null` stickers are hollow.
 * @param active highlights the face with a softly breathing sunset outline and glow.
 */
@Composable
fun FaceGrid(colors: List<CubeColor?>, modifier: Modifier = Modifier, active: Boolean = false) {
    require(colors.size == 9) { "A face has 9 stickers, got ${colors.size}" }
    val activeAmount by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "faceGridActive",
    )
    val breathe = if (active) {
        rememberInfiniteTransition(label = "faceGridBreathe").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe",
        )
    } else {
        null
    }
    val description = remember(colors) {
        val known = colors.count { it != null }
        if (known == 0) "Face not scanned yet" else "Face with ${colors.joinToString { it?.displayName ?: "unknown" }}"
    }
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description },
    ) {
        val glow = activeAmount * (0.72f + 0.28f * (breathe?.value ?: 1f))
        drawFaceThumbnail(colors, glow, activeAmount)
    }
}

/** Shared pulse for flagged stickers: 1 → 0 → 1, eased. */
@Composable
internal fun rememberHighlightPulse(): State<Float> =
    rememberInfiniteTransition(label = "highlightPulse").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(720, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

@Composable
private fun NetSticker(
    index: Int,
    color: CubeColor?,
    flagged: Boolean,
    selected: Boolean,
    onClick: ((Int) -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.82f
            selected -> 1.12f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 650f),
        label = "stickerScale",
    )
    val selection by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
        label = "stickerSelection",
    )
    val face = Facelets.faceOf(index)
    val description = "${face.friendlyName} face, row ${Facelets.rowOf(index) + 1}, column ${Facelets.colOf(index) + 1}: " +
        (color?.displayName ?: "empty")

    Box(
        Modifier
            .zIndex(if (selected) 2f else if (flagged) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
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
                val radius = CornerRadius(size.minDimension * STICKER_CORNER)
                val gloss = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.42f),
                    0.42f to Color.White.copy(alpha = 0.08f),
                    0.6f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                val shade = Brush.linearGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.22f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                onDrawBehind {
                    if (selection > 0.01f) drawSelectionRing(radius, selection)
                    if (color != null) {
                        drawRoundRect(CubePalette.color(color), cornerRadius = radius)
                        drawRoundRect(shade, cornerRadius = radius)
                        drawRoundRect(gloss, cornerRadius = radius)
                    } else {
                        drawRoundRect(EMPTY_FILL, cornerRadius = radius)
                        val w = 1.5.dp.toPx()
                        drawRoundRect(
                            color = EMPTY_RIM,
                            topLeft = Offset(w / 2, w / 2),
                            size = Size(size.width - w, size.height - w),
                            cornerRadius = radius,
                            style = Stroke(width = w, pathEffect = dash),
                        )
                    }
                    if (flagged) {
                        val w = 2.dp.toPx()
                        drawRoundRect(
                            color = Brand.Danger,
                            topLeft = Offset(w / 2, w / 2),
                            size = Size(size.width - w, size.height - w),
                            cornerRadius = radius,
                            style = Stroke(width = w),
                        )
                    }
                }
            },
    )
}

/** Pulsing danger glow around flagged sticker [index], drawn by the net behind all stickers. */
private fun DrawScope.drawFlagGlow(metrics: NetMetrics, index: Int, pulse: Float) {
    val s = metrics.sticker
    drawGlow(
        brush = DangerGlowBrush,
        topLeft = Offset(metrics.stickerX(index), metrics.stickerY(index)),
        size = Size(s, s),
        cornerRadius = s * STICKER_CORNER,
        spread = 9.dp.toPx() * (0.7f + 0.3f * pulse),
        alpha = 0.9f * (0.4f + 0.6f * pulse),
    )
}

/**
 * A soft glow hugging a rounded rect: many thin concentric strokes whose coverage accumulates into a
 * smooth falloff (works on every API level, no blur effects needed).
 *
 * @param alpha opacity right at the edge, fading linearly to zero at [spread].
 */
internal fun DrawScope.drawGlow(
    brush: Brush,
    topLeft: Offset,
    size: Size,
    cornerRadius: Float,
    spread: Float,
    alpha: Float,
) {
    if (spread <= 0f || alpha <= 0f) return
    val ring = alpha / GLOW_RINGS
    for (i in 1..GLOW_RINGS) {
        val s = spread * i / GLOW_RINGS
        drawRoundRect(
            brush = brush,
            topLeft = topLeft - Offset(s / 2, s / 2),
            size = Size(size.width + s, size.height + s),
            cornerRadius = CornerRadius(cornerRadius + s / 2),
            style = Stroke(width = s),
            alpha = ring,
        )
    }
}

/** White ring with a soft halo around the selected sticker. */
private fun DrawScope.drawSelectionRing(radius: CornerRadius, amount: Float) {
    val gap = 3.dp.toPx()
    val w = 2.dp.toPx()
    val outer = gap + w / 2
    drawRoundRect(
        color = Color.White.copy(alpha = 0.16f * amount),
        topLeft = Offset(-outer - w, -outer - w),
        size = Size(size.width + 2 * (outer + w), size.height + 2 * (outer + w)),
        cornerRadius = CornerRadius(radius.x + outer + w),
        style = Stroke(width = w * 2.5f),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = amount),
        topLeft = Offset(-outer, -outer),
        size = Size(size.width + 2 * outer, size.height + 2 * outer),
        cornerRadius = CornerRadius(radius.x + outer),
        style = Stroke(width = w),
    )
}

/** Dark glass plates behind the six faces of the net. */
private fun DrawScope.drawFacePlates(m: NetMetrics) {
    val radius = CornerRadius(m.plate * PLATE_CORNER)
    val hairline = 1.dp.toPx()
    for (face in Face.entries) {
        val topLeft = Offset(m.plateX(face), m.plateY(face))
        val plateSize = Size(m.plate, m.plate)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(PLATE_TOP, PLATE_BOTTOM), startY = topLeft.y, endY = topLeft.y + m.plate),
            topLeft = topLeft,
            size = plateSize,
            cornerRadius = radius,
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Brand.HairlineStrong, Brand.Hairline.copy(alpha = 0.04f)),
                startY = topLeft.y,
                endY = topLeft.y + m.plate,
            ),
            topLeft = topLeft + Offset(hairline / 2, hairline / 2),
            size = Size(m.plate - hairline, m.plate - hairline),
            cornerRadius = radius,
            style = Stroke(width = hairline),
        )
    }
}

private fun DrawScope.drawFaceThumbnail(colors: List<CubeColor?>, glow: Float, active: Float) {
    val inset = size.minDimension * 0.09f
    val plate = size.minDimension - 2 * inset
    val topLeft = Offset(inset, inset)
    val plateRadius = CornerRadius(plate * PLATE_CORNER)
    val plateSize = Size(plate, plate)

    if (glow > 0.01f) {
        drawGlow(WarmGlowBrush, topLeft, plateSize, plateRadius.x, spread = inset * 1.05f, alpha = 0.62f * glow)
    }
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(PLATE_TOP, PLATE_BOTTOM), startY = inset, endY = inset + plate),
        topLeft = topLeft,
        size = plateSize,
        cornerRadius = plateRadius,
    )
    val border = 1.dp.toPx() + 1.dp.toPx() * active
    drawRoundRect(
        color = Brand.HairlineStrong,
        topLeft = topLeft + Offset(border / 2, border / 2),
        size = Size(plate - border, plate - border),
        cornerRadius = plateRadius,
        style = Stroke(width = border),
        alpha = 1f - active,
    )
    if (active > 0.01f) {
        drawRoundRect(
            brush = Brand.SunsetBrush,
            topLeft = topLeft + Offset(border / 2, border / 2),
            size = Size(plate - border, plate - border),
            cornerRadius = plateRadius,
            style = Stroke(width = border),
            alpha = active,
        )
    }

    // 3x3 stickers: padding p, sticker s, gap g with 2p + 3s + 2g = plate.
    val unit = plate / (3f + 2 * GRID_GAP + 2 * GRID_PADDING)
    val s = unit
    val g = unit * GRID_GAP
    val p = unit * GRID_PADDING
    val radius = CornerRadius(s * STICKER_CORNER)
    val dash = PathEffect.dashPathEffect(floatArrayOf(s * 0.16f, s * 0.12f))
    for (i in 0 until 9) {
        val x = inset + p + (i % 3) * (s + g)
        val y = inset + p + (i / 3) * (s + g)
        val color = colors[i]
        if (color != null) {
            drawRoundRect(CubePalette.color(color), Offset(x, y), Size(s, s), radius)
            drawRoundRect(
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.4f),
                    0.42f to Color.White.copy(alpha = 0.06f),
                    0.6f to Color.Transparent,
                    start = Offset(x, y),
                    end = Offset(x + s, y + s),
                ),
                topLeft = Offset(x, y),
                size = Size(s, s),
                cornerRadius = radius,
            )
        } else {
            drawRoundRect(EMPTY_FILL, Offset(x, y), Size(s, s), radius)
            val w = (s * 0.06f).coerceAtLeast(1f)
            drawRoundRect(
                color = EMPTY_RIM,
                topLeft = Offset(x + w / 2, y + w / 2),
                size = Size(s - w, s - w),
                cornerRadius = radius,
                style = Stroke(width = w, pathEffect = dash),
            )
        }
    }
}

/**
 * Geometry of the net in pixels, all derived from the sticker size: stickers are separated by a
 * small gap, sit inside a padded plate per face, and plates are separated by a wider gap.
 */
internal class NetMetrics private constructor(val sticker: Float) {
    val gap = sticker * NET_GAP
    val padding = sticker * NET_PADDING
    val faceGap = sticker * NET_FACE_GAP
    val plate = 3 * sticker + 2 * gap + 2 * padding
    val width = 4 * plate + 3 * faceGap
    val height = 3 * plate + 2 * faceGap

    fun plateX(face: Face): Float = layoutColumn(face) * (plate + faceGap)
    fun plateY(face: Face): Float = layoutRow(face) * (plate + faceGap)
    fun stickerX(index: Int): Float = plateX(Facelets.faceOf(index)) + padding + Facelets.colOf(index) * (sticker + gap)
    fun stickerY(index: Int): Float = plateY(Facelets.faceOf(index)) + padding + Facelets.rowOf(index) * (sticker + gap)

    companion object {
        /** Width of the net in sticker units. */
        private val WIDTH_UNITS = 4 * (3 + 2 * NET_GAP + 2 * NET_PADDING) + 3 * NET_FACE_GAP

        /** Height of the net in sticker units. */
        private val HEIGHT_UNITS = 3 * (3 + 2 * NET_GAP + 2 * NET_PADDING) + 2 * NET_FACE_GAP

        /** Largest net that fits [constraints]; uses the width when the height is unbounded. */
        fun fit(constraints: Constraints): NetMetrics {
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else DEFAULT_WIDTH_PX
            val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.MAX_VALUE
            return forSize(maxW, maxH)
        }

        /** Largest net that fits in [width] x [height] pixels, with a whole-pixel sticker size. */
        fun forSize(width: Float, height: Float): NetMetrics {
            val s = min(width / WIDTH_UNITS, height / HEIGHT_UNITS)
            return NetMetrics(floor(s).coerceAtLeast(1f))
        }

        private const val DEFAULT_WIDTH_PX = 1080f

        fun layoutColumn(face: Face): Int = when (face) {
            Face.L -> 0
            Face.U, Face.F, Face.D -> 1
            Face.R -> 2
            Face.B -> 3
        }

        fun layoutRow(face: Face): Int = when (face) {
            Face.U -> 0
            Face.L, Face.F, Face.R, Face.B -> 1
            Face.D -> 2
        }
    }
}

/** Holder for the metrics of the last measure pass of a [CubeNet]. */
private class MeasuredNet {
    var metrics: NetMetrics? = null
}

/** Plain-language face names for accessibility (no notation jargon). */
internal val Face.friendlyName: String
    get() = when (this) {
        Face.U -> "Top"
        Face.D -> "Bottom"
        Face.F -> "Front"
        Face.B -> "Back"
        Face.L -> "Left"
        Face.R -> "Right"
    }

private const val NET_GAP = 0.1f
private const val NET_PADDING = 0.16f
private const val NET_FACE_GAP = 0.22f
private const val GRID_GAP = 0.1f
private const val GRID_PADDING = 0.16f
private const val STICKER_CORNER = 0.2f
private const val PLATE_CORNER = 0.13f

private const val GLOW_RINGS = 10

private val DangerGlowBrush = SolidColor(Brand.Danger)

/** Glow hues stay in magenta/coral: low-alpha orange over ink reads as brown. */
private val WarmGlowBrush = Brush.linearGradient(listOf(Brand.Magenta, Brand.Coral, Brand.Magenta))

private val PLATE_TOP = Color(0xFF191921)
private val PLATE_BOTTOM = Color(0xFF0E0E14)
private val EMPTY_FILL = Color(0xFF1B1B24)
private val EMPTY_RIM = Color(0xFF6F6A86)
