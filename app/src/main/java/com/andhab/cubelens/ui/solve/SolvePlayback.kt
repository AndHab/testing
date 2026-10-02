package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.core.nxn.SolveStage
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
     * Animates [move] (any layers) over [durationMillis] and then shows its result; suspends until
     * the turn is done. If the calling coroutine is cancelled mid-turn, the move must not be applied.
     */
    suspend fun turn(move: LayerMove, durationMillis: Int)

    /** Shows [colors] at once (6·N² colors, [NxNGeometry] order), abandoning any turn in progress. */
    fun snapTo(colors: List<CubeColor>)
}

/** Playback speeds offered on the solve screen, as a multiplier of the normal pace. */
enum class PlaybackSpeed(val factor: Float) {
    Slow(0.5f),
    Normal(1f),
    Fast(2f),
    Faster(4f),
    ;

    /** Short on-screen label, e.g. "0.5×", "1×", "4×". */
    val label: String
        get() = (if (factor % 1f == 0f) factor.toInt().toString() else factor.toString()) + "×"

    /** The speed after this one, wrapping around from the fastest to the slowest. */
    fun next(): PlaybackSpeed = entries[(ordinal + 1) % entries.size]
}

/**
 * The state of a step-by-step, staged solution for a cube of any size: which moves have been
 * applied, whether it is auto-playing and how fast. It drives a [CubeAnimator] so the cube on screen
 * always matches [position].
 *
 * ### Model
 * [moves] is the whole solution, every stage's moves one after another. [position] (often called
 * *k*) is the number of them applied to the cube so far, `0..moveCount`; the cube then shows
 * `NxNCube.of(n, startColors)` with `moves[0 until k]` applied. Commands move a [target] position;
 * a single driver coroutine walks [position] towards it one animated turn at a time. Because every
 * turn goes through that one coroutine, commands may arrive as fast as the user can tap and the
 * cube can never fall out of step with [position]:
 *  - [next] animates `moves[k]` and then increments k; [previous] animates the inverse of
 *    `moves[k - 1]` and then decrements k. Taps that arrive while a turn is running are queued
 *    (as a further step of the target) and the backlog plays out in quicker turns.
 *  - [jumpTo], [restart], [nextStage] and [previousStage] cancel any running turn and snap the
 *    cube to the state after `moves[0 until k]`.
 *  - [play] advances on its own with a short pause between moves and stops at the end. Any manual
 *    command while playing pauses playback first. Pressing play drops a backlog of queued steps
 *    back: only the turn in flight finishes before the cube heads forward again.
 *
 * Every state along the way is computed once, up front, so stepping and jumping are O(1) however
 * long the solution is.
 *
 * When the system animator duration scale is 0 ("Remove animations"), turns are snapped instead of
 * animated; the pause between auto-played moves is kept so each move can still be read.
 *
 * All members must be used from the thread of [scope] (the main thread in the app). The state is
 * snapshot state, so Compose observes it directly.
 *
 * @param startColors the scrambled cube: 6·N² sticker colors in [NxNGeometry] order (N is inferred
 *   from their number).
 * @param solution the staged solution for an N×N cube; applying its moves to [startColors] solves
 *   the cube. Stages without moves are left out.
 * @param animator shows the turns; it is snapped to the state at [initialPosition] on creation.
 * @param scope runs the turns; cancelling it stops playback.
 * @param initialPosition the number of moves already applied, e.g. when restoring saved state.
 * @param initialSpeed the starting [speed].
 * @param initialCelebration restored value of [celebration].
 * @param resumePlaying true when restoring a state that was auto-playing; [resumeAfterRestore]
 *   then starts playing again.
 * @param motionScale returns the current animator duration scale; the default reads Compose's
 *   [MotionDurationScale] from the calling coroutine's context.
 * @throws IllegalArgumentException if [startColors] is not a whole cube of [solution]'s size or a
 *   move needs more layers than the cube has.
 */
