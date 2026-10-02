package com.andhab.cubelens.ui.solve

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.drawSticker
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pictogram of a turn, in [faceColor] (the sticker color of the side the layers are counted from,
 * from [com.andhab.cubelens.ui.theme.LocalStickerPalette]) with a sunset arrow.
 *
 *  - A single outer face of a 2×2 or 3×3 cube: the face seen head-on as a small glossy N×N grid,
 *    ringed by a curved arrow. A quarter turn sweeps a third of the way round, clockwise or
 *    counter-clockwise as seen looking at the face; a half turn sweeps well over half way round.
 *  - Any other turn (wide moves, inner slices, layer ranges, and every move of a bigger cube): a
 *    small N×N cube seen from the front-top-right, as the 3D cube first appears, with the turning
 *    layers lit up as a band in [faceColor] and the rest dimmed, and an arrow running along the band
 *    the way its stickers travel. A half turn gets a double arrowhead.
 *
 * Whenever [key] changes (e.g. a new current move), the arrow draws itself in from its tail.
 * Purely decorative: give the turn a text description nearby.
 *
 * @param n size of the cube.
 */
@Composable
internal fun TurnGlyph(
    move: LayerMove,
    n: Int,
    faceColor: Color,
    modifier: Modifier = Modifier,
    key: Any? = move,
) {
    val static = LocalInspectionMode.current
    // Starts hidden so the first frame does not flash the full arrow before it draws in.
    val reveal = remember { Animatable(if (static) 1f else 0f) }
    LaunchedEffect(key) {
        if (static) return@LaunchedEffect
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
    }
    val layerView = usesLayerView(move, n)
    Canvas(modifier) {
        if (layerView) {
            drawLayerGlyph(move, n, faceColor, reveal.value)
        } else {
            drawFaceGlyph(move.turns, n, faceColor, reveal.value)
        }
    }
}

/** True when [move] is shown on the small cube rather than as a face seen head-on. */
internal fun usesLayerView(move: LayerMove, n: Int): Boolean = n >= 4 || !move.isOuter

/** Arc of the arrow for a face turn: where it starts (degrees, clockwise from 3 o'clock) and how far it sweeps. */
private fun arcFor(turns: Int): Pair<Float, Float> = when (turns) {
    // Over the top, left to right.
    1 -> -152f to 124f
    // Over the top, right to left.
    3 -> -28f to -124f
    // From lower left, over the top, down to lower right.
    else -> 158f to 224f
}

private fun DrawScope.drawFaceGlyph(turns: Int, n: Int, faceColor: Color, reveal: Float) {
    val unit = size.minDimension
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = unit * 0.40f
    val stroke = unit * 0.06f

    // The face, lit by its own color.
    drawSoftGlow(faceColor, alpha = 0.22f, center = center, radiusX = unit * 0.42f)
    drawMiniFace(faceColor, n, center, side = unit * 0.40f)

    // A faint full ring: the path the face turns along.
    drawCircle(
        color = Color.White.copy(alpha = 0.08f),
        radius = radius,
        center = center,
        style = Stroke(width = stroke * 0.45f),
    )

    if (reveal <= 0f) return
    val (start, fullSweep) = arcFor(turns)
    val clockwise = fullSweep > 0f
    val sweep = fullSweep * reveal
    val headLength = stroke * 2.5f
    val headWidth = stroke * 2.7f
    // Stop the shaft short of the tip so the round cap never peeks out past the arrowhead.
    val trim = Math.toDegrees((headLength * 0.55f / radius).toDouble()).toFloat()
    val shaftSweep = if (clockwise) (sweep - trim).coerceAtLeast(0f) else (sweep + trim).coerceAtMost(0f)

    // Sunset along the direction of travel: magenta tail, gold head.
    val brush = Brush.linearGradient(
        colors = Brand.SunsetColors,
        start = Offset(if (clockwise) 0f else size.width, size.height * 0.2f),
        end = Offset(if (clockwise) size.width else 0f, size.height * 0.8f),
    )
    val box = Rect(center, radius)
    if (shaftSweep != 0f) {
        drawArc(
            brush = brush,
            startAngle = start,
            sweepAngle = shaftSweep,
            useCenter = false,
            topLeft = box.topLeft,
            size = box.size,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }

    // Arrowhead at the moving end, pointing along the direction of travel.
    val end = Math.toRadians((start + sweep).toDouble()).toFloat()
    val radial = Offset(cos(end), sin(end))
    val tangent = if (clockwise) Offset(-radial.y, radial.x) else Offset(radial.y, -radial.x)
    drawArrowHead(center + radial * radius, tangent, headLength, headWidth, stroke, brush)
}

/** An N×N face of glossy stickers on a black body, centered on [center]. */
private fun DrawScope.drawMiniFace(color: Color, n: Int, center: Offset, side: Float) {
    val topLeft = center - Offset(side / 2f, side / 2f)
    drawRoundRect(
        color = CubePalette.Body,
        topLeft = topLeft,
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.16f),
    )
    val padding = side * 0.07f
    // Gaps shrink with the grid so bigger faces keep solid-looking stickers (5% of the face at 3×3).
    val gap = side * 0.15f / n
    val cell = (side - 2f * padding - (n - 1) * gap) / n
    for (row in 0 until n) {
        for (col in 0 until n) {
            drawSticker(
                color = color,
                topLeft = topLeft + Offset(padding + col * (cell + gap), padding + row * (cell + gap)),
                size = Size(cell, cell),
                cornerFraction = 0.2f,
            )
        }
    }
}

