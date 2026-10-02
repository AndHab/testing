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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.ui.components.colorName
import com.andhab.cubelens.ui.components.colorNameRes
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import com.andhab.cubelens.ui.theme.StickerPalette
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The cube unfolded in the standard cross: U above F, the row L F R B, and D below F. Works for any
 * size: each face is an N×N grid, with N inferred from the 6·N² colors.
 *
 * The net fills the available width (or the available height, if that is the tighter fit), and
 * keeps the same overall proportions for every size. Each face sits on a dark glass plate;
 * stickers are rounded and glossy in the colors of [LocalStickerPalette], unknown (`null`) stickers
 * are hollow with a dashed rim, flagged stickers pulse with a soft [Brand.Danger] glow, sit inside
 * a danger ring set off by a dark gap and carry a "!" badge (so the flag never relies on color: red
 * and orange stickers read as flagged too; on a 5×5 and larger, whose stickers are tiny, one badge
 * on the corner of each face with flags stands in for them), and the selected sticker lifts
 * slightly inside a white ring. When [onStickerClick] is set, stickers are buttons with a springy
 * press.
 *
 * On a big cube the stickers of a net get small; pass [onFaceClick] to make each face's plate a
 * button (e.g. to open that face in a [FaceEditor]). Taps on a sticker go to [onStickerClick] when
 * it is set, everywhere else on a plate to [onFaceClick].
 *
 * @param colors 6·N² sticker colors in [NxNGeometry] order (54 for a 3×3).
 * @param highlightFacelets stickers to flag (e.g. [com.andhab.cubelens.core.cube.ValidationResult.flaggedFacelets]).
 * @param selectedFacelet the sticker currently being edited, if any.
 * @param onStickerClick called with the index of a tapped sticker; `null` makes stickers read-only.
 * @param onFaceClick called with the face whose plate was tapped; `null` makes plates read-only.
 */
@Composable
fun CubeNet(
    colors: List<CubeColor?>,
    modifier: Modifier = Modifier,
    highlightFacelets: Set<Int> = emptySet(),
    selectedFacelet: Int? = null,
    onStickerClick: ((Int) -> Unit)? = null,
    onFaceClick: ((Face) -> Unit)? = null,
) {
    val n = CubeSizes.ofCube(colors.size)
    val geometry = NxNGeometry.of(n)
    val pulse = if (highlightFacelets.isNotEmpty()) rememberHighlightPulse() else null
    // An array, so the per-frame glow loop needs no iterator.
    val flagged = remember(highlightFacelets, n) {
        highlightFacelets.filter { it in 0 until geometry.stickerCount }.sorted().toIntArray()
    }
    // Written by the measure pass, read by the draw pass (which always follows it).
    val measured = remember { MeasuredNet() }
    val plateTargets = onFaceClick != null

    Layout(
        content = {
            if (onFaceClick != null) {
                for (face in Face.entries) FacePlateTarget(face, onFaceClick)
            }
            for (i in 0 until geometry.stickerCount) {
                val face = geometry.faceOf(i)
                val color = colors[i]
                StickerCell(
                    index = i,
                    color = color,
                    description = stringResource(
                        R.string.sticker_net_description,
                        netFaceName(face),
                        geometry.rowOf(i) + 1,
                        geometry.colOf(i) + 1,
                        colorName(color),
                    ),
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
                    spread = min(FLAG_GLOW_SPREAD.dp.toPx(), metrics.sticker * 0.75f),
                    colors = listOf(Brand.Danger),
                )
            }
            val badges = flagBadges(metrics, flagged, this)
            val badgeDiameter = FLAG_BADGE_DIAMETER.dp.toPx()
            val badgeHalo = FLAG_BADGE_HALO.dp.toPx()
            onDrawWithContent {
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
                drawContent()
                for (badge in badges) drawFlagBadge(badge, badgeDiameter, badgeHalo)
            }
        },
    ) { measurables, constraints ->
        val metrics = NetMetrics.fit(constraints, n)
        measured.metrics = metrics
        val sticker = metrics.sticker.roundToInt().coerceAtLeast(1)
        val plate = metrics.plate.roundToInt().coerceAtLeast(1)
        val first = if (plateTargets) Face.entries.size else 0
        val placeables = measurables.mapIndexed { k, measurable ->
            val side = if (k < first) plate else sticker
            measurable.measure(Constraints.fixed(side, side))
        }
        layout(metrics.width.roundToInt(), metrics.height.roundToInt()) {
            placeables.forEachIndexed { k, placeable ->
                if (k < first) {
                    val face = Face.entries[k]
                    placeable.place(metrics.plateX(face).roundToInt(), metrics.plateY(face).roundToInt())
                } else {
                    val i = k - first
                    placeable.place(metrics.stickerX(i).roundToInt(), metrics.stickerY(i).roundToInt())
                }
            }
        }
    }
}

/**
 * Where the "!" badges of the [flagged] stickers of a net with [metrics] go: on each sticker's
 * top-right corner, kept inside its face's plate; from [FLAG_BADGE_PER_FACE_SIZE] on, where
 * stickers are too small for a badge each, one on the top-right corner of each face with flags,
 * like a notification badge.
 */
