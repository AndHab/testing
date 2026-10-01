package com.andhab.cubelens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.ui.theme.CubeLensTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Plays real bursts on a black stage and looks at the pixels to see whether confetti is in flight. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfettiBurstTest {

    /** Stands in for the system animator duration scale ("Remove animations" sets it to 0). */
    private val motion = object : MotionDurationScale {
        var factor = 1f
        override val scaleFactor: Float get() = factor
    }

    @get:Rule
    val compose = createComposeRule(effectContext = motion)

    private var trigger by mutableStateOf<Any?>(null)

    @Before
    fun pauseClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun aNewTriggerFiresABurstThatPlaysOut() {
        compose.setContent { Stage() }
        assertFalse("idle overlay draws nothing", confettiVisible())

        fire(1)
        compose.mainClock.advanceTimeBy(InFlightMillis)
        assertTrue("burst is in flight", confettiVisible())

        compose.mainClock.advanceTimeBy(PlayedOutMillis)
        assertFalse("burst has faded out", confettiVisible())
    }

    @Test
    fun removedAnimationsSuppressTheBurst() {
        motion.factor = 0f
        compose.setContent { Stage() }

        fire(1)
        compose.mainClock.advanceTimeBy(InFlightMillis)
        assertFalse(confettiVisible())
    }

    @Test
    fun theSameTriggerDoesNotReplayWhenTheScreenComesBack() {
        // Like a navigation back stack: the screen leaves composition and returns with its saved state.
        var shown by mutableStateOf(true)
        compose.setContent {
            val savedScreens = rememberSaveableStateHolder()
            if (shown) savedScreens.SaveableStateProvider("solved") { Stage() }
        }
        fire(1)
        compose.mainClock.advanceTimeBy(InFlightMillis)
        assertTrue("first visit celebrates", confettiVisible())
        compose.mainClock.advanceTimeBy(PlayedOutMillis)

        shown = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(InFlightMillis)
        shown = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(InFlightMillis)
        assertFalse("returning to the screen does not replay", confettiVisible())

        fire(2)
        compose.mainClock.advanceTimeBy(InFlightMillis)
        assertTrue("a new trigger still fires", confettiVisible())
    }

    /** Changes the trigger and tells the paused-clock recomposer about it right away. */
    private fun fire(value: Any) {
        trigger = value
        Snapshot.sendApplyNotifications()
    }

    @Composable
    private fun Stage() {
        CubeLensTheme {
            Box(
                Modifier
                    .size(240.dp)
                    .background(Color.Black)
                    .testTag(StageTag),
            ) {
                ConfettiBurst(trigger)
            }
        }
    }

    /** True when any pixel of the black stage is lit by a confetti piece. */
    private fun confettiVisible(): Boolean {
        val pixels = compose.onNodeWithTag(StageTag).captureToImage().toPixelMap()
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c.red + c.green + c.blue > 0.25f) return true
            }
        }
        return false
    }

    private companion object {
        const val StageTag = "stage"
        const val InFlightMillis = 250L
        const val PlayedOutMillis = 4_000L
    }
}
