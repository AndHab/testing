package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.cube.CubeViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Performs the turns of a [SolvePlayback] on screen. In the app this is the 3D cube
 * ([CubeViewState]); tests use a fake.
 */
interface CubeAnimator {

    /**
     * Animates [move] over [durationMillis] and then shows its result; suspends until the turn is
     * done. If the calling coroutine is cancelled mid-turn, the move must not be applied.
     */
    suspend fun turn(move: Move, durationMillis: Int)

    /** Shows [colors] at once (54 colors, facelet order), abandoning any turn in progress. */
    fun snapTo(colors: List<CubeColor>)
}

/** Playback speeds offered on the solve screen, as a multiplier of the normal pace. */
enum class PlaybackSpeed(val factor: Float) {
    Slow(0.5f),
    Normal(1f),
    Fast(2f),
    ;

    /** Short on-screen label, e.g. "0.5×", "1×", "2×". */
    val label: String
        get() = (if (factor % 1f == 0f) factor.toInt().toString() else factor.toString()) + "×"

    /** The speed after this one, wrapping around from the fastest to the slowest. */
    fun next(): PlaybackSpeed = entries[(ordinal + 1) % entries.size]
}

/**
 * The state of a step-by-step solution: which moves have been applied, whether it is auto-playing
 * and how fast. It drives a [CubeAnimator] so the cube on screen always matches [position].
 *
 * ### Model
 * [position] (often called *k*) is the number of [moves] applied to the cube so far, `0..moveCount`.
 * Commands move a [target] position; a single driver coroutine walks [position] towards it one
 * animated turn at a time. Because every turn goes through that one coroutine, commands may arrive
 * as fast as the user can tap and the cube can never fall out of step with [position]:
 *  - [next] animates `moves[k]` and then increments k; [previous] animates the inverse of
 *    `moves[k - 1]` and then decrements k. Taps that arrive while a turn is running are queued
 *    (as a further step of the target) and the backlog plays out in quicker turns.
 *  - [jumpTo] and [restart] cancel any running turn and snap the cube to the state after `moves[0 until k]`.
 *  - [play] advances on its own with a short pause between moves and stops at the end. Any manual
 *    command while playing pauses playback first.
 *
 * When the system animator duration scale is 0 ("Remove animations"), turns are snapped instead of
 * animated; the pause between auto-played moves is kept so each move can still be read.
 *
 * All members must be used from the thread of [scope] (the main thread in the app). The state is
 * snapshot state, so Compose observes it directly.
 *
 * @param startColors the scrambled cube: 54 sticker colors in facelet order with six distinct centers.
 * @param moves the solution; applying all of them to [startColors] solves the cube.
 * @param animator shows the turns; it is snapped to the state at [initialPosition] on creation.
 * @param scope runs the turns; cancelling it stops playback.
 * @param initialPosition the number of moves already applied, e.g. when restoring saved state.
 * @param initialSpeed the starting [speed].
 * @param initialCelebration restored value of [celebration].
 * @param motionScale returns the current animator duration scale; the default reads Compose's
 *   [MotionDurationScale] from the calling coroutine's context.
 */
