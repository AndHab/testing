package com.andhab.cubelens.ui.scan

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.drawSticker
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.LocalStickerPalette

/** Corner radius of the guide's window, as a fraction of its side. */
internal const val GuideCornerFraction = 0.075f

/**
 * Dims the camera everywhere except the guide window, so the face inside stands out and the
 * controls on top stay legible. The edges get a little darker still, behind the top bar and the
 * shutter.
 *
 * @param guide the guide window in this composable's coordinates, or null to dim everything.
 */
@Composable
internal fun ScanScrim(guide: Rect?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (guide == null) {
            drawRect(ScrimColor)
            return@Canvas
        }
        val window = Path().apply {
            addRoundRect(RoundRect(guide, CornerRadius(guide.width * GuideCornerFraction)))
        }
        clipPath(window, ClipOp.Difference) {
            drawRect(ScrimColor)
            drawRect(
                Brush.verticalGradient(0f to Brand.Ink.copy(alpha = 0.55f), 1f to Color.Transparent, endY = guide.top),
                size = Size(size.width, guide.top),
            )
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Brand.Ink.copy(alpha = 0.78f),
                    startY = guide.bottom,
                    endY = size.height,
                ),
                topLeft = Offset(0f, guide.bottom),
                size = Size(size.width, size.height - guide.bottom),
            )
            // Warm light spilling from the glowing brackets onto the dimmed camera image.
            drawSoftGlow(Brand.Magenta, alpha = 0.16f, center = guide.topLeft, radiusX = guide.width * 0.55f)
            drawSoftGlow(Brand.Tangerine, alpha = 0.12f, center = guide.bottomRight, radiusX = guide.width * 0.55f)
        }
    }
}

private val ScrimColor = Brand.Ink.copy(alpha = 0.6f)

/**
 * The scan guide: a square window with glowing sunset corner brackets, thin dividers into N×N
 * cells and, in each cell, a small swatch of the color currently read there, drawn in the cube's
 * own colors ([LocalStickerPalette]). Swatches shrink with the cells, so a 7×7 face stays legible.
 *
 * The brackets reach further along the edges as [lockProgress] grows, closing into a full frame
 * when the face is about to be captured; with [complete] they turn mint and a check appears.
 *
 * @param n the cube's size N.
 * @param liveColors the N² live colors, row-major, or null to hide the swatches.
 * @param flagCenter rings the center swatch in amber (the face in view isn't the expected one);
 *   only meaningful for odd sizes, which have a center sticker.
 */
