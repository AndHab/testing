package com.andhab.cubelens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.theme.CubePalette
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Celebration overlay. Each time [trigger] changes to a new non-null value, a burst of confetti in
 * the six sticker colors explodes from the upper middle of the overlay, tumbles under gravity and
 * air drag, and fades out after about three seconds. A new trigger during a burst adds to it.
 *
 * Fills its parent (place it last in a `Box` so it draws on top), never intercepts touches and
 * draws nothing while idle. In inspection mode a non-null [trigger] renders a frozen mid-flight
 * frame, so previews and screenshot tests show the effect.
 *
 * ```
 * Box {
 *     SolveContent()
 *     ConfettiBurst(trigger = solvedAt) // e.g. a timestamp set when the cube is solved
 * }
 * ```
 */
@Composable
fun ConfettiBurst(trigger: Any?, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        FrozenConfetti(trigger, modifier)
        return
    }
    val simulation = remember { ConfettiSimulation() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var bursts by remember { mutableIntStateOf(0) }
    // Bumped every simulated frame; read in the draw phase so only drawing is invalidated.
    var frameNanos by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current.density

    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        val bounds = snapshotFlow { canvasSize }.first { it.width > 0 && it.height > 0 }
        simulation.burst(
            originX = bounds.width / 2f,
            originY = bounds.height * BurstOriginY,
            density = density,
        )
        bursts++
    }
    // Runs independently of the trigger so a burst always plays out, even if the trigger resets.
    LaunchedEffect(bursts) {
        if (bursts == 0) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (!simulation.isIdle) {
            withFrameNanos { now ->
                simulation.step((now - last) / 1_000_000_000f, canvasSize.height.toFloat())
                last = now
                frameNanos = now
            }
        }
        frameNanos = 0L
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it },
    ) {
        if (frameNanos != 0L) drawConfetti(simulation)
    }
}

/** Vertical position of the burst origin, as a fraction of the overlay height. */
private const val BurstOriginY = 0.36f

/** Seconds into the burst shown by the inspection-mode frame. */
private const val FrozenFrameSeconds = 0.34f

@Composable
private fun FrozenConfetti(trigger: Any?, modifier: Modifier) {
    val density = LocalDensity.current.density
    Canvas(modifier.fillMaxSize()) {
        if (trigger == null) return@Canvas
        val simulation = ConfettiSimulation(Random(trigger.hashCode()))
        simulation.burst(size.width / 2f, size.height * BurstOriginY, density)
        val dt = 1f / 60f
        repeat((FrozenFrameSeconds / dt).toInt()) { simulation.step(dt, size.height) }
        drawConfetti(simulation)
    }
}

private val ConfettiColors: List<Color> = CubeColor.entries.map(CubePalette::color)

private fun DrawScope.drawConfetti(simulation: ConfettiSimulation) {
    for (p in simulation.particles) {
        val alpha = simulation.alphaOf(p)
        if (alpha <= 0f) continue
        // Tumbling: the piece's apparent height follows the flip angle, and it darkens when seen
        // edge-on, which reads as paper twisting in 3D.
        val flip = cos(p.flipAngle)
        val base = ConfettiColors[p.colorIndex]
        val color = lerp(base, Color.Black, (1f - abs(flip)) * 0.35f).copy(alpha = alpha)
        withTransform({
            translate(p.x, p.y)
            rotate(p.rotation, pivot = Offset.Zero)
            scale(1f, flip.coerceIn(-1f, 1f).let { if (abs(it) < 0.08f) 0.08f else it }, pivot = Offset.Zero)
        }) {
            val size = Size(p.width, p.height)
            val topLeft = Offset(-p.width / 2f, -p.height / 2f)
            when (p.shape) {
                ConfettiShape.Sticker -> drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = size,
                    cornerRadius = CornerRadius(p.width * 0.22f),
                )
                ConfettiShape.Ribbon -> drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = size,
                    cornerRadius = CornerRadius(p.width * 0.5f),
                )
                ConfettiShape.Dot -> drawCircle(color = color, radius = p.width / 2f, center = Offset.Zero)
            }
        }
    }
}

/** Shapes of confetti pieces: little cube stickers, paper ribbons and round dots. */
internal enum class ConfettiShape { Sticker, Ribbon, Dot }

/**
 * Framework-free confetti physics, in pixels and seconds. Each particle is launched radially with
 * an upward kick, then integrates gravity, exponential air drag, a gentle side-to-side flutter,
 * spin and a 3D-looking flip, and fades out at the end of its life.
 */