private fun flagBadges(metrics: NetMetrics, flagged: IntArray, density: Density): List<Offset> {
    if (flagged.isEmpty()) return emptyList()
    val geometry = NxNGeometry.of(metrics.n)
    val r = with(density) { (FLAG_BADGE_DIAMETER / 2).dp.toPx() }
    if (metrics.n >= FLAG_BADGE_PER_FACE_SIZE) {
        return flagged.map { geometry.faceOf(it) }.distinct().map { face ->
            Offset(metrics.plateX(face) + metrics.plate - r * 0.45f, metrics.plateY(face) + r * 0.45f)
        }
    }
    val reach = r + with(density) { FLAG_BADGE_HALO.dp.toPx() }
    return flagged.map { i ->
        val face = geometry.faceOf(i)
        val badge = Offset(metrics.stickerX(i) + metrics.sticker - r * 0.45f, metrics.stickerY(i) + r * 0.45f)
        Offset(
            badge.x.coerceIn(metrics.plateX(face) + reach, metrics.plateX(face) + metrics.plate - reach),
            badge.y.coerceIn(metrics.plateY(face) + reach, metrics.plateY(face) + metrics.plate - reach),
        )
    }
}

/** The face's plain name ("Front face"), for screen readers. */
@Composable
private fun netFaceName(face: Face): String = stringResource(
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
 * The tappable plate of one face of a [CubeNet]: invisible at rest (the net draws the plates), it
 * lights up with a sunset rim while pressed.
 */
@Composable
private fun FacePlateTarget(face: Face, onClick: (Face) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "platePressed",
    )
    val name = netFaceName(face)
    val editLabel = stringResource(R.string.face_action_edit)
    Box(
        Modifier
            .semantics { contentDescription = name }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = editLabel,
            ) { onClick(face) }
            .drawBehind {
                if (glow > 0.01f) {
                    val width = 2.dp.toPx()
                    drawRoundRect(
                        brush = Brush.linearGradient(Brand.SunsetColors, start = Offset.Zero, end = Offset(size.width, size.height)),
                        topLeft = Offset(width / 2, width / 2),
                        size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius(size.minDimension * PLATE_CORNER),
                        style = Stroke(width),
                        alpha = glow.coerceAtMost(1f),
                    )
                }
            },
    )
}

/**
 * A single face as an N×N thumbnail on a dark plate (e.g. the strip of scanned faces), with N
 * inferred from the N² colors.
 *
 * @param colors the face's stickers, row-major as in [NxNGeometry]; `null` stickers are hollow.
 * @param active highlights the face with a sunset outline and a softly breathing glow. The glow
 *   stays within the composable's bounds, so a clipping parent will not cut it off.
 */
