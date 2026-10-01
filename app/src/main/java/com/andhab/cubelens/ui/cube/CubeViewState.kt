package com.andhab.cubelens.ui.cube

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * State of a [Cube3D]: the sticker colors, the orbit angles of the camera and the layer turn that is
 * currently being animated.
 *
 * All properties are snapshot state, so a [Cube3D] redraws (without recomposing) when they change.
 *
 * Turn semantics:
 *  - [animateMove] eases the layer turn and then commits `colors = permuted` with
 *    `new[i] = old[move.permutation[i]]`.
 *  - Starting another [animateMove] while one is running finishes the running turn instantly
 *    (its colors are committed and its coroutine is cancelled), so rapid taps never lose a move.
 *  - [snapTo] and [setPreview] cancel a running turn without committing it.
 *  - If the caller's coroutine is cancelled mid-turn, the colors stay unchanged.
 *
 * @param initialColors 54 sticker colors in facelet order; `null` is an unknown sticker.
 * @param initialYaw orbit around the vertical axis in degrees; negative shows the right face.
 * @param initialPitch tilt in degrees; positive shows the top face. Clamped to [MIN_PITCH]..[MAX_PITCH].
 */
@Stable
class CubeViewState(
    initialColors: List<CubeColor?>,
    initialYaw: Float = -35f,
    initialPitch: Float = 28f,
) {
    init {
        requireColorCount(initialColors)
    }

    /** Current committed sticker colors (54, facelet order; `null` = unknown, drawn neutral). */
    var colors: List<CubeColor?> by mutableStateOf(initialColors.toList())
        private set

    private val yawState = mutableFloatStateOf(wrapDegrees(initialYaw))
    private val pitchState = mutableFloatStateOf(initialPitch.coerceIn(MIN_PITCH, MAX_PITCH))

    /** Orbit around the vertical axis, in degrees, wrapped into [-180, 180). */
    var yaw: Float
        get() = yawState.floatValue
        set(value) {
            yawState.floatValue = wrapDegrees(value)
        }

    /** Tilt towards the top (positive) or bottom (negative) face, clamped to [MIN_PITCH]..[MAX_PITCH]. */
    var pitch: Float
        get() = pitchState.floatValue
        set(value) {
            pitchState.floatValue = value.coerceIn(MIN_PITCH, MAX_PITCH)
        }

    /** The layer turn being shown, or `null` when the cube is at rest. */
    var animatingMove: Move? by mutableStateOf(null)
        private set

    /**
     * Linear time fraction (0..1) of [animatingMove]. The renderer applies the turn easing, so a
     * preview at a given fraction looks exactly like the animation at that moment.
     */
    var moveProgress: Float by mutableFloatStateOf(0f)
        private set

    private var turnGeneration = 0
    private var turnJob: Job? = null
    private var viewGeneration = 0
    private var viewJob: Job? = null

    /**
     * Animates [move] over [durationMillis] and then commits the permuted colors. Suspends until the
     * turn has finished. See the class documentation for how overlapping calls behave.
     */
    suspend fun animateMove(move: Move, durationMillis: Int = 380) {
        require(durationMillis >= 0) { "durationMillis must not be negative" }
        completeTurnInstantly()
        val generation = ++turnGeneration
        turnJob = currentCoroutineContext()[Job]
        animatingMove = move
        moveProgress = 0f
        var completed = false
        try {
            if (durationMillis > 0) {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis, easing = LinearEasing),
                ) { value, _ ->
                    // A newer turn, snap or preview took over: stop driving the state.
                    if (generation != turnGeneration) throw CancellationException("Turn superseded")
                    moveProgress = value
                }
            }
            completed = true
        } finally {
            if (generation == turnGeneration) {
                if (completed) colors = colors.permutedBy(move)
                animatingMove = null
                moveProgress = 0f
                turnJob = null
            }
        }
    }

    /** Cancels any turn (without committing it) and shows [colors]. */
    fun snapTo(colors: List<CubeColor?>) {
        requireColorCount(colors)
        cancelTurn()
        animatingMove = null
        moveProgress = 0f
        this.colors = colors.toList()
    }

    /**
     * Cancels any running turn and freezes [move] at linear time fraction [progress] without changing
     * [colors]. Pass `null` to return to rest. Useful for screenshots, tests and scrubbing.
     */
    fun setPreview(move: Move?, progress: Float) {
        cancelTurn()
        animatingMove = move
        moveProgress = if (move == null) 0f else progress.coerceIn(0f, 1f)
    }

    /**
     * Smoothly orbits the camera to [targetYaw] / [targetPitch], taking the short way around.
     * A newer call (or the user grabbing the cube) cancels this one.
     */
    suspend fun animateView(
        targetYaw: Float,
        targetPitch: Float,
        animationSpec: AnimationSpec<Float> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow),
    ) {
        cancelViewAnimation()
        val generation = ++viewGeneration
        viewJob = currentCoroutineContext()[Job]
        val startYaw = yaw
        val startPitch = pitch
        val deltaYaw = wrapDegrees(targetYaw - startYaw)
        val deltaPitch = targetPitch.coerceIn(MIN_PITCH, MAX_PITCH) - startPitch
        try {
            animate(0f, 1f, animationSpec = animationSpec) { t, _ ->
                if (generation != viewGeneration) throw CancellationException("View animation superseded")
                yaw = startYaw + deltaYaw * t
                pitch = startPitch + deltaPitch * t
            }
        } finally {
            if (generation == viewGeneration) viewJob = null
        }
    }

    /** Stops a running [animateView], e.g. because the user started dragging. */
    internal fun cancelViewAnimation() {
        viewGeneration++
        viewJob?.cancel()
        viewJob = null
    }

    private fun completeTurnInstantly() {
        val move = animatingMove ?: return
        val job = turnJob
        // Only a real animation is fast-forwarded; a frozen preview was never meant to be committed.
        if (job != null) colors = colors.permutedBy(move)
        cancelTurn()
        animatingMove = null
        moveProgress = 0f
    }

    private fun cancelTurn() {
        turnGeneration++
        turnJob?.cancel()
        turnJob = null
    }

    companion object {
        /** Lowest allowed [pitch] (looking up at the bottom face). */
        const val MIN_PITCH = -80f

        /** Highest allowed [pitch] (looking down at the top face). */
        const val MAX_PITCH = 80f

        private fun requireColorCount(colors: List<CubeColor?>) =
            require(colors.size == Facelets.COUNT) { "Need ${Facelets.COUNT} sticker colors, got ${colors.size}" }
    }
}

/** Applies a move to sticker colors: `new[i] = old[move.permutation[i]]`. */
internal fun List<CubeColor?>.permutedBy(move: Move): List<CubeColor?> {
    val p = move.permutation
    return List(Facelets.COUNT) { this[p[it]] }
}

/**
 * Remembers a [CubeViewState] showing [colors].
 *
 * The initial angles are only used when the state is created. If a later recomposition passes
 * different [colors] (compared by value), the state snaps to them, cancelling any running turn;
 * colors changed by [CubeViewState.animateMove] are kept as long as the argument stays the same.
 */
@Composable
fun rememberCubeViewState(
    colors: List<CubeColor?>,
    initialYaw: Float = -35f,
    initialPitch: Float = 28f,
): CubeViewState {
    val state = remember { CubeViewState(colors, initialYaw, initialPitch) }
    val applied = remember { AppliedColors(colors) }
    SideEffect {
        if (applied.colors != colors) {
            applied.colors = colors
            state.snapTo(colors)
        }
    }
    return state
}

/** The last colors argument [rememberCubeViewState] applied; plain field, not snapshot state. */
private class AppliedColors(var colors: List<CubeColor?>)