internal class ConfettiSimulation(private val random: Random = Random.Default) {

    class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var rotation: Float,
        val spin: Float,
        var flipAngle: Float,
        val flipSpeed: Float,
        val flutterPhase: Float,
        val width: Float,
        val height: Float,
        val colorIndex: Int,
        val shape: ConfettiShape,
        val life: Float,
        var age: Float = 0f,
    )

    private val live = ArrayList<Particle>()

    /** Particles currently in flight. */
    val particles: List<Particle> get() = live

    /** True when nothing is left to draw. */
    val isIdle: Boolean get() = live.isEmpty()

    private var dpToPx = 1f

    /** Launches [count] particles from ([originX], [originY]); [density] is px per dp. */
    fun burst(originX: Float, originY: Float, density: Float, count: Int = DefaultCount) {
        dpToPx = density
        repeat(count) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val speed = (MinSpeedDp + random.nextFloat() * (MaxSpeedDp - MinSpeedDp)) * density
            val shape = when (random.nextFloat()) {
                in 0f..0.46f -> ConfettiShape.Sticker
                in 0.46f..0.86f -> ConfettiShape.Ribbon
                else -> ConfettiShape.Dot
            }
            val (w, h) = when (shape) {
                ConfettiShape.Sticker -> (8f + random.nextFloat() * 4f).let { it to it }
                ConfettiShape.Ribbon -> (4.5f + random.nextFloat() * 1.5f) to (12f + random.nextFloat() * 6f)
                ConfettiShape.Dot -> (5.5f + random.nextFloat() * 2.5f).let { it to it }
            }
            live += Particle(
                x = originX + (random.nextFloat() - 0.5f) * 24f * density,
                y = originY + (random.nextFloat() - 0.5f) * 16f * density,
                vx = cos(angle) * speed * 1.15f,
                vy = sin(angle) * speed - UpwardKickDp * density * (0.6f + random.nextFloat() * 0.6f),
                rotation = random.nextFloat() * 360f,
                spin = (if (random.nextBoolean()) 1f else -1f) * (160f + random.nextFloat() * 560f),
                flipAngle = random.nextFloat() * 2f * PI.toFloat(),
                flipSpeed = 4f + random.nextFloat() * 9f,
                flutterPhase = random.nextFloat() * 2f * PI.toFloat(),
                width = w * density,
                height = h * density,
                colorIndex = random.nextInt(CubeColor.entries.size),
                shape = shape,
                life = MinLife + random.nextFloat() * (MaxLife - MinLife),
            )
        }
    }

    /**
     * Advances the simulation by [dt] seconds (clamped to avoid tunnelling after a stall) and drops
     * particles that have expired or fallen more than a little below [floorY].
     */
    fun step(dt: Float, floorY: Float = Float.POSITIVE_INFINITY) {
        val t = dt.coerceIn(0f, MaxStep)
        if (t == 0f) return
        val damping = exp(-Drag * t)
        val gravity = GravityDp * dpToPx
        val flutter = FlutterDp * dpToPx
        val iterator = live.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.age += t
            p.vx = p.vx * damping + sin(p.age * 7f + p.flutterPhase) * flutter * t
            p.vy = p.vy * damping + gravity * t
            p.x += p.vx * t
            p.y += p.vy * t
            p.rotation += p.spin * t
            p.flipAngle += p.flipSpeed * t
            if (p.age >= p.life || p.y > floorY + 40f * dpToPx) iterator.remove()
        }
    }

    /** Opacity of [p]: pops in over the first 60ms, fades out over the last [FadeSeconds]. */
    fun alphaOf(p: Particle): Float {
        val fadeIn = (p.age / 0.06f).coerceIn(0f, 1f)
        val fadeOut = ((p.life - p.age) / FadeSeconds).coerceIn(0f, 1f)
        return fadeIn * fadeOut
    }

    companion object {
        const val DefaultCount = 150
        const val MinSpeedDp = 260f
        const val MaxSpeedDp = 1150f
        const val UpwardKickDp = 520f
        const val GravityDp = 1050f
        const val FlutterDp = 240f
        const val Drag = 1.9f
        const val MinLife = 2.3f
        const val MaxLife = 3.3f
        const val FadeSeconds = 0.7f
        const val MaxStep = 1f / 20f
    }
}
