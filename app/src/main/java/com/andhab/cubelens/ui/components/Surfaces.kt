package com.andhab.cubelens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.ui.theme.Brand

/** Corner shape of [GlassCard] and other medium glass surfaces. */
val GlassCardShape: Shape = RoundedCornerShape(20.dp)

/** Corner shape of hero cards and sheets. */
val HeroCardShape: Shape = RoundedCornerShape(28.dp)

/**
 * Dark-glass surface: translucent [fill] over whatever is behind (usually the aurora), a soft top
 * highlight and a 1dp hairline that is brighter along the top edge. Content is clipped to [shape].
 */
fun Modifier.glassSurface(
    shape: Shape = GlassCardShape,
    fill: Color = Brand.Glass,
    border: Brush = Brand.GlassBorderBrush,
): Modifier = this
    .clip(shape)
    .background(fill)
    .background(GlassHighlightBrush)
    .border(1.dp, border, shape)

/** Soft top highlight of glass surfaces; fades out by 60% of the height. */
private val GlassHighlightBrush = Brush.verticalGradient(
    0f to Color(0x12FFFFFF),
    0.6f to Color.Transparent,
)

/**
 * Paints a soft elliptical glow of [color] behind the element, as if it were lit and bleeding light
 * onto the table beneath it. Cheap: one radial gradient, no blur, works on every API level.
 *
 * @param alpha peak opacity at the glow's center.
 * @param spread how far the glow extends beyond the element's bounds.
 * @param offsetY vertical shift of the glow's center (positive = down), for a "light below" look.
 */
fun Modifier.softGlow(
    color: Color,
    alpha: Float = 0.45f,
    spread: Dp = 18.dp,
    offsetY: Dp = 8.dp,
): Modifier = drawBehind {
    drawSoftGlow(
        color = color,
        alpha = alpha,
        center = Offset(size.width / 2f, size.height / 2f + offsetY.toPx()),
        radiusX = size.width / 2f + spread.toPx(),
        radiusY = size.height / 2f + spread.toPx(),
    )
}

/**
 * Two-tone sunset glow (magenta on the left, tangerine on the right) behind the element, used
 * under primary buttons and highlighted controls.
 */
fun Modifier.sunsetGlow(
    alpha: Float = 0.6f,
    spread: Dp = 24.dp,
    offsetY: Dp = 14.dp,
): Modifier = drawBehind {
    val spreadPx = spread.toPx()
    val cy = size.height / 2f + offsetY.toPx()
    val rx = size.width * 0.34f + spreadPx
    val ry = size.height / 2f + spreadPx
    drawSoftGlow(Brand.Magenta, alpha, Offset(size.width * 0.30f, cy), rx, ry)
    drawSoftGlow(Brand.Tangerine, alpha * 0.85f, Offset(size.width * 0.72f, cy), rx, ry)
}

/**
 * Draws an elliptical glow with a smooth, roughly Gaussian falloff (no visible edge).
 * Shared by the glow modifiers, [AuroraBackground] and the brand mark.
 */
fun DrawScope.drawSoftGlow(
    color: Color,
    alpha: Float,
    center: Offset,
    radiusX: Float,
    radiusY: Float = radiusX,
) {
    if (alpha <= 0f || radiusX <= 0f || radiusY <= 0f) return
    val brush = Brush.radialGradient(
        0f to color.copy(alpha = alpha),
        0.25f to color.copy(alpha = alpha * 0.78f),
        0.5f to color.copy(alpha = alpha * 0.40f),
        0.75f to color.copy(alpha = alpha * 0.12f),
        1f to color.copy(alpha = 0f),
        center = center,
        radius = radiusX,
    )
    scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = center) {
        drawCircle(brush = brush, radius = radiusX, center = center)
    }
}

/**
 * Glossy plastic sheen for pills: a white highlight fading out over the top half of the element.
 * Place it after the `background` modifier so it sits above the fill but below the content.
 */
internal fun Modifier.topSheen(alpha: Float = 0.22f): Modifier = drawBehind {
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = alpha),
            0.55f to Color.White.copy(alpha = 0f),
        ),
    )
}
