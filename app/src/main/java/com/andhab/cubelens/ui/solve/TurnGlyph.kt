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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.drawSticker
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pictogram of a face turn: the face seen head-on as a small glossy 3x3 grid in [faceColor], ringed
 * by a curved sunset arrow. A quarter turn sweeps a third of the way round, clockwise or
 * counter-clockwise as seen looking at the face; a half turn sweeps well over half way round.
 *
 * Whenever [key] changes (e.g. a new current move), the arrow draws itself in from its tail.
 * Purely decorative: give the turn a text description nearby.
 *
 * @param turns clockwise quarter turns: 1, 2 or 3 (3 = counter-clockwise).
 */
@Composable
internal fun TurnGlyph(
    turns: Int,
    faceColor: Color,
    modifier: Modifier = Modifier,
    key: Any? = turns,
) {
    val static = LocalInspectionMode.current
    // Starts hidden so the first frame does not flash the full arrow before it draws in.
    val reveal = remember { Animatable(if (static) 1f else 0f) }
    LaunchedEffect(key) {
        if (static) return@LaunchedEffect
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        drawTurnGlyph(turns, faceColor, reveal.value)
    }
}

/** Arc of the arrow for a turn: where it starts (degrees, clockwise from 3 o'clock) and how far it sweeps. */
private fun arcFor(turns: Int): Pair<Float, Float> = when (turns) {
    // Over the top, left to right.
    1 -> -152f to 124f
    // Over the top, right to left.
    3 -> -28f to -124f
    // From lower left, over the top, down to lower right.
    else -> 158f to 224f
}

private fun DrawScope.drawTurnGlyph(turns: Int, faceColor: Color, reveal: Float) {
    val unit = size.minDimension
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = unit * 0.40f
    val stroke = unit * 0.06f

    // The face, lit by its own color.
    drawSoftGlow(faceColor, alpha = 0.22f, center = center, radiusX = unit * 0.42f)
    drawMiniFace(faceColor, center, side = unit * 0.40f)

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
    val anchor = center + radial * radius
    val tip = anchor + tangent * (headLength * 0.45f)
    val back = anchor - tangent * (headLength * 0.55f)
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + radial.x * headWidth / 2f, back.y + radial.y * headWidth / 2f)
        lineTo(back.x - radial.x * headWidth / 2f, back.y - radial.y * headWidth / 2f)
        close()
    }
    drawPath(head, brush)
    // Round off the corners of the head.
    drawPath(head, brush, style = Stroke(width = stroke * 0.5f, join = StrokeJoin.Round))
}

/** A 3x3 face of glossy stickers on a black body, centered on [center]. */
private fun DrawScope.drawMiniFace(color: Color, center: Offset, side: Float) {
    val topLeft = center - Offset(side / 2f, side / 2f)
    drawRoundRect(
        color = CubePalette.Body,
        topLeft = topLeft,
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.16f),
    )
    val padding = side * 0.07f
    val gap = side * 0.05f
    val cell = (side - 2f * padding - 2f * gap) / 3f
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            drawSticker(
                color = color,
                topLeft = topLeft + Offset(padding + col * (cell + gap), padding + row * (cell + gap)),
                size = Size(cell, cell),
                cornerFraction = 0.2f,
            )
        }
    }
}