private fun DrawScope.drawLayerGlyph(move: LayerMove, n: Int, faceColor: Color, reveal: Float) {
    val unit = size.minDimension
    val center = Offset(size.width / 2f, size.height / 2f)
    val side = unit * 0.98f
    val pictogram = CubePictogram.of(n)

    drawSoftGlow(faceColor, alpha = 0.2f, center = center, radiusX = unit * 0.48f)
    drawCubePictogram(pictogram, center, side) { sticker -> faceColor.takeIf { pictogram.isMoved(sticker.index, move) } }

    if (reveal <= 0f) return
    val points = turnArrowPath(move, n).map { center + pictogram.project(it) * side }
    // A face spans about half the drawing. The arrow stays thin enough that a single lit layer of a
    // big cube still shows on both sides of it, keyline included.
    val layerWidth = side * 0.5f / n
    val stroke = minOf(unit * 0.036f, layerWidth * 0.36f)
    val bend = unit * 0.06f
    val path = Path().apply {
        val (a, b, c) = points
        val inDir = (b - a).unit()
        val outDir = (c - b).unit()
        moveTo(a.x, a.y)
        val beforeBend = b - inDir * bend
        val afterBend = b + outDir * bend
        lineTo(beforeBend.x, beforeBend.y)
        quadraticTo(b.x, b.y, afterBend.x, afterBend.y)
        lineTo(c.x, c.y)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    // The head keeps a readable size even on the thin arrows of big cubes.
    val headLength = maxOf(stroke * 3.1f, unit * 0.1f)
    val headWidth = headLength * 1.1f
    val tipAt = length * reveal
    val double = move.turns == 2
    // The shaft stops short of the (last) arrowhead so its round cap never shows past the tip.
    val shaftEnd = (tipAt - headLength * 0.55f).coerceAtLeast(0f)
    val brush = Brush.linearGradient(Brand.SunsetColors, start = points.first(), end = points.last())

    val shaft = Path()
    if (shaftEnd > 0f) measure.getSegment(0f, shaftEnd, shaft, true)
    // A dark keyline under the arrow keeps it readable over light stickers.
    val keyline = Brand.Ink.copy(alpha = 0.6f)
    drawPath(shaft, keyline, style = Stroke(width = stroke * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val heads = if (double) listOf(tipAt, tipAt - headLength * 0.8f) else listOf(tipAt)
    for (at in heads) {
        if (at <= 0f) continue
        val tip = measure.getPosition(at)
        val tangent = measure.getTangent(at)
        drawArrowHead(tip - tangent * (headLength * 0.45f), tangent, headLength * 1.1f, headWidth * 1.25f, stroke, SolidColor(keyline))
    }
    drawPath(shaft, brush, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    for (at in heads) {
        if (at <= 0f) continue
        val tip = measure.getPosition(at)
        val tangent = measure.getTangent(at)
        drawArrowHead(tip - tangent * (headLength * 0.45f), tangent, headLength, headWidth, stroke, brush)
    }
}

/**
 * A rounded triangular arrowhead whose base is centered on [anchor], pointing along the unit vector
 * [direction].
 */
private fun DrawScope.drawArrowHead(
    anchor: Offset,
    direction: Offset,
    headLength: Float,
    headWidth: Float,
    stroke: Float,
    brush: Brush,
) {
    val normal = Offset(-direction.y, direction.x)
    val tip = anchor + direction * (headLength * 0.45f)
    val back = anchor - direction * (headLength * 0.55f)
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + normal.x * headWidth / 2f, back.y + normal.y * headWidth / 2f)
        lineTo(back.x - normal.x * headWidth / 2f, back.y - normal.y * headWidth / 2f)
        close()
    }
    drawPath(head, brush)
    // Round off the corners of the head.
    drawPath(head, brush, style = Stroke(width = stroke * 0.5f, join = StrokeJoin.Round))
}

private fun Offset.unit(): Offset {
    val length = hypot(x, y)
    return if (length == 0f) Offset.Zero else this / length
}