@Stable
class SolvePlayback(
    startColors: List<CubeColor>,
    val moves: List<Move>,
    private val animator: CubeAnimator,
    private val scope: CoroutineScope,
    initialPosition: Int = 0,
    initialSpeed: PlaybackSpeed = PlaybackSpeed.Normal,
    initialCelebration: Int? = null,
    private val motionScale: suspend () -> Float = ::contextMotionScale,
) {
    /** Number of moves in the solution. */
    val moveCount: Int = moves.size

    /** The cube's colors after each prefix of [moves]: `states[k]` is the cube at position k. */
    private val states: List<List<CubeColor>> = statesAlong(startColors, moves)

    init {
        require(initialPosition in 0..moveCount) { "initialPosition $initialPosition is outside 0..$moveCount" }
    }

    /** Number of moves applied to the cube on screen (k), `0..moveCount`. Changes after each turn. */
    var position: Int by mutableIntStateOf(initialPosition)
        private set

    /** Where playback is heading: [position] once all queued turns have played. */
    var target: Int by mutableIntStateOf(initialPosition)
        private set

    /** True while moves advance on their own. */
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** Pace of the turns and of the pauses between auto-played moves. Takes effect from the next turn. */
    var speed: PlaybackSpeed by mutableStateOf(initialSpeed)

    /**
     * Increases each time playback arrives at the solved end, starting at 1 for a solution that is
     * already finished when created. A fresh value per arrival, made to key a confetti burst.
     */
    var celebration: Int by mutableIntStateOf(initialCelebration ?: if (initialPosition == moveCount) 1 else 0)
        private set

    /**
     * Index into [moves] of the move to present as "current": the move being turned, the next one
     * to make when at rest, or (while stepping back) the move being undone. Equals [moveCount] once
     * every move is done.
     */
    val currentIndex: Int
        get() = if (target < position) position - 1 else position

    /** The move at [currentIndex], or null when every move is done. */
    val currentMove: Move?
        get() = moves.getOrNull(currentIndex)

    /** True when every move has been applied and nothing is queued: the cube is solved. */
    val isFinished: Boolean
        get() = position == moveCount && target == moveCount

    /** True if there is a move left to step forward to. */
    val canGoForward: Boolean
        get() = target < moveCount

    /** True if there is a move to step back over. */
    val canGoBack: Boolean
        get() = target > 0

    private var driver: Job? = null

    /** Bumped whenever the driver is replaced or cancelled; a stale driver stops touching state. */
    private var driverGeneration = 0

    /** True while the driver sits in the pause between two auto-played moves (no turn in flight). */
    private var waiting = false

    /** Skips the pause before the first move after [play], so pressing play responds at once. */
    private var playJustStarted = false

    init {
        animator.snapTo(states[initialPosition])
    }

    /** The cube's 54 colors after the first [k] moves. */
    fun colorsAt(k: Int): List<CubeColor> = states[k]

    /** Steps one move forward (pausing playback). Does nothing at the end. */
    fun next() {
        pause()
        if (target < moveCount) {
            target++
            ensureDriver()
        }
    }

    /** Steps one move back (pausing playback). Does nothing at the start. */
    fun previous() {
        pause()
        if (target > 0) {
            target--
            ensureDriver()
        }
    }

    /**
     * Shows the cube after the first [k] moves at once: pauses playback, cancels any running or
     * queued turns and snaps the cube. [k] is clamped to `0..moveCount`.
     */
    fun jumpTo(k: Int) {
        val destination = k.coerceIn(0, moveCount)
        val wasFinished = isFinished
        cancelDriver()
        isPlaying = false
        position = destination
        target = destination
        animator.snapTo(states[destination])
        if (destination == moveCount && !wasFinished) celebration++
    }

    /** Back to the scrambled cube, ready to go again. */
    fun restart() = jumpTo(0)

    /** Starts auto-playing from the current move; from the solved end it starts over. */
    fun play() {
        if (isPlaying) return
        if (isFinished) {
            if (moveCount == 0) return
            jumpTo(0)
        }
        isPlaying = true
        playJustStarted = true
        ensureDriver()
    }

    /** Stops auto-playing. A turn already in flight finishes; nothing new starts. */
    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        // A driver that is only waiting between moves has nothing to finish.
        if (waiting) cancelDriver()
    }

    /** [pause] when playing, [play] otherwise. */
    fun togglePlay() = if (isPlaying) pause() else play()

    /** Moves to the next speed, wrapping around (0.5× → 1× → 2× → 0.5×). */
    fun cycleSpeed() {
        speed = speed.next()
    }

    private fun ensureDriver() {
        if (driver != null) return
        val token = ++driverGeneration
        driver = scope.launch { drive(token) }
    }

    private fun cancelDriver() {
        driverGeneration++
        driver?.cancel()
        driver = null
        waiting = false
    }

    /**
     * Walks [position] towards [target] and, while playing, keeps advancing the target. Runs until
     * there is nothing left to do; a newer generation (from [cancelDriver]) retires it.
     */
    private suspend fun drive(token: Int) {
        try {
            while (token == driverGeneration) {
                val from = position
                when {
                    target != from -> {
                        val forward = target > from
                        val to = if (forward) from + 1 else from - 1
                        val move = if (forward) moves[from] else moves[from - 1].inverse
                        val backlog = abs(target - from) > 1
                        turn(move, to, backlog)
                        if (token != driverGeneration) return
                        position = to
                        if (to == moveCount && target == moveCount) celebration++
                    }
                    isPlaying && from < moveCount -> {
                        if (playJustStarted) {
                            playJustStarted = false
                        } else {
                            waiting = true
                            try {
                                delay((PAUSE_MILLIS / speed.factor).roundToLong())
                            } finally {
                                if (token == driverGeneration) waiting = false
                            }
                        }
                        if (token != driverGeneration) return
                        if (isPlaying && target == position && position < moveCount) target = position + 1
                    }
                    else -> {
                        isPlaying = false
                        return
                    }
                }
            }
        } finally {
            if (token == driverGeneration) {
                // Normally a no-op; after an unexpected failure it leaves a consistent, idle state.
                driver = null
                waiting = false
                target = position
                isPlaying = false
            }
        }
    }

    private suspend fun turn(move: Move, to: Int, backlog: Boolean) {
        if (motionScale() == 0f) {
            animator.snapTo(states[to])
            return
        }
        val normal = (TURN_MILLIS / speed.factor).roundToInt()
        animator.turn(move, if (backlog) minOf(CATCH_UP_TURN_MILLIS, normal) else normal)
    }

    companion object {
        /** Duration of one turn at 1× speed. */
        const val TURN_MILLIS = 480

        /** Duration of the turns that work off a backlog of quick taps, at any speed. */
        const val CATCH_UP_TURN_MILLIS = 170

        /** Pause between two auto-played moves at 1× speed, time to read the next move. */
        const val PAUSE_MILLIS = 620L

        /**
         * Saves position, speed and celebration count; restoring creates a playback for the same
         * [startColors] and [moves] that drives [animator] in [scope].
         */
        fun saver(
            startColors: List<CubeColor>,
            moves: List<Move>,
            animator: CubeAnimator,
            scope: CoroutineScope,
        ): Saver<SolvePlayback, IntArray> = Saver(
            save = { intArrayOf(it.position, it.speed.ordinal, it.celebration) },
            restore = { saved ->
                SolvePlayback(
                    startColors = startColors,
                    moves = moves,
                    animator = animator,
                    scope = scope,
                    initialPosition = saved[0].coerceIn(0, moves.size),
                    initialSpeed = PlaybackSpeed.entries.getOrElse(saved[1]) { PlaybackSpeed.Normal },
                    initialCelebration = saved[2],
                )
            },
        )
    }
}