@Composable
fun FaceGrid(colors: List<CubeColor?>, modifier: Modifier = Modifier, active: Boolean = false) {
    val n = CubeSizes.ofFace(colors.size)
    val palette = LocalStickerPalette.current
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
    val res = LocalResources.current
    val description = remember(colors, res) {
        val known = colors.count { it != null }
        if (known == 0) {
            res.getString(R.string.face_thumb_empty)
        } else {
            res.getString(R.string.face_thumb_colors, colors.joinToString { res.getString(colorNameRes(it)) })
        }
    }
    // Outlives the draw cache, which is rebuilt whenever active or colors change, so the brushes
    // and the glow sprite are only rebuilt when the size does.
    val thumbnails = remember { FaceThumbnailHolder() }
    Spacer(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .drawWithCache {
                val thumbnail = thumbnails.forSize(size, this, n)
                onDrawBehind {
                    val amount = activeAmount.value
                    val glow = amount * (0.7f + 0.3f * (breathe?.value ?: 1f))
                    with(thumbnail) { drawThumbnail(colors, palette, glow, amount) }
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

/** The [FaceThumbnail] of a [FaceGrid]'s latest size, density and cube size. */
private class FaceThumbnailHolder {
    private var current: FaceThumbnail? = null

    fun forSize(size: Size, density: Density, n: Int): FaceThumbnail =
        current?.takeIf { it.size == size && it.pixelDensity == density.density && it.n == n }
            ?: FaceThumbnail(size, density, n).also { current = it }
}

/**
 * Everything a [FaceGrid] of one size draws with: plate, border, sticker layout, brushes and strokes.
 * Built once per size, so a breathing active thumbnail allocates nothing per frame.
 */
private class FaceThumbnail(val size: Size, density: Density, val n: Int) {
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

    // N×N stickers: padding p and gap g scaled to the 3×3's proportions of the plate, so every size
    // fills the plate the same way: 2p + N·s + (N-1)·g = plate.
    private val padding = plate * GRID_PADDING_FRACTION
    private val sticker = (plate - 2 * padding) / (n + (n - 1) * GRID_GAP)
    private val stickerStep = sticker * (1 + GRID_GAP)
    private val stickerOrigin = inset + padding
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
    fun DrawScope.drawThumbnail(colors: List<CubeColor?>, palette: StickerPalette, glow: Float, active: Float) {
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
        for (i in 0 until n * n) {
            translate(stickerOrigin + (i % n) * stickerStep, stickerOrigin + (i / n) * stickerStep) {
                val color = colors[i]
                if (color != null) {
                    drawRoundRect(palette.color(color), size = stickerSize, cornerRadius = stickerRadius)
                    drawRoundRect(gloss, size = stickerSize, cornerRadius = stickerRadius, alpha = palette.finish(color).glossScale)
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
 * Geometry of an N×N net in pixels, all derived from the sticker size: stickers are separated by a
 * small gap, sit inside a padded plate per face, and plates are separated by a wider gap. Plate
 * padding and the gap between plates keep the 3×3's proportions of a plate, so nets of every size
 * have the same shape and only the stickers get smaller.
 */
internal class NetMetrics private constructor(val sticker: Float, val n: Int) {
    private val geometry = NxNGeometry.of(n)
    val gap = sticker * NET_GAP
    val plate = sticker * plateUnits(n)
    val padding = plate * PLATE_PADDING_FRACTION
    val faceGap = plate * FACE_GAP_FRACTION
    val width = 4 * plate + 3 * faceGap
    val height = 3 * plate + 2 * faceGap

    fun plateX(face: Face): Float = layoutColumn(face) * (plate + faceGap)
    fun plateY(face: Face): Float = layoutRow(face) * (plate + faceGap)
    fun stickerX(index: Int): Float = plateX(geometry.faceOf(index)) + padding + geometry.colOf(index) * (sticker + gap)
    fun stickerY(index: Int): Float = plateY(geometry.faceOf(index)) + padding + geometry.rowOf(index) * (sticker + gap)

    companion object {
        /** A 3×3 plate is 3 stickers, 2 gaps and 2 paddings; these keep its proportions for every N. */
        private val PLATE_PADDING_FRACTION = NET_PADDING / (3 + 2 * NET_GAP + 2 * NET_PADDING)
        private val FACE_GAP_FRACTION = NET_FACE_GAP / (3 + 2 * NET_GAP + 2 * NET_PADDING)

        /** Plate side in sticker units for an N×N face. */
        private fun plateUnits(n: Int): Float = (n + (n - 1) * NET_GAP) / (1 - 2 * PLATE_PADDING_FRACTION)

        /** Width of the net in sticker units. */
        private fun widthUnits(n: Int): Float = plateUnits(n) * (4 + 3 * FACE_GAP_FRACTION)

        /** Height of the net in sticker units. */
        private fun heightUnits(n: Int): Float = plateUnits(n) * (3 + 2 * FACE_GAP_FRACTION)

        /** Largest net of an [n]×[n] cube that fits [constraints]; uses the width when the height is unbounded. */
        fun fit(constraints: Constraints, n: Int = 3): NetMetrics {
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else DEFAULT_WIDTH_PX
            val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.MAX_VALUE
            return forSize(maxW, maxH, n)
        }

        /** Largest net of an [n]×[n] cube that fits in [width] x [height] pixels, with a whole-pixel sticker size. */
        fun forSize(width: Float, height: Float, n: Int = 3): NetMetrics {
            val s = min(width / widthUnits(n), height / heightUnits(n))
            return NetMetrics(floor(s).coerceAtLeast(1f), n)
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


private const val NET_GAP = 0.1f
private const val NET_PADDING = 0.16f
private const val NET_FACE_GAP = 0.22f
private const val GRID_GAP = 0.1f

/** Thumbnail plate padding as a fraction of the plate: a 3×3 has 0.16 sticker of padding. */
private const val GRID_PADDING_FRACTION = 0.16f / (3 + 2 * 0.1f + 2 * 0.16f)
internal const val PLATE_CORNER = 0.13f

/** Margin around a [FaceGrid]'s plate, as a fraction of its size; the active glow fills it. */
private const val THUMBNAIL_INSET = 0.09f

/** Strength of the active [FaceGrid] glow; it is half this strong at the plate's edge. */
private const val THUMBNAIL_GLOW_ALPHA = 0.85f

/** Reach of a flagged sticker's glow, in dp (less for tiny stickers). */
private const val FLAG_GLOW_SPREAD = 9f

/** Diameter of a flag's "!" badge on a net, and its ink halo, in dp. */
private const val FLAG_BADGE_DIAMETER = 12f
private const val FLAG_BADGE_HALO = 1.5f

/** From this size on, a net carries one flag badge per face instead of one per sticker. */
private const val FLAG_BADGE_PER_FACE_SIZE = 5

/** Glow hues stay in magenta/coral: low-alpha orange over ink reads as brown. */
private val WARM_GLOW_COLORS = listOf(Brand.Magenta, Brand.Coral, Brand.Magenta)

internal val PLATE_COLORS = listOf(Color(0xFF191921), Color(0xFF0E0E14))
internal val RIM_COLORS = listOf(Brand.HairlineStrong, Brand.Hairline.copy(alpha = 0.04f))
