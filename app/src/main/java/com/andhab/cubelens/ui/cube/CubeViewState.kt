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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.toLayerMove
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * State of a [Cube3D]: the sticker colors, the orbit angles of the camera and the layer turn that is
 * currently being animated.
 *
 * Works for any cube size N from [NxNGeometry.MIN_SIZE] to [NxNGeometry.MAX_SIZE]: the size is
 * inferred from the number of colors (6·N², in [NxNGeometry] order; 54 for a 3×3, which is the
 * same order as [com.andhab.cubelens.core.cube.Facelets]).
 *
 * All properties are snapshot state, so a [Cube3D] redraws (without recomposing) when they change.
 *
 * Turn semantics:
 *  - [animateMove] eases the layer turn and then commits `colors = permuted` with
 *    `new[i] = old[permutation[i]]` ([NxNGeometry.permutation]).
 *  - Starting another [animateMove] while one is running finishes the running turn instantly
 *    (its colors are committed and its coroutine is cancelled), so rapid taps never lose a move.
 *  - [snapTo] and [setPreview] cancel a running turn without committing it.
 *  - If the caller's coroutine is cancelled mid-turn, the colors stay unchanged.
 *
 * @param initialColors 6·N² sticker colors; `null` is an unknown sticker.
 * @param initialYaw orbit around the vertical axis in degrees; negative shows the right face.
 * @param initialPitch tilt in degrees; positive shows the top face. Clamped to [MIN_PITCH]..[MAX_PITCH].
 */
