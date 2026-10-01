package com.andhab.cubelens.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import com.andhab.cubelens.ui.theme.DisplayFont
import kotlin.math.sqrt

/**
 * The CubeLens brand mark: a glossy isometric cube (top, left and right faces, 3x3 stickers in a
 * curated scramble) on a black body, optionally sitting in a sunset bloom. Same geometry and
 * colors as the launcher icon. Fills the smaller side of its bounds; the bloom spills outside.
 *
 * Decorative by default; pass [contentDescription] when it stands alone.
 */
@Composable
fun CubeMark(
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    contentDescription: String? = null,
) {
    Spacer(
        modifier
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val geometry = IsoCubeGeometry(
                    center = Offset(size.width / 2f, size.height / 2f),
                    edge = size.minDimension * 0.47f,
                )
                val parts = geometry.buildParts()
                onDrawBehind {
                    if (glow) drawSunsetBloom(geometry.center + Offset(0f, geometry.edge * 0.55f), geometry.edge * 1.75f)
                    drawIsoCube(parts, geometry.edge)
                }
            },
    )
}

/**
 * Logo lockup: [CubeMark] followed by the "CubeLens" wordmark with "Lens" in the sunset gradient.
 * Screen readers hear it as one word, the app name.
 */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = 30.dp,
) {
    val style = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = (markSize.value * 0.72f).sp,
        letterSpacing = (-0.02).em,
    )
    val appName = stringResource(R.string.app_name)
    Row(
        // The two-tone wordmark is two Text nodes; without this TalkBack stops on "Cube", then "Lens".
        modifier = modifier.clearAndSetSemantics { contentDescription = appName },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CubeMark(Modifier.size(markSize), glow = false)
        Spacer(Modifier.width(markSize * 0.34f))
        Text("Cube", style = style, color = Brand.TextPrimary)
        GradientText("Lens", style = style)
    }
}

/** Sticker mix shown on the mark: rows top to bottom, columns left to right, as seen. */
private val TopFace = listOf("OWY", "RWW", "WBO")
private val LeftFace = listOf("YRG", "WGO", "GOR")
private val RightFace = listOf("RYB", "ORR", "YWO")

private enum class IsoFace(val grid: List<String>, val shade: Float, val body: Color) {
    Top(TopFace, 1f, Color(0xFF262430)),
    Left(LeftFace, 0.86f, Color(0xFF15141C)),
    Right(RightFace, 0.70f, Color(0xFF0C0B11)),
}

/** Isometric projection of a cube with circumradius [edge] around [center]. */
private class IsoCubeGeometry(val center: Offset, val edge: Float) {
    private val h = edge * sqrt(3f) / 2f
    val top = center + Offset(0f, -edge)
    val upperRight = center + Offset(h, -edge / 2f)
    val lowerRight = center + Offset(h, edge / 2f)
    val bottom = center + Offset(0f, edge)
    val lowerLeft = center + Offset(-h, edge / 2f)
    val upperLeft = center + Offset(-h, -edge / 2f)

    /** Origin (top-left as seen), column axis and row axis of a face. */
    fun axes(face: IsoFace): Triple<Offset, Offset, Offset> = when (face) {
        IsoFace.Top -> Triple(top, upperRight - top, upperLeft - top)
        IsoFace.Left -> Triple(upperLeft, center - upperLeft, lowerLeft - upperLeft)
        IsoFace.Right -> Triple(center, upperRight - center, bottom - center)
    }

