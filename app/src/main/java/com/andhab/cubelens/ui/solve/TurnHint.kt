package com.andhab.cubelens.ui.solve

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.MotionDurationScale
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.cube.TurnEasing
import com.andhab.cubelens.ui.cube.signedQuarterTurns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Shows which layers turn next, and which way, while the cube waits for [move] (any layers: outer,
 * wide or inner): every few seconds they flick a little way into the turn and ease back. The person
 * sees it from where they hold the cube, so this works for the sides pointing away from them (back,
 * bottom, left) and for inner layers just as well as for the faces they can see. The colors never
 * change.
 *
 * Runs until cancelled. Every frame checks [stillWaiting] before touching the cube, and the cube is
 * left alone as soon as it returns false, so a turn or snap that takes over is never disturbed, even
 * in the frames before this coroutine is cancelled. Does nothing while animations are turned off.
 */
internal suspend fun CubeViewState.hintTurnWhileWaiting(move: LayerMove, stillWaiting: () -> Boolean) {
    try {
        delay(TurnHint.FIRST_DELAY_MILLIS)
        while (true) {
            if (motionScale() != 0f) nudge(move, stillWaiting)
            delay(TurnHint.INTERVAL_MILLIS)
        }
    } finally {
        // Cancelled mid-nudge with nothing taking over (e.g. the screen went away): settle the layer.
        if (stillWaiting() && animatingLayerMove == move) setPreview(null, 0f)
    }
}

/** One flick of [move]'s layers into the turn and back; returns early once [stillWaiting] is false. */
private suspend fun CubeViewState.nudge(move: LayerMove, stillWaiting: () -> Boolean) {
    var start = -1L
    while (true) {
        val more = withFrameMillis { now ->
            if (start < 0) start = now
            when {
                !stillWaiting() -> false
                now - start >= TurnHint.NUDGE_MILLIS -> {
                    setPreview(null, 0f)
                    false
                }
                else -> {
                    val degrees = TurnHint.DEGREES * TurnHint.lift(now - start)
                    setPreview(move, TurnHint.progressFor(move, degrees))
                    true
                }
            }
        }
        if (!more) return
    }
}

/** The animator duration scale Compose provides to the calling coroutine (1 when absent). */
private suspend fun motionScale(): Float = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f

/** Timing and shape of the [hintTurnWhileWaiting] nudge. */
internal object TurnHint {

    /** Rest before the first nudge, so a quick next tap never sees one. */
    const val FIRST_DELAY_MILLIS = 900L

    /** Rest between nudges. */
    const val INTERVAL_MILLIS = 2_300L

    /** Length of one nudge: a quick flick out, a short hold and a softer return. */
    const val NUDGE_MILLIS = 900L

    /** How far the layer turns at the height of a nudge. */
    const val DEGREES = 16f

    private const val OUT_MILLIS = 260f
    private const val HOLD_MILLIS = 120f

    /** Fraction (0..1) of [DEGREES] the layer is turned [elapsedMillis] into a nudge. */
    fun lift(elapsedMillis: Long): Float {
        val t = elapsedMillis.toFloat()
        return when {
            t <= 0f -> 0f
            t < OUT_MILLIS -> LinearOutSlowInEasing.transform(t / OUT_MILLIS)
            t < OUT_MILLIS + HOLD_MILLIS -> 1f
            t < NUDGE_MILLIS -> 1f - FastOutSlowInEasing.transform((t - OUT_MILLIS - HOLD_MILLIS) / (NUDGE_MILLIS - OUT_MILLIS - HOLD_MILLIS))
            else -> 0f
        }
    }

    /**
     * The time fraction of [move]'s turn animation at which its layer has turned [degrees], found by
     * inverting the turn easing, so a nudge turns every move (quarter or half) by the same angle.
     */
    fun progressFor(move: LayerMove, degrees: Float): Float {
        val eased = (degrees / (90f * abs(move.signedQuarterTurns))).coerceIn(0f, 1f)
        var lo = 0f
        var hi = 1f
        repeat(BISECTION_STEPS) {
            val mid = (lo + hi) / 2f
            if (TurnEasing.transform(mid) < eased) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    private const val BISECTION_STEPS = 20
}
