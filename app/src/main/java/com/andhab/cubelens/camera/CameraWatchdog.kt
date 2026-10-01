package com.andhab.cubelens.camera

import androidx.camera.core.CameraState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The camera was bound but isn't working: another app holds it, it is disabled by policy or by Do
 * Not Disturb, it failed, or it simply never delivered a frame.
 *
 * @property errorCode the [CameraState] error code the camera reported, if any (e.g.
 *   [CameraState.ERROR_CAMERA_IN_USE]); null when it just never started.
 */
class CameraUnavailableException(message: String, val errorCode: Int? = null) : Exception(message)

/**
 * Notices a camera that is bound but not working, which binding itself doesn't report: errors
 * arrive later through [CameraState], and some failures produce no error at all, only no frames.
 */
object CameraWatchdog {

    /** How long a camera may take to deliver its first frame. */
    const val START_TIMEOUT_MILLIS: Long = 6_000

    /** How long a reported error may last before giving up; CameraX retries the recoverable ones. */
    const val ERROR_GRACE_MILLIS: Long = 2_000

    /**
     * Watches a camera that should be running (collect it only while the screen is visible) and
     * returns once it should be given up on: it didn't deliver a frame within [startTimeoutMillis],
     * or it reported an error and didn't get back to [CameraState.Type.OPEN] within
     * [errorGraceMillis]. Never returns while the camera is healthy.
     *
     * @param cameraStates the camera's states, e.g. `cameraInfo.cameraState.asFlow()`.
     * @param frameArrived emits true once a frame has arrived since watching started.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitFailure(
        cameraStates: Flow<CameraState>,
        frameArrived: Flow<Boolean>,
        startTimeoutMillis: Long = START_TIMEOUT_MILLIS,
        errorGraceMillis: Long = ERROR_GRACE_MILLIS,
    ): CameraUnavailableException {
        val neverStarted = flow {
            if (withTimeoutOrNull(startTimeoutMillis) { frameArrived.first { it } } == null) {
                emit(CameraUnavailableException("No camera frame within $startTimeoutMillis ms"))
            }
        }
        // The first error of a streak that only an open camera ends: while CameraX retries, the state
        // can go back and forth between opening and failing without the error showing every time.
        val lastingErrors = cameraStates
            .runningFold(null as CameraState.StateError?) { streak, state ->
                when {
                    state.error != null -> streak ?: state.error
                    state.type == CameraState.Type.OPEN -> null
                    else -> streak
                }
            }
            .distinctUntilChanged { old, new -> (old == null) == (new == null) }
            .transformLatest { error ->
                if (error != null) {
                    delay(errorGraceMillis)
                    emit(CameraUnavailableException("Camera error ${error.code}", error.code))
                }
            }
        return merge(neverStarted, lastingErrors).first()
    }
}