@Stable
class CubeViewState(
    initialColors: List<CubeColor?>,
    initialYaw: Float = -35f,
    initialPitch: Float = 28f,
) {
    private val sizeState = mutableIntStateOf(CubeSizes.ofCube(initialColors.size))

    /** The cube's size N (a 3×3 has size 3). Changes when [snapTo] is given another size. */
    val size: Int
        get() = sizeState.intValue

    /** Current committed sticker colors (6·N², [NxNGeometry] order; `null` = unknown, drawn neutral). */
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

    /** The layer turn being shown (outer, wide, inner slice or whole-cube rotation), or `null` at rest. */
    var animatingLayerMove: LayerMove? by mutableStateOf(null)
        private set

    /**
     * The turn being shown as a 3×3-style [Move]: non-null only while an outer-layer turn is shown.
     * See [animatingLayerMove] for turns of any layers.
     */
    val animatingMove: Move?
        get() = animatingLayerMove?.toMove()

    /**
     * Linear time fraction (0..1) of [animatingLayerMove]. The renderer applies the turn easing, so a
     * preview at a given fraction looks exactly like the animation at that moment.
     */
    var moveProgress: Float by mutableFloatStateOf(0f)
        private set

    private var turnGeneration = 0
    private var turnJob: Job? = null
    private var viewGeneration = 0
    private var viewJob: Job? = null

    /**
     * Animates the outer-layer turn [move] over [durationMillis] and then commits the permuted
     * colors. Suspends until the turn has finished. Same as `animateMove(move.toLayerMove())`.
     */
    suspend fun animateMove(move: Move, durationMillis: Int = DEFAULT_TURN_MILLIS) {
        animateMove(move.toLayerMove(), durationMillis)
    }

    /**
     * Animates [move] (any layers: outer, wide, inner slice or a whole-cube rotation) over
     * [durationMillis] and then commits the permuted colors. Suspends until the turn has finished.
     * See the class documentation for how overlapping calls behave.
     *
     * @param durationMillis defaults to [defaultTurnMillis]: heavier turns of more layers take a
     *   little longer, like on a real cube.
     * @throws IllegalArgumentException if [move] needs more layers than the cube has.
     */
    suspend fun animateMove(move: LayerMove, durationMillis: Int = defaultTurnMillis(move)) {
        require(durationMillis >= 0) { "durationMillis must not be negative" }
        requireFits(move)
        completeTurnInstantly()
        val generation = ++turnGeneration
        turnJob = currentCoroutineContext()[Job]
        animatingLayerMove = move
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
                animatingLayerMove = null
                moveProgress = 0f
                turnJob = null
            }
        }
    }

    /**
     * Cancels any turn (without committing it) and shows [colors], which may be a cube of another
     * size (6·N² colors); [size] follows.
     */
    fun snapTo(colors: List<CubeColor?>) {
        val n = CubeSizes.ofCube(colors.size)
        cancelTurn()
        animatingLayerMove = null
        moveProgress = 0f
        this.colors = colors.toList()
        sizeState.intValue = n
    }

    /**
     * Cancels any running turn and freezes [move] at linear time fraction [progress] without changing
     * [colors]. Pass `null` (or call [clearPreview]) to return to rest. Useful for screenshots,
     * tests, hints and scrubbing.
     *
     * @throws IllegalArgumentException if [move] needs more layers than the cube has.
     */
    fun setPreview(move: LayerMove?, progress: Float) {
        if (move != null) requireFits(move)
        cancelTurn()
        animatingLayerMove = move
        moveProgress = if (move == null) 0f else progress.coerceIn(0f, 1f)
    }

    /**
     * [setPreview] for an outer-layer turn. To return to rest, call [clearPreview] (or
     * `setPreview(null, 0f)`, which resolves to the [LayerMove] overload); a caller holding a
     * nullable `Move?` can write `setPreview(move?.toLayerMove(), progress)`.
     */
    fun setPreview(move: Move, progress: Float) {
        setPreview(move.toLayerMove(), progress)
    }

    /**
     * Returns the cube to rest: cancels any running turn without committing it and clears a frozen
     * preview. Same as `setPreview(null, 0f)`.
     */
    fun clearPreview() {
        setPreview(null, 0f)
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
        val move = animatingLayerMove ?: return
        val job = turnJob
        // Only a real animation is fast-forwarded; a frozen preview was never meant to be committed.
        if (job != null) colors = colors.permutedBy(move)
        cancelTurn()
        animatingLayerMove = null
        moveProgress = 0f
    }

    private fun cancelTurn() {
        turnGeneration++
        turnJob?.cancel()
        turnJob = null
    }

    private fun requireFits(move: LayerMove) {
        require(move.toDepth <= size) { "Move ${move.notation} needs ${move.toDepth} layers, the cube is ${size}x$size" }
    }

    companion object {
        /** Lowest allowed [pitch] (looking up at the bottom face). */
        const val MIN_PITCH = -80f

        /** Highest allowed [pitch] (looking down at the top face). */
        const val MAX_PITCH = 80f

        /** Duration of a single-layer turn (any size), in milliseconds. */
        const val DEFAULT_TURN_MILLIS = 380

        /**
         * Default duration of [move]: [DEFAULT_TURN_MILLIS] for a single layer, about 6% longer for
         * each extra layer turned together, at most 30% longer (e.g. a whole 7×7 rotation).
         */
        fun defaultTurnMillis(move: LayerMove): Int {
            val extra = (0.06f * (move.width - 1)).coerceAtMost(0.3f)
            return (DEFAULT_TURN_MILLIS * (1f + extra)).toInt()
        }
    }
}

/** Applies a turn to sticker colors (6·N² of them): `new[i] = old[permutation[i]]`. */
internal fun List<CubeColor?>.permutedBy(move: LayerMove): List<CubeColor?> {
    val p = NxNGeometry.of(CubeSizes.ofCube(size)).permutation(move)
    return List(size) { this[p[it]] }
}

/** [permutedBy] for an outer-layer turn. */
internal fun List<CubeColor?>.permutedBy(move: Move): List<CubeColor?> = permutedBy(move.toLayerMove())

/**
 * Remembers a [CubeViewState] showing [colors] (6·N² stickers for an N×N cube).
 *
 * The initial angles are only used when the state is created. If a later recomposition passes
 * different [colors] (compared by value, possibly of another size), the state snaps to them,
 * cancelling any running turn; colors changed by [CubeViewState.animateMove] are kept as long as
 * the argument stays the same.
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
