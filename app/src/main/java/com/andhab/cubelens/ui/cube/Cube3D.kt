package com.andhab.cubelens.ui.cube

import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A glossy, real-time 3D twisty cube of any size (2×2 up to 10×10) drawn with Compose Canvas.
 *
 * Layer turns, colors, size and the camera come from [state]; sticker colors are drawn in
 * [LocalStickerPalette], so a pastel or knock-off cube looks like itself. The cube is centered in
 * the available space, sized to fit any orientation, and sits above a soft warm glow. The cube
 * takes the same space whatever its size, so bigger cubes simply have smaller cubies.
 *
 * @param interactive drag to orbit the camera; releasing with speed keeps it spinning with inertia.
 *   Drags past touch slop are consumed in every direction, so inside a vertically scrolling
 *   container the page does not scroll while the drag starts on the cube.
 * @param autoRotate slow idle spin and a gentle float (the Home hero). Pauses while the user drags.
 * @param highlightFacelets stickers (indices into [CubeViewState.colors]) outlined with a pulsing
 *   [com.andhab.cubelens.ui.theme.Brand.Danger] ring.
 * @param focusFace show this face at full brightness and dim the others; set [CubeViewState.yaw]
 *   and [CubeViewState.pitch] (e.g. from [viewAnglesFor]) so the face is visible.
 */
@Composable
fun Cube3D(
    state: CubeViewState,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    autoRotate: Boolean = false,
    highlightFacelets: Set<Int> = emptySet(),
    focusFace: Face? = null,
) {
    val renderer = remember { CubeRenderer() }
    val frame = remember { CubeFrame() }
    val palette = LocalStickerPalette.current
    val scope = rememberCoroutineScope()
    val orbit = remember(state) { OrbitController(state, scope) }

    // Idle float phase in seconds; only advances while autoRotate is on.
    val idleClock = remember { mutableFloatStateOf(0f) }
    if (autoRotate) {
        LaunchedEffect(state) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                    last = now
                    idleClock.floatValue += dt
                    if (!orbit.isBusy) state.yaw += IDLE_SPIN_DEGREES_PER_SECOND * dt
                }
            }
        }
    }

    val pulse: State<Float>? = if (highlightFacelets.isNotEmpty()) rememberHighlightPulse() else null
    // A lookup table, so drawing never boxes sticker indices.
    val highlights = remember(highlightFacelets) { highlightTable(highlightFacelets) }

    val faceDim = Face.entries.map { face ->
        animateFloatAsState(
            targetValue = if (focusFace == null || focusFace == face) 0f else 1f,
            animationSpec = tween(420, easing = LinearEasing),
            label = "dim${face.name}",
        )
    }
    val focusGlow by animateFloatAsState(if (focusFace != null) 1f else 0f, tween(500), label = "focusGlow")

    val gestures = if (interactive) {
        Modifier.pointerInput(orbit) { orbitGestures(orbit) }
    } else {
        Modifier
    }

    Canvas(
        modifier
            .then(gestures)
            .semantics { contentDescription = if (interactive) "3D cube, drag to rotate" else "3D cube" },
    ) {
        frame.colors = state.colors
        frame.size = state.size
        frame.yaw = state.yaw
        frame.pitch = state.pitch
        frame.move = state.animatingLayerMove
        frame.progress = state.moveProgress
        frame.highlights = highlights
        frame.palette = palette
        frame.pulse = pulse?.value ?: 0f
        for (i in faceDim.indices) frame.faceDim[i] = faceDim[i].value
        frame.focusFace = focusFace
        frame.focusGlow = focusGlow
        frame.lift = if (autoRotate) sin(idleClock.floatValue * FLOAT_RADIANS_PER_SECOND) else 0f
        drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, frame) }
    }
}

/** Sticker indices as a lookup table (`table[i]` = highlighted), or null for none. */
internal fun highlightTable(stickers: Set<Int>): BooleanArray? {
    val valid = stickers.filter { it >= 0 }
    if (valid.isEmpty()) return null
    return BooleanArray(valid.max() + 1).also { table -> for (i in valid) table[i] = true }
}

/** Degrees of yaw per second for the idle spin. */
private const val IDLE_SPIN_DEGREES_PER_SECOND = 16f

/** One up-and-down cycle of the idle float every 4.2 seconds. */
private const val FLOAT_RADIANS_PER_SECOND = (2 * PI / 4.2).toFloat()

/** Degrees of orbit for a drag across the full shorter side of the canvas. */
private const val DEGREES_PER_SIZE = 230f

/** Turns drags into camera orbit and keeps spinning with inertia after a fling. */
private class OrbitController(private val state: CubeViewState, private val scope: CoroutineScope) {
    private var flingJob: Job? = null
    private var dragging = false

    /** True while the user is touching the cube or it is still coasting after a fling. */
    val isBusy: Boolean get() = dragging || flingJob?.isActive == true

    fun onTouch() {
        flingJob?.cancel()
        flingJob = null
        state.cancelViewAnimation()
        dragging = true
    }

    fun rotateBy(deltaYaw: Float, deltaPitch: Float) {
        state.yaw += deltaYaw
        state.pitch += deltaPitch
    }

    /** Ends a touch; a non-zero velocity (degrees per second) keeps the cube coasting. */
    fun onRelease(yawVelocity: Float, pitchVelocity: Float) {
        dragging = false
        if (yawVelocity == 0f && pitchVelocity == 0f) return
        val decay = FloatExponentialDecaySpec(FRICTION)
        flingJob = scope.launch {
            launch {
                var last = 0f
                animateDecay(0f, yawVelocity.coerceIn(-MAX_FLING, MAX_FLING), decay) { value, _ ->
                    state.yaw += value - last
                    last = value
                }
            }
            launch {
                var last = 0f
                animateDecay(0f, pitchVelocity.coerceIn(-MAX_FLING, MAX_FLING), decay) { value, _ ->
                    state.pitch += value - last
                    last = value
                }
            }
        }
    }

    private companion object {
        const val FRICTION = 2.2f

        /** Fling speed cap in degrees per second. */
        const val MAX_FLING = 1400f
    }
}

/**
 * Drag to orbit: a touch catches a coasting cube, a drag past touch slop turns it, a release flings.
 *
 * The release always runs, even when the gesture coroutine is cancelled mid-drag without a lift
 * (e.g. a density or configuration change resets the pointer input). That way the controller never
 * stays busy and the idle spin always resumes.
 */
private suspend fun PointerInputScope.orbitGestures(orbit: OrbitController) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        orbit.onTouch()
        var release = Velocity.Zero
        try {
            val degreesPerPx = DEGREES_PER_SIZE / min(size.width, size.height).coerceAtLeast(1)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var slop = Offset.Zero
            val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                slop = over
            } ?: return@awaitEachGesture
            orbit.rotateBy(slop.x * degreesPerPx, slop.y * degreesPerPx)
            tracker.addPosition(start.uptimeMillis, start.position)
            val completed = drag(start.id) { change ->
                val delta = change.positionChange()
                orbit.rotateBy(delta.x * degreesPerPx, delta.y * degreesPerPx)
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
            }
            if (completed) release = tracker.calculateVelocity() * degreesPerPx
        } finally {
            orbit.onRelease(release.x, release.y)
        }
    }
}
