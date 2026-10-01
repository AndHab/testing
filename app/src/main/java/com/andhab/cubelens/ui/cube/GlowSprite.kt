package com.andhab.cubelens.ui.cube

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A soft glow around a rounded rectangle, rendered once into a bitmap.
 *
 * The falloff matches a Gaussian blur of the shape: half strength at the shape's edge, fading
 * smoothly to nothing at [spread]. It has no bands or hard edges at any size. Each frame draws
 * it with a single bitmap draw, so animating its opacity or spread allocates nothing. It also looks
 * the same on every API level, whereas blur mask filters are not hardware-accelerated before API 28.
 *
 * Build one per shape size (e.g. in `Modifier.drawWithCache`) and draw it with [drawGlow].
 *
 * @property shape size in px of the rounded rect that glows.
 * @property spread distance in px from the shape's edge at which the glow has faded out.
 */
internal class GlowSprite private constructor(
    val image: ImageBitmap,
    val shape: Size,
    val spread: Float,
) {
    companion object {
        /**
         * Renders the glow for a rounded rect of [shape] size and [cornerRadius].
         *
         * @param colors hues along the diagonal from the top-left to the bottom-right corner (one
         *   color for a uniform glow).
         * @param resolution bitmap pixels per canvas pixel. A glow has no fine detail, so a
         *   half-resolution bitmap drawn with bilinear filtering looks the same at a quarter of the cost.
         */
        fun create(
            shape: Size,
            cornerRadius: Float,
            spread: Float,
            colors: List<Color>,
            resolution: Float = 0.5f,
        ): GlowSprite {
            require(colors.isNotEmpty()) { "A glow needs at least one color" }
            val width = ceil((shape.width + 2 * spread) * resolution).toInt().coerceAtLeast(1)
            val height = ceil((shape.height + 2 * spread) * resolution).toInt().coerceAtLeast(1)
            val pixels = glowPixels(width, height, shape, cornerRadius, spread, diagonalPalette(colors))
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            bitmap.prepareToDraw()
            return GlowSprite(bitmap.asImageBitmap(), shape, spread)
        }

        /** Colors sampled evenly along [colors], as opaque ARGB, for per-pixel lookup. */
        private fun diagonalPalette(colors: List<Color>): IntArray {
            if (colors.size == 1) return intArrayOf(colors[0].toArgb())
            val segments = colors.size - 1
            return IntArray(PALETTE_SIZE) { i ->
                val t = i.toFloat() / (PALETTE_SIZE - 1) * segments
                val k = t.toInt().coerceAtMost(segments - 1)
                lerp(colors[k], colors[k + 1], t - k).toArgb()
            }
        }

        private const val PALETTE_SIZE = 64
    }
}

/**
 * Draws [sprite] around a shape whose top-left corner is at [shapeTopLeft].
 *
 * @param alpha overall opacity; the glow is half this strong at the shape's edge.
 * @param spreadFraction how far the glow reaches, from 0 (pulled in under the shape) to 1 (full
 *   [GlowSprite.spread]). The sprite is scaled about the shape's center, which pulls the glow inward,
 *   so the glow still never shows inside the shape.
 */
internal fun DrawScope.drawGlow(sprite: GlowSprite, shapeTopLeft: Offset, alpha: Float, spreadFraction: Float = 1f) {
    if (alpha <= 0f) return
    val reach = sprite.spread * spreadFraction.coerceIn(0f, 1f)
    val width = sprite.shape.width + 2 * reach
    val height = sprite.shape.height + 2 * reach
    withTransform({
        translate(shapeTopLeft.x - reach, shapeTopLeft.y - reach)
        scale(width / sprite.image.width, height / sprite.image.height, pivot = Offset.Zero)
    }) {
        drawImage(sprite.image, alpha = alpha.coerceAtMost(1f))
    }
}

/**
 * Pixels of a glow sprite: ARGB, not premultiplied, row-major, [width] x [height].
 *
 * The sprite spans the rounded rect of [shape] size plus [spread] on every side, scaled to the
 * bitmap. Each pixel's alpha is [glowAlpha] of its signed distance to the rounded rect. Its hue comes
 * from [palette], indexed by the pixel's position along the top-left to bottom-right diagonal.
 */
internal fun glowPixels(
    width: Int,
    height: Int,
    shape: Size,
    cornerRadius: Float,
    spread: Float,
    palette: IntArray,
): IntArray {
    val spriteWidth = shape.width + 2 * spread
    val spriteHeight = shape.height + 2 * spread
    val pixelWidth = spriteWidth / width
    val pixelHeight = spriteHeight / height
    val halfWidth = shape.width / 2
    val halfHeight = shape.height / 2
    val radius = cornerRadius.coerceIn(0f, min(halfWidth, halfHeight))
    val sigma = spread / GLOW_SIGMAS
    val lastColor = palette.size - 1
    val pixels = IntArray(width * height)
    for (py in 0 until height) {
        val y = (py + 0.5f) * pixelHeight
        // Signed distance to a rounded rect: distance to its inner (unrounded) core, minus the radius.
        val qy = abs(y - spriteHeight / 2) - (halfHeight - radius)
        for (px in 0 until width) {
            val x = (px + 0.5f) * pixelWidth
            val qx = abs(x - spriteWidth / 2) - (halfWidth - radius)
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            val distance = sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - radius
            val alpha = glowAlpha(distance, spread, sigma)
            if (alpha <= 0f) continue
            val diagonal = (x / spriteWidth + y / spriteHeight) / 2
            val rgb = palette[(diagonal * lastColor).roundToInt().coerceIn(0, lastColor)] and 0xFFFFFF
            pixels[py * width + px] = ((alpha * 255f).roundToInt() shl 24) or rgb
        }
    }
    return pixels
}

/**
 * Glow strength at signed [distance] from the shape's edge (negative inside the shape).
 *
 * This is a Gaussian-blurred edge (standard deviation [sigma]), via the logistic approximation of
 * the normal CDF. The far tail is eased to exactly zero at [spread], so the sprite's own border
 * never shows.
 */
internal fun glowAlpha(distance: Float, spread: Float, sigma: Float): Float {
    if (distance >= spread) return 0f
    val blurred = 1f / (1f + exp(LOGISTIC_NORMAL * distance / sigma))
    val tail = distance / spread
    if (tail <= TAIL_START) return blurred
    val u = (tail - TAIL_START) / (1f - TAIL_START)
    return blurred * (1f - u * u * (3f - 2f * u))
}

/** Spread in standard deviations of the blur; before the tail is eased out, the glow is about 1.4% strong at the spread. */
private const val GLOW_SIGMAS = 2.5f

/** Scale that makes the logistic function approximate the standard normal CDF. */
private const val LOGISTIC_NORMAL = 1.702f

/** Fraction of the spread after which the tail is eased to zero. */
private const val TAIL_START = 0.55f