@Stable
class SolvePlayback(
    startColors: List<CubeColor>,
    solution: NxNSolution,
    private val animator: CubeAnimator,
    private val scope: CoroutineScope,
    initialPosition: Int = 0,
    initialSpeed: PlaybackSpeed = PlaybackSpeed.Normal,
    initialCelebration: Int? = null,
    resumePlaying: Boolean = false,
    private val motionScale: suspend () -> Float = ::contextMotionScale,
) {
    /** Size of the cube (N for an N×N×N cube). */
    val n: Int = cubeSizeOf(startColors.size)

    /** The stages of the solution that have moves, in order. */
    val stages: List<SolveStage> = solution.stages.filter { it.moves.isNotEmpty() }

    /** The whole solution: every stage's moves, one after another. */
    val moves: List<LayerMove> = stages.flatMap { it.moves }

    /** Number of moves in the solution. */
    val moveCount: Int = moves.size

    /** `stageStarts[s]` is the index of stage s's first move; the last entry is [moveCount]. */
    private val stageStarts: IntArray = IntArray(stages.size + 1).also { starts ->
        stages.forEachIndexed { s, stage -> starts[s + 1] = starts[s] + stage.moves.size }
    }

    init {
        require(solution.n == n) { "The solution is for a ${solution.n}x${solution.n} cube, the colors are a ${n}x$n" }
        moves.firstOrNull { it.toDepth > n }?.let { throw IllegalArgumentException("Move $it needs more layers than a ${n}x$n cube has") }
        require(initialPosition in 0..moveCount) { "initialPosition $initialPosition is outside 0..$moveCount" }
    }

    /** The cube's colors after each prefix of [moves]: `states[k]` is the cube at position k. */
    private val states: List<List<CubeColor>> =
        moves.runningFold(NxNCube.of(n, startColors)) { cube, move -> cube.apply(move) }.map { it.toColors() }

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
    val currentMove: LayerMove?
        get() = moves.getOrNull(currentIndex)

    /**
     * Index into [stages] of the stage of the current move (the last stage once every move is
     * done), or -1 for a solution without moves.
     */
    val currentStage: Int
        get() = if (moveCount == 0) -1 else stageOf(currentIndex)

    /** True when every move has been applied and nothing is queued: the cube is solved. */
    val isFinished: Boolean
        get() = position == moveCount && target == moveCount

    /** True if there is a move left to step forward to. */
    val canGoForward: Boolean
        get() = target < moveCount

    /** True if there is a move to step back over. */
    val canGoBack: Boolean
        get() = target > 0

    /** True if a later stage follows the current one ([nextStage] does something). */
    val hasNextStage: Boolean
        get() = currentStage in 0 until stages.lastIndex

    /** True if [previousStage] does something: any move has been made or is under way. */
    val hasPreviousStage: Boolean
        get() = position > 0 || target > 0

    /**
     * Index of the move the cube is waiting for while playback stands still (paused, nothing in
     * flight or queued, moves left); null while turning, while auto-playing and once finished.
     */
    val waitingIndex: Int?
        get() = if (!isPlaying && target == position && position < moveCount) position else null

    private var driver: Job? = null

    /** Bumped whenever the driver is replaced or cancelled; a stale driver stops touching state. */
    private var driverGeneration = 0

    /** True while the driver sits in the pause between two auto-played moves (no turn in flight). */
    private var waiting = false

    /** Skips the pause before the first move after [play], so pressing play responds at once. */
    private var playJustStarted = false

    /** The position the turn in flight lands on, or null when no turn is in flight. */
    private var turningTo: Int? = null

    /** Set when restored mid-play; cleared by [resumeAfterRestore]. */
    private var playWhenResumed = resumePlaying

    init {
        animator.snapTo(states[initialPosition])
    }

    /** The cube's 6·N² colors after the first [k] moves. */
    fun colorsAt(k: Int): List<CubeColor> = states[k]

    /** Index into [stages] of the stage that move [moveIndex] belongs to; [moveCount] counts as the last stage. */
    fun stageOf(moveIndex: Int): Int {
        require(moveCount > 0) { "A solution without moves has no stages" }
        val index = moveIndex.coerceIn(0, moveCount - 1)
        // The last stage whose first move is at or before index.
        var lo = 0
        var hi = stages.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (stageStarts[mid] <= index) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Index into [moves] of the first move of stage [stage]. */
    fun stageStart(stage: Int): Int = stageStarts[stage]

    /** Number of moves in stage [stage]. */
    fun stageSize(stage: Int): Int = stageStarts[stage + 1] - stageStarts[stage]

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

    /** Jumps to the start of the stage after the current one (see [jumpTo]). Does nothing in the last stage. */
    fun nextStage() {
        if (hasNextStage) jumpTo(stageStart(currentStage + 1))
    }

    /**
     * Jumps back to the start of the current stage, or, when no move of it has been made yet, to
     * the start of the stage before (see [jumpTo]); like "previous track" on a music player.
     */
    fun previousStage() {
        val stage = currentStage
        if (stage < 0) return
        val start = stageStart(stage)
        when {
            position > start || target > start -> jumpTo(start)
            stage > 0 -> jumpTo(stageStart(stage - 1))
        }
    }

    /**
     * Starts auto-playing from the current move; from the solved end it starts over. Steps back that
     * are still queued are dropped: the turn in flight finishes and playback heads forward from there.
     */
    fun play() {
        if (isPlaying) return
        if (isFinished) {
            if (moveCount == 0) return
            jumpTo(0)
        }
        if (target < position) target = turningTo ?: position
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

    /**
     * Picks auto-play back up if this state was restored from one that was playing (see the
     * `resumePlaying` constructor parameter); does nothing otherwise or when called again. Call it
     * once the screen is shown, e.g. from a `LaunchedEffect`, not during composition.
     */
    fun resumeAfterRestore() {
        if (!playWhenResumed) return
        playWhenResumed = false
        if (position < moveCount) play()
    }

    /** Moves to the next speed, wrapping around (0.5× → 1× → 2× → 4× → 0.5×). */
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
        turningTo = null
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
                        turningTo = to
                        turn(move, to, backlog)
                        if (token != driverGeneration) return
                        turningTo = null
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
                turningTo = null
                target = position
                isPlaying = false
            }
        }
    }

    private suspend fun turn(move: LayerMove, to: Int, backlog: Boolean) {
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
         * Saves position, speed, celebration count and whether it was playing; restoring creates a
         * playback for the same [startColors] and [solution] that drives [animator] in [scope] and
         * resumes playing on [resumeAfterRestore].
         */
        fun saver(
            startColors: List<CubeColor>,
            solution: NxNSolution,
            animator: CubeAnimator,
            scope: CoroutineScope,
        ): Saver<SolvePlayback, IntArray> = Saver(
            save = { intArrayOf(it.position, it.speed.ordinal, it.celebration, if (it.isPlaying) 1 else 0) },
            restore = { saved ->
                SolvePlayback(
                    startColors = startColors,
                    solution = solution,
                    animator = animator,
                    scope = scope,
                    initialPosition = saved[0].coerceIn(0, solution.length),
                    initialSpeed = PlaybackSpeed.entries.getOrElse(saved[1]) { PlaybackSpeed.Normal },
                    initialCelebration = saved[2],
                    resumePlaying = saved.getOrElse(3) { 0 } == 1,
                )
            },
        )
    }
}