/**
 * Remembers the [SolvePlayback] for [moves] on [startColors], driving [cubeState]. Position, speed
 * and the celebration count survive configuration changes and process death; playback stops when
 * this leaves the composition.
 */
@Composable
fun rememberSolvePlayback(
    startColors: List<CubeColor>,
    moves: List<Move>,
    cubeState: CubeViewState,
): SolvePlayback {
    val scope = rememberCoroutineScope()
    val animator = remember(cubeState) { CubeViewAnimator(cubeState) }
    return rememberSaveable(
        startColors,
        moves,
        animator,
        saver = SolvePlayback.saver(startColors, moves, animator, scope),
    ) {
        SolvePlayback(startColors, moves, animator, scope)
    }
}

/** Plays turns on the 3D cube. */
internal class CubeViewAnimator(private val state: CubeViewState) : CubeAnimator {
    override suspend fun turn(move: Move, durationMillis: Int) = state.animateMove(move, durationMillis)

    override fun snapTo(colors: List<CubeColor>) = state.snapTo(colors)
}

/** The animator duration scale Compose provides to the calling coroutine (1 when absent). */
private suspend fun contextMotionScale(): Float =
    currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f

/**
 * The colors of [startColors] after each prefix of [moves] (`moves.size + 1` states), computed on
 * the cube model so the solution is replayed exactly as the solver meant it.
 */
private fun statesAlong(startColors: List<CubeColor>, moves: List<Move>): List<List<CubeColor>> {
    require(startColors.size == Facelets.COUNT) { "Need ${Facelets.COUNT} colors, got ${startColors.size}" }
    val scheme = requireNotNull(FaceletCube.schemeOf(startColors)) { "The six centers must have six different colors" }
    val start = requireNotNull(FaceletCube.fromColors(startColors))
    return moves.runningFold(start) { cube, move -> cube.apply(move) }.map { it.toColors(scheme) }
}