    fun buildParts(): IsoCubeParts {
        val bodyRadius = edge * 0.105f
        val stickerRadius = edge * 0.05f
        val body = roundedPolygon(listOf(top, upperRight, lowerRight, bottom, lowerLeft, upperLeft), bodyRadius)
        val faces = IsoFace.entries.map { face ->
            val (o, u, v) = axes(face)
            roundedPolygon(listOf(o, o + u, o + u + v, o + v), bodyRadius * 0.6f) to face.body
        }
        val stickers = IsoFace.entries.flatMap { face ->
            val (o, u, v) = axes(face)
            val inset = 0.05f
            val gap = 0.10f
            val span = (1f - 2f * inset) / 3f
            (0 until 3).flatMap { row ->
                (0 until 3).map { col ->
                    val u0 = inset + col * span + span * gap / 2f
                    val u1 = inset + (col + 1) * span - span * gap / 2f
                    val v0 = inset + row * span + span * gap / 2f
                    val v1 = inset + (row + 1) * span - span * gap / 2f
                    val corners = listOf(o + u * u0 + v * v0, o + u * u1 + v * v0, o + u * u1 + v * v1, o + u * u0 + v * v1)
                    val base = CubePalette.color(CubeColor.fromLetter(face.grid[row][col])).let {
                        lerp(Color.Black, it, face.shade)
                    }
                    IsoSticker(
                        path = roundedPolygon(corners, stickerRadius),
                        brush = Brush.linearGradient(
                            listOf(lerp(base, Color.White, 0.28f), lerp(Color.Black, base, 0.92f)),
                            start = corners[0],
                            end = corners[2],
                        ),
                    )
                }
            }
        }
        val rim = roundedPolyline(
            listOf(
                upperLeft + (lowerLeft - upperLeft) * 0.35f,
                upperLeft,
                top,
                upperRight,
                upperRight + (lowerRight - upperRight) * 0.35f,
            ),
            bodyRadius,
        )
        return IsoCubeParts(body, faces, stickers, rim)
    }
}

private class IsoSticker(val path: Path, val brush: Brush)

private class IsoCubeParts(
    val body: Path,
    val faces: List<Pair<Path, Color>>,
    val stickers: List<IsoSticker>,
    val rim: Path,
)

private fun DrawScope.drawIsoCube(parts: IsoCubeParts, edge: Float) {
    drawPath(parts.body, IsoFace.Left.body)
    for ((path, color) in parts.faces) drawPath(path, color)
    for (sticker in parts.stickers) drawPath(sticker.path, sticker.brush)
    drawPath(
        parts.rim,
        Color.White.copy(alpha = 0.22f),
        style = Stroke(width = edge * 0.02f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Warm gold → tangerine → magenta bloom, the light the brand cube sits in. */
internal fun DrawScope.drawSunsetBloom(center: Offset, radius: Float, alpha: Float = 1f) {
    val brush = Brush.radialGradient(
        0f to Brand.Gold.copy(alpha = 0.62f * alpha),
        0.24f to Brand.Tangerine.copy(alpha = 0.48f * alpha),
        0.52f to Brand.Magenta.copy(alpha = 0.24f * alpha),
        0.78f to Brand.Magenta.copy(alpha = 0.07f * alpha),
        1f to Brand.Magenta.copy(alpha = 0f),
        center = center,
        radius = radius,
    )
    scale(scaleX = 1f, scaleY = 0.82f, pivot = center) {
        drawCircle(brush, radius = radius, center = center)
    }
}

/** Closed polygon whose corners are rounded with quadratic curves of the given [radius]. */
internal fun roundedPolygon(points: List<Offset>, radius: Float): Path = Path().apply {
    val n = points.size
    for (i in 0..n) {
        val prev = points[(i - 1 + n) % n]
        val corner = points[i % n]
        val next = points[(i + 1) % n]
        val a = corner + (prev - corner).normalized() * radius
        val b = corner + (next - corner).normalized() * radius
        if (i == 0) moveTo(b.x, b.y) else {
            lineTo(a.x, a.y)
            quadraticTo(corner.x, corner.y, b.x, b.y)
        }
    }
    close()
}

/** Open polyline with rounded interior corners. */
private fun roundedPolyline(points: List<Offset>, radius: Float): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.lastIndex) {
        val corner = points[i]
        val a = corner + (points[i - 1] - corner).normalized() * radius
        val b = corner + (points[i + 1] - corner).normalized() * radius
        lineTo(a.x, a.y)
        quadraticTo(corner.x, corner.y, b.x, b.y)
    }
    lineTo(points.last().x, points.last().y)
}

private fun Offset.normalized(): Offset {
    val d = getDistance()
    return if (d == 0f) Offset.Zero else this / d
}