/**
 * Remembers the [SolvePlayback] for [solution] on [startColors], driving [cubeState]. Position,
 * speed, the celebration count and auto-play survive configuration changes and process death;
 * playback stops when this leaves the composition.
 */
@Composable
fun rememberSolvePlayback(
    startColors: List<CubeColor>,
    solution: NxNSolution,
    cubeState: CubeViewState,
): SolvePlayback {
    val scope = rememberCoroutineScope()
    val animator = remember(cubeState) { CubeViewAnimator(cubeState) }
    val playback = rememberSaveable(
        startColors,
        solution,
        animator,
        saver = SolvePlayback.saver(startColors, solution, animator, scope),
    ) {
        SolvePlayback(startColors, solution, animator, scope)
    }
    LaunchedEffect(playback) { playback.resumeAfterRestore() }
    return playback
}

/** Plays turns on the 3D cube. */
internal class CubeViewAnimator(private val state: CubeViewState) : CubeAnimator {
    override suspend fun turn(move: LayerMove, durationMillis: Int) = state.animateMove(move, durationMillis)

    override fun snapTo(colors: List<CubeColor>) = state.snapTo(colors)
}

/** The animator duration scale Compose provides to the calling coroutine (1 when absent). */
private suspend fun contextMotionScale(): Float =
    currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f

/** N of a whole N×N×N cube given its 6·N² sticker colors. */
private fun cubeSizeOf(stickerCount: Int): Int =
    (NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE).firstOrNull { 6 * it * it == stickerCount }
        ?: throw IllegalArgumentException(
            "A cube needs 6·N² colors with N in ${NxNGeometry.MIN_SIZE}..${NxNGeometry.MAX_SIZE}, got $stickerCount",
        )
