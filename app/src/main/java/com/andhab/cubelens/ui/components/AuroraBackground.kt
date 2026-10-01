package com.andhab.cubelens.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalInspectionMode
import com.andhab.cubelens.ui.theme.Brand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The signature CubeLens backdrop: ink, lit by large, soft glows of magenta, tangerine and a hint
 * of mint that drift slowly like neon reflected on a dark table, finished with a fine film grain
 * (which also hides gradient banding) and a gentle vignette.
 *
 * Cheap to draw: four radial gradients, one tiled 128px noise texture and one vignette gradient per
 * frame. The drift is read only in the draw phase, so it never recomposes [content]. In inspection
 * mode (previews, screenshot tests) it renders a fixed frame.
 *
 * @param intensity scales the glows' opacity; 1 is the default mood, lower values calm busy screens
 *   (e.g. 0.6 behind a camera preview frame), up to ~1.5 for celebratory moments.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val drift = rememberAuroraDrift()
    val currentIntensity = rememberUpdatedState(intensity.coerceIn(0f, 2f))
    Box(
        modifier = modifier.drawWithCache {
            val vignette = Brush.radialGradient(
                0f to Color.Transparent,
                0.6f to Color.Transparent,
                1f to Brand.Ink.copy(alpha = 0.45f),
                center = Offset(size.width / 2f, size.height * 0.45f),
                radius = size.maxDimension * 0.85f,
            )
            onDrawBehind {
                drawRect(Brand.Ink)
                drawAuroraGlows(drift.value, currentIntensity.value)
                drawRect(GrainBrush)
                drawRect(vignette)
            }
        },
        content = content,
    )
}

/** Fraction (0..1) of one full drift cycle at which inspection-mode renders are frozen. */
private const val StaticDriftPhase = 0.12f

/** Duration of one full, seamless loop of the glow drift. */
private const val DriftPeriodMillis = 28_000

@Composable
private fun rememberAuroraDrift(): State<Float> {
    if (LocalInspectionMode.current) return remember { FrozenState(StaticDriftPhase) }
    val transition = rememberInfiniteTransition(label = "aurora")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(DriftPeriodMillis, easing = LinearEasing)),
        label = "auroraDrift",
    )
}

/**
 * One drifting glow. Positions are fractions of the canvas, the radius a fraction of its shorter
 * side. The center orbits its anchor on a Lissajous path with whole-number frequencies, so the
 * loop is seamless.
 */
private class AuroraGlow(
    val color: Color,
    val alpha: Float,
    val x: Float,
    val y: Float,
    val radius: Float,
    val driftX: Float,
    val driftY: Float,
    val freqX: Int,
    val freqY: Int,
    val phase: Float,
    val squash: Float = 1f,
)

// Glows sit partly off-screen so what shows is light leaking in from the edges, which reads as
// neon rather than as a tinted haze.
private val Glows = listOf(
    // Big magenta bloom washing in from the top-left.
    AuroraGlow(Brand.Magenta, 0.30f, x = -0.06f, y = 0.02f, radius = 1.0f, driftX = 0.10f, driftY = 0.04f, freqX = 1, freqY = 1, phase = 0f),
    // Tangerine rising from the lower right.
    AuroraGlow(Brand.Tangerine, 0.24f, x = 1.10f, y = 0.74f, radius = 0.85f, driftX = 0.06f, driftY = 0.07f, freqX = 1, freqY = 2, phase = 0.35f, squash = 1.3f),
    // Warm coral pooling along the bottom edge, like light on a table.
    AuroraGlow(Brand.Coral, 0.16f, x = 0.30f, y = 1.10f, radius = 0.95f, driftX = 0.14f, driftY = 0.03f, freqX = 2, freqY = 1, phase = 0.6f, squash = 0.65f),
    // A hint of mint on the right, a cool counterpoint to the warm light.
    AuroraGlow(Brand.Mint, 0.075f, x = 1.04f, y = 0.24f, radius = 0.55f, driftX = 0.04f, driftY = 0.06f, freqX = 1, freqY = 1, phase = 0.8f),
)

private fun DrawScope.drawAuroraGlows(drift: Float, intensity: Float) {
    if (intensity <= 0f) return
    val unit = size.minDimension
    for (glow in Glows) {
        val angle = 2f * PI.toFloat() * (drift + glow.phase)
        val cx = (glow.x + glow.driftX * sin(angle * glow.freqX)) * size.width
        val cy = (glow.y + glow.driftY * cos(angle * glow.freqY)) * size.height
        // Breathe gently: +-8% radius over the cycle.
        val radius = glow.radius * unit * (1f + 0.08f * sin(angle * 2f + glow.phase * 5f))
        drawSoftGlow(
            color = glow.color,
            alpha = (glow.alpha * intensity).coerceAtMost(0.5f),
            center = Offset(cx, cy),
            radiusX = radius,
            radiusY = radius * glow.squash,
        )
    }
}

private const val GrainSize = 128

/** Tiled film grain: sparse light specks (max ~7% alpha). Built once per process. */
private val GrainBrush: ShaderBrush by lazy {
    ShaderBrush(ImageShader(createGrainBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private fun createGrainBitmap(): ImageBitmap {
    val random = Random(0x5EED)
    val pixels = IntArray(GrainSize * GrainSize) {
        val light = random.nextFloat() < 0.5f
        val alpha = if (light) random.nextInt(0, 18) else random.nextInt(0, 28)
        val v = if (light) 0xFF else 0x00
        (alpha shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(pixels, GrainSize, GrainSize, Bitmap.Config.ARGB_8888).asImageBitmap()
}
