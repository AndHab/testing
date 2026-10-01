package com.andhab.cubelens.camera

import androidx.camera.core.CameraState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWatchdogTest {

    private val states = MutableStateFlow(CameraState.create(CameraState.Type.PENDING_OPEN))
    private val frames = MutableStateFlow(false)

    private fun error(type: CameraState.Type, code: Int): CameraState = CameraState.create(type, CameraState.StateError.create(code))

    @Test
    fun aHealthyCameraIsNeverGivenUpOn() = runTest {
        val watch = async { CameraWatchdog.awaitFailure(states, frames, startTimeoutMillis = 6_000, errorGraceMillis = 2_000) }
        states.value = CameraState.create(CameraState.Type.OPENING)
        advanceTimeBy(400)
        states.value = CameraState.create(CameraState.Type.OPEN)
        frames.value = true
        advanceTimeBy(60_000)
        assertFalse(watch.isCompleted)
        watch.cancel()
    }

    @Test
    fun noFrameInTimeGivesUp() = runTest {
        val watch = async { CameraWatchdog.awaitFailure(states, frames, startTimeoutMillis = 6_000, errorGraceMillis = 2_000) }
        states.value = CameraState.create(CameraState.Type.OPEN)
        advanceTimeBy(5_900)
        assertFalse(watch.isCompleted)
        advanceTimeBy(200)
        assertTrue(watch.isCompleted)
        assertNull(watch.await().errorCode)
    }

    @Test
    fun aCameraInUseIsGivenUpOnAfterTheGracePeriod() = runTest {
        frames.value = true // A frame arrived, then another app took the camera.
        val watch = async { CameraWatchdog.awaitFailure(states, frames, startTimeoutMillis = 6_000, errorGraceMillis = 2_000) }
        states.value = CameraState.create(CameraState.Type.OPEN)
        runCurrent()
        states.value = error(CameraState.Type.PENDING_OPEN, CameraState.ERROR_CAMERA_IN_USE)
        advanceTimeBy(700)
        // CameraX retrying: the error doesn't show on every state, but the streak goes on.
        states.value = CameraState.create(CameraState.Type.OPENING)
        advanceTimeBy(700)
        states.value = error(CameraState.Type.PENDING_OPEN, CameraState.ERROR_CAMERA_IN_USE)
        advanceTimeBy(500)
        assertFalse(watch.isCompleted)
        advanceTimeBy(200)
        assertTrue(watch.isCompleted)
        assertEquals(CameraState.ERROR_CAMERA_IN_USE, watch.await().errorCode)
    }

    @Test
    fun anErrorThatClearsInTimeIsForgiven() = runTest {
        frames.value = true
        val watch = async { CameraWatchdog.awaitFailure(states, frames, startTimeoutMillis = 6_000, errorGraceMillis = 2_000) }
        states.value = error(CameraState.Type.OPENING, CameraState.ERROR_OTHER_RECOVERABLE_ERROR)
        advanceTimeBy(1_500)
        states.value = CameraState.create(CameraState.Type.OPEN)
        advanceTimeBy(10_000)
        assertFalse(watch.isCompleted)
        watch.cancel()
    }

    @Test
    fun aDisabledCameraIsGivenUpOnEvenBeforeTheStartTimeout() = runTest {
        val watch = async { CameraWatchdog.awaitFailure(states, frames, startTimeoutMillis = 6_000, errorGraceMillis = 2_000) }
        states.value = error(CameraState.Type.CLOSED, CameraState.ERROR_CAMERA_DISABLED)
        advanceTimeBy(2_100)
        assertTrue(watch.isCompleted)
        assertEquals(CameraState.ERROR_CAMERA_DISABLED, watch.await().errorCode)
    }
}
