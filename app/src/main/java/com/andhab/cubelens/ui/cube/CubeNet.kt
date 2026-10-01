package com.andhab.cubelens.ui.cube

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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
 * with a dashed rim, flagged stickers pulse with a soft [Brand.Danger] glow and ring, and the
 * selected sticker lifts slightly inside a white ring. When [onStickerClick] is set, stickers are
 * buttons with a springy press.
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
    // An array, so the per-frame glow loop needs no iterator.
    val flagged = remember(highlightFacelets) {
        highlightFacelets.filter { it in 0 until Facelets.COUNT }.sorted().toIntArray()
    }
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
        modifier = modifier.drawWithCache {
            // The net's size follows one-to-one from its metrics, so this size-keyed cache always
            // matches the latest measure pass.
            val metrics = measured.metrics ?: return@drawWithCache onDrawBehind {}
            val plates = NetPlates(metrics, this)
            val glow = if (flagged.isEmpty()) {
                null
            } else {
                GlowSprite.create(
                    shape = Size(metrics.sticker, metrics.sticker),
                    cornerRadius = metrics.sticker * STICKER_CORNER,
                    spread = FLAG_GLOW_SPREAD.dp.toPx(),
                    colors = listOf(Brand.Danger),
                )
            }
            onDrawBehind {
                with(plates) { drawPlates() }
                // Glows sit behind every sticker so they only bleed into the gaps, never over a neighbor.
                if (glow != null && pulse != null) {
                    val p = pulse.value
                    for (i in flagged) {
                        drawGlow(
                            sprite = glow,
                            shapeTopLeft = Offset(metrics.stickerX(i), metrics.stickerY(i)),
                            alpha = 0.55f + 0.45f * p,
                            spreadFraction = 0.72f + 0.28f * p,
                        )
                    }
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
 * @param active highlights the face with a sunset outline and a softly breathing glow. The glow
 *   stays within the composable's bounds, so a clipping parent will not cut it off.
 */
@Composable
fun FaceGrid(colors: List<CubeColor?>, modifier: Modifier = Modifier, active: Boolean = false) {
    require(colors.size == 9) { "A face has 9 stickers, got ${colors.size}" }
    val activeAmount = animateFloatAsState(
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
    // Outlives the draw cache, which is rebuilt whenever active or colors change, so the brushes
    // and the glow sprite are only rebuilt when the size does.
    val thumbnails = remember { FaceThumbnailHolder() }
    Spacer(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .drawWithCache {
                val thumbnail = thumbnails.forSize(size, this)
                onDrawBehind {
                    val amount = activeAmount.value
                    val glow = amount * (0.7f + 0.3f * (breathe?.value ?: 1f))
                    with(thumbnail) { drawThumbnail(colors, glow, amount) }
                }
            },
    )
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
    val face = Facelets.faceOf(index)
    val description = "${face.friendlyName} face, row ${Facelets.rowOf(index) + 1}, column ${Facelets.colOf(index) + 1}: " +
        (color?.displayName ?: "empty")

    Box(
        Modifier
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
                val rimWidth = 1.5.dp.toPx()
                val emptyRim = Stroke(rimWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                val flagWidth = 2.dp.toPx()
                val flagRing = Stroke(flagWidth)
                // The selection ring straddles the sticker's edge, so even at SELECTED_SCALE it ends
                // inside the gap to the next sticker. The body shrinks inside it, like a picked swatch.
                val ringWidth = SELECTION_RING_WIDTH.dp.toPx()
                val ring = Stroke(ringWidth)
                val bodyInset = ringWidth / 2 + SELECTION_RING_GAP.dp.toPx()
                val selectedBodyScale = 1f - 2 * bodyInset / size.minDimension
                onDrawBehind {
                    val amount = selection.value
                    if (amount > 0.01f) {
                        drawRoundRect(Color.White, cornerRadius = radius, style = ring, alpha = amount.coerceAtMost(1f))
                    }
                    // Scaling the canvas (rather than the rects) keeps every brush at one size, so
                    // no shader is rebuilt while the selection animates.
                    scale(1f + (selectedBodyScale - 1f) * amount) {
                        if (color != null) {
                            drawRoundRect(CubePalette.color(color), cornerRadius = radius)
                            drawRoundRect(shade, cornerRadius = radius)
                            drawRoundRect(gloss, cornerRadius = radius)
                        } else {
                            drawRoundRect(EMPTY_FILL, cornerRadius = radius)
                            drawRoundRect(
                                color = EMPTY_RIM,
                                topLeft = Offset(rimWidth / 2, rimWidth / 2),
                                size = Size(size.width - rimWidth, size.height - rimWidth),
                                cornerRadius = radius,
                                style = emptyRim,
                            )
                        }
                        if (flagged) {
                            drawRoundRect(
                                color = Brand.Danger,
                                topLeft = Offset(flagWidth / 2, flagWidth / 2),
                                size = Size(size.width - flagWidth, size.height - flagWidth),
                                cornerRadius = radius,
                                style = flagRing,
                            )
                        }
                    }
                }
            },
    )
}

/** The dark glass plates behind the six faces of a net, with their brushes built once per size. */
private class NetPlates(metrics: NetMetrics, density: Density) {
    private val plateSize = Size(metrics.plate, metrics.plate)
    private val radius = CornerRadius(metrics.plate * PLATE_CORNER)
    private val fill = Brush.verticalGradient(PLATE_COLORS, startY = 0f, endY = metrics.plate)
    private val rimWidth = with(density) { 1.dp.toPx() }
    private val rim = Stroke(rimWidth)
    private val rimBrush = Brush.verticalGradient(RIM_COLORS, startY = 0f, endY = metrics.plate)

    /** Top-left corner of each face's plate as (x, y) pairs, in [Face] order. */
    private val origins = FloatArray(2 * Face.entries.size).also { origins ->
        for (face in Face.entries) {
            origins[2 * face.ordinal] = metrics.plateX(face)
            origins[2 * face.ordinal + 1] = metrics.plateY(face)
        }
    }

    fun DrawScope.drawPlates() {
        for (f in 0 until origins.size / 2) {
            translate(origins[2 * f], origins[2 * f + 1]) {
                drawRoundRect(fill, size = plateSize, cornerRadius = radius)
                drawRoundRect(
                    brush = rimBrush,
                    topLeft = Offset(rimWidth / 2, rimWidth / 2),
                    size = Size(plateSize.width - rimWidth, plateSize.height - rimWidth),
                    cornerRadius = radius,
                    style = rim,
                )
            }
        }
    }
}

/** The [FaceThumbnail] of a [FaceGrid]'s latest size and density. */
private class FaceThumbnailHolder {
    private var current: FaceThumbnail? = null

    fun forSize(size: Size, density: Density): FaceThumbnail =
        current?.takeIf { it.size == size && it.pixelDensity == density.density }
            ?: FaceThumbnail(size, density).also { current = it }
}

/**
 * Everything a [FaceGrid] of one size draws with: plate, border, sticker layout, brushes and strokes.
 * Built once per size, so a breathing active thumbnail allocates nothing per frame.
 */
private class FaceThumbnail(val size: Size, density: Density) {
    val pixelDensity = density.density
    private val inset = size.minDimension * THUMBNAIL_INSET
    private val plate = size.minDimension - 2 * inset
    private val plateTopLeft = Offset(inset, inset)
    private val plateSize = Size(plate, plate)
    private val plateRadius = CornerRadius(plate * PLATE_CORNER)
    private val plateFill = Brush.verticalGradient(PLATE_COLORS, startY = inset, endY = inset + plate)

    private val hairlineWidth = with(density) { 1.dp.toPx() }
    private val hairline = Stroke(hairlineWidth)
    private val ringWidth = with(density) { 2.dp.toPx() }
    private val ring = Stroke(ringWidth)
    private val ringBrush = Brush.linearGradient(Brand.SunsetColors, start = plateTopLeft, end = plateTopLeft + Offset(plate, plate))

    // 3x3 stickers: padding p, sticker s, gap g with 2p + 3s + 2g = plate.
    private val sticker = plate / (3f + 2 * GRID_GAP + 2 * GRID_PADDING)
    private val stickerStep = sticker * (1 + GRID_GAP)
    private val stickerOrigin = inset + sticker * GRID_PADDING
    private val stickerSize = Size(sticker, sticker)
    private val stickerRadius = CornerRadius(sticker * STICKER_CORNER)
    private val gloss = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.4f),
        0.42f to Color.White.copy(alpha = 0.06f),
        0.6f to Color.Transparent,
        start = Offset.Zero,
        end = Offset(sticker, sticker),
    )
    private val emptyRimWidth = (sticker * 0.06f).coerceAtLeast(1f)
    private val emptyRim = Stroke(emptyRimWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sticker * 0.16f, sticker * 0.12f)))

    /** Built on first use: most thumbnails are never active. */
    private var glowSprite: GlowSprite? = null

    private fun glow(): GlowSprite = glowSprite ?: GlowSprite.create(
        shape = plateSize,
        cornerRadius = plateRadius.x,
        spread = inset,
        colors = WARM_GLOW_COLORS,
    ).also { glowSprite = it }

    /**
     * @param glow strength of the glow around the plate, 0..1.
     * @param active how far the hairline border has turned into the sunset ring, 0..1.
     */
    fun DrawScope.drawThumbnail(colors: List<CubeColor?>, glow: Float, active: Float) {
        if (glow > 0.01f) drawGlow(glow(), plateTopLeft, alpha = THUMBNAIL_GLOW_ALPHA * glow)
        drawRoundRect(plateFill, plateTopLeft, plateSize, plateRadius)
        if (active < 0.99f) {
            drawRoundRect(
                color = Brand.HairlineStrong,
                topLeft = plateTopLeft + Offset(hairlineWidth / 2, hairlineWidth / 2),
                size = Size(plate - hairlineWidth, plate - hairlineWidth),
                cornerRadius = plateRadius,
                style = hairline,
                alpha = 1f - active.coerceIn(0f, 1f),
            )
        }
        if (active > 0.01f) {
            drawRoundRect(
                brush = ringBrush,
                topLeft = plateTopLeft + Offset(ringWidth / 2, ringWidth / 2),
                size = Size(plate - ringWidth, plate - ringWidth),
                cornerRadius = plateRadius,
                style = ring,
                alpha = active.coerceIn(0f, 1f),
            )
        }
        for (i in 0 until 9) {
            translate(stickerOrigin + (i % 3) * stickerStep, stickerOrigin + (i / 3) * stickerStep) {
                val color = colors[i]
                if (color != null) {
                    drawRoundRect(CubePalette.color(color), size = stickerSize, cornerRadius = stickerRadius)
                    drawRoundRect(gloss, size = stickerSize, cornerRadius = stickerRadius)
                } else {
                    drawRoundRect(EMPTY_FILL, size = stickerSize, cornerRadius = stickerRadius)
                    drawRoundRect(
                        color = EMPTY_RIM,
                        topLeft = Offset(emptyRimWidth / 2, emptyRimWidth / 2),
                        size = Size(sticker - emptyRimWidth, sticker - emptyRimWidth),
                        cornerRadius = stickerRadius,
                        style = emptyRim,
                    )
                }
            }
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

/** Margin around a [FaceGrid]'s plate, as a fraction of its size; the active glow fills it. */
private const val THUMBNAIL_INSET = 0.09f

/** Strength of the active [FaceGrid] glow; it is half this strong at the plate's edge. */
private const val THUMBNAIL_GLOW_ALPHA = 0.85f

/** Reach of a flagged sticker's glow, in dp. */
private const val FLAG_GLOW_SPREAD = 9f

/** Net sticker scale while pressed and while selected (DESIGN.md: gentle, springy). */
private const val PRESSED_SCALE = 0.92f
private const val SELECTED_SCALE = 1.06f

/** Width of the white ring around the selected net sticker, and its gap to the sticker body, in dp. */
private const val SELECTION_RING_WIDTH = 2f
private const val SELECTION_RING_GAP = 1.25f

/** Glow hues stay in magenta/coral: low-alpha orange over ink reads as brown. */
private val WARM_GLOW_COLORS = listOf(Brand.Magenta, Brand.Coral, Brand.Magenta)

private val PLATE_COLORS = listOf(Color(0xFF191921), Color(0xFF0E0E14))
private val RIM_COLORS = listOf(Brand.HairlineStrong, Brand.Hairline.copy(alpha = 0.04f))
private val EMPTY_FILL = Color(0xFF1B1B24)
private val EMPTY_RIM = Color(0xFF6F6A86)
