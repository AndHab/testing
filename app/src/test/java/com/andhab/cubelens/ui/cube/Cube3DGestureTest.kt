package com.andhab.cubelens.ui.cube

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.FaceletCube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drag-to-orbit interplay with the idle spin of [Cube3D]. */
@RunWith(AndroidJUnit4::class)
class Cube3DGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = CubeViewState(FaceletCube.SOLVED.toColors())
    private var interactive by mutableStateOf(true)
    private var density by mutableStateOf(Density(3f))

    @Test
    fun idleSpinResumesWhenTheGestureIsResetMidDrag() {
        val held = grabAndHold()
        // A density change resets the pointer input: the gesture coroutine is cancelled without
        // ever seeing the finger lift.
        density = Density(2.5f)
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("idle spin resumed: ${state.yaw} vs $held", state.yaw > held + 8f)
    }

    @Test
    fun idleSpinResumesWhenInteractionIsTurnedOffMidDrag() {
        val held = grabAndHold()
        interactive = false
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("idle spin resumed: ${state.yaw} vs $held", state.yaw > held + 8f)
    }

    /** Shows an idly spinning cube, drags it past touch slop and keeps holding; returns the held yaw. */
    private fun grabAndHold(): Float {
        // The idle spin never lets the UI go idle, so drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                Cube3D(state, Modifier.size(240.dp).testTag("cube"), interactive = interactive, autoRotate = true)
            }
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("cube").performTouchInput {
            down(center)
            moveBy(Offset(80f, 0f))
        }
        compose.mainClock.advanceTimeBy(100)
        val held = state.yaw
        compose.mainClock.advanceTimeBy(500)
        assertEquals("no idle spin while the finger is down", held, state.yaw, 0.01f)
        return held
    }
}