@Composable
internal fun ScanGuide(
    n: Int,
    liveColors: List<CubeColor>?,
    lockProgress: Float,
    complete: Boolean,
    flagCenter: Boolean,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    val palette = LocalStickerPalette.current
    val cells = n * n
    val centerCell = if (n % 2 == 1) cells / 2 else -1
    val lock by animateFloatAsState(lockProgress, spring(dampingRatio = 0.9f, stiffness = 400f), label = "guideLock")
    val done by animateFloatAsState(if (complete) 1f else 0f, tween(420), label = "guideDone")
    val swatchAlpha by animateFloatAsState(
        targetValue = if (liveColors != null && !complete) 1f else 0f,
        animationSpec = tween(240),
        label = "swatchAlpha",
    )
    val flag by animateFloatAsState(if (flagCenter && centerCell >= 0) 1f else 0f, tween(200), label = "centerFlag")
    val swatchColors: List<State<Color>> = List(cells) { index ->
        animateColorAsState(
            targetValue = palette.color(liveColors?.getOrNull(index)),
            animationSpec = tween(160),
            label = "swatch$index",
        )
    }
    val description = when {
        complete -> res.getString(R.string.scan_guide_done)
        liveColors == null -> res.getString(R.string.scan_guide)
        else -> res.getString(R.string.scan_guide_seeing, liveColors.joinToString { res.lowerColorName(it) })
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .drawWithCache {
                val bracketBrush = Brush.linearGradient(Brand.SunsetColors, start = Offset.Zero, end = Offset(size.width, size.height))
                val divider = Stroke(1.dp.toPx())
                val cell = size.width / n
                val swatch = (cell * SwatchCellFraction).coerceIn(MinSwatchSize.toPx(), MaxSwatchSize.toPx())
                val plate = swatch * (1f + 2f * SwatchPlateFraction)
                val window = CornerRadius(size.width * GuideCornerFraction)
                onDrawBehind {
                    // Once done, the camera image inside settles back so the check stands out.
                    if (done > 0f) drawRoundRect(Brand.Ink.copy(alpha = 0.45f * done), cornerRadius = window)
                    drawDividers(n, alpha = 1f - done, stroke = divider)
                    drawGuideBrackets(
                        bounds = Rect(Offset.Zero, size),
                        lock = lock,
                        brush = bracketBrush,
                        accent = Brand.Mint,
                        accentAmount = done,
                    )
                    if (swatchAlpha > 0f) {
                        for (index in 0 until cells) {
                            val cellCenter = Offset(cell * (index % n + 0.5f), cell * (index / n + 0.5f))
                            drawLiveSwatch(
                                color = swatchColors[index].value,
                                center = cellCenter,
                                swatch = swatch,
                                plate = plate,
                                alpha = swatchAlpha,
                                flag = if (index == centerCell) flag else 0f,
                            )
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (done > 0f) {
            Box(
                Modifier
                    .size(76.dp)
                    .graphicsLayer {
                        alpha = done
                        scaleX = 0.6f + 0.4f * done
                        scaleY = 0.6f + 0.4f * done
                    }
                    .drawWithCache {
                        onDrawBehind {
                            drawSoftGlow(Brand.Mint, alpha = 0.45f, center = center, radiusX = size.width)
                        }
                    }
                    .background(Brand.Mint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Brand.OnAccent, modifier = Modifier.size(44.dp))
            }
        }
    }
}

/** A live swatch's side as a fraction of its cell, within [MinSwatchSize]..[MaxSwatchSize]. */
private const val SwatchCellFraction = 0.3f
private val MinSwatchSize: Dp = 10.dp
private val MaxSwatchSize: Dp = 20.dp

/** The dark plate around a swatch, each side, as a fraction of the swatch. */
private const val SwatchPlateFraction = 0.15f

/** Hairlines between the [n]×[n] cells, stopping short of the outer edge. */
private fun DrawScope.drawDividers(n: Int, alpha: Float, stroke: Stroke) {
    if (alpha <= 0f) return
    val inset = size.width * 0.05f
    // Many lines would net the face over; finer grids get fainter ones.
    val color = Color.White.copy(alpha = (if (n <= 3) 0.26f else 0.2f) * alpha)
    for (i in 1 until n) {
        val x = size.width * i / n
        val y = size.height * i / n
        drawLine(color, Offset(x, inset), Offset(x, size.height - inset), stroke.width)
        drawLine(color, Offset(inset, y), Offset(size.width - inset, y), stroke.width)
    }
}

/**
 * Four rounded corner brackets hugging [bounds] from just outside, glowing like neon. [lock]
 * (0..1) extends their arms until they meet into a closed frame; [accentAmount] fades the
 * [brush] into the solid [accent].
 */
internal fun DrawScope.drawGuideBrackets(
    bounds: Rect,
    lock: Float,
    brush: Brush,
    accent: Color = Brand.Mint,
    accentAmount: Float = 0f,
    strokeWidth: Dp = 4.dp,
) {
    val core = strokeWidth.toPx()
    val frame = bounds.inflate(core / 2f + 3.dp.toPx())
    val radius = bounds.width * GuideCornerFraction + core / 2f + 3.dp.toPx()
    val maxArm = frame.width / 2f - radius
    val arm = (MinArmFraction * frame.width) + (maxArm - MinArmFraction * frame.width) * lock.coerceIn(0f, 1f)
    val path = bracketPath(frame, radius, arm)
    val glowAmount = 0.75f + 0.25f * lock
    for (i in GlowWidths.indices) {
        val stroke = Stroke(core * GlowWidths[i], cap = StrokeCap.Round)
        val alpha = GlowAlphas[i] * glowAmount
        if (accentAmount < 1f) drawPath(path, brush, alpha = alpha * (1f - accentAmount), style = stroke)
        if (accentAmount > 0f) drawPath(path, accent, alpha = alpha * accentAmount, style = stroke)
    }
    val stroke = Stroke(core, cap = StrokeCap.Round)
    if (accentAmount < 1f) drawPath(path, brush, alpha = 1f - accentAmount, style = stroke)
    if (accentAmount > 0f) drawPath(path, accent, alpha = accentAmount, style = stroke)
}

/** Shortest bracket arm beyond the corner arc, as a fraction of the frame side. */
private const val MinArmFraction = 0.1f

/** Glow passes under a bracket: stroke widths (multiples of the core) and their opacity. */
private val GlowWidths = floatArrayOf(6f, 3.6f, 2.2f)
private val GlowAlphas = floatArrayOf(0.07f, 0.13f, 0.24f)

private fun bracketPath(frame: Rect, radius: Float, arm: Float): Path = Path().apply {
    val l = frame.left
    val t = frame.top
    val r = frame.right
    val b = frame.bottom
    val d = 2 * radius
    // Top-left
    moveTo(l, t + radius + arm)
    lineTo(l, t + radius)
    arcTo(Rect(l, t, l + d, t + d), 180f, 90f, false)
    lineTo(l + radius + arm, t)
    // Top-right
    moveTo(r - radius - arm, t)
    lineTo(r - radius, t)
    arcTo(Rect(r - d, t, r, t + d), 270f, 90f, false)
    lineTo(r, t + radius + arm)
    // Bottom-right
    moveTo(r, b - radius - arm)
    lineTo(r, b - radius)
    arcTo(Rect(r - d, b - d, r, b), 0f, 90f, false)
    lineTo(r - radius - arm, b)
    // Bottom-left
    moveTo(l + radius + arm, b)
    lineTo(l + radius, b)
    arcTo(Rect(l, b - d, l + d, b), 90f, 90f, false)
    lineTo(l, b - radius - arm)
}

/**
 * One live color readout: a small glossy sticker on a dark plate, so it reads on any sticker and
 * on black plastic alike. [flag] (0..1) adds an amber ring.
 */
private fun DrawScope.drawLiveSwatch(
    color: Color,
    center: Offset,
    swatch: Float,
    plate: Float,
    alpha: Float,
    flag: Float,
) {
    drawSoftGlow(Brand.Ink, alpha = 0.55f * alpha, center = center, radiusX = plate * 1.1f)
    val plateTopLeft = center - Offset(plate / 2f, plate / 2f)
    drawRoundRect(
        color = Brand.Ink.copy(alpha = 0.88f * alpha),
        topLeft = plateTopLeft,
        size = Size(plate, plate),
        cornerRadius = CornerRadius(plate * 0.32f),
    )
    if (flag > 0f) {
        val ring = (swatch * 0.1f).coerceAtLeast(1.5.dp.toPx())
        val outer = plate + 2 * ring + swatch * 0.1f
        drawRoundRect(
            color = Brand.Amber.copy(alpha = flag * alpha),
            topLeft = center - Offset(outer / 2f, outer / 2f),
            size = Size(outer, outer),
            cornerRadius = CornerRadius(outer * 0.32f),
            style = Stroke(ring),
        )
    }
    if (alpha >= 1f) {
        drawSticker(color, center - Offset(swatch / 2f, swatch / 2f), Size(swatch, swatch), cornerFraction = 0.26f)
    } else {
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = center - Offset(swatch / 2f, swatch / 2f),
            size = Size(swatch, swatch),
            cornerRadius = CornerRadius(swatch * 0.26f),
        )
    }
}
