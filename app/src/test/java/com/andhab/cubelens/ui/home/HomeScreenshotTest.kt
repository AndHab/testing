package com.andhab.cubelens.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.ui.screenshot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the Home screen. PNGs land in app/build/outputs/roborazzi/home_*.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hero() {
        compose.screenshot("home_hero", advanceMillis = 900) {
            HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {})
        }
        compose.onNodeWithText("Scan my 3×3").assertExists()
        compose.onNodeWithContentDescription("3 by 3 cube").assertIsSelected()
    }

    @Test
    fun fiveByFive() {
        compose.screenshot("home_5x5", advanceMillis = 900) {
            HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, size = 5)
        }
        compose.onNodeWithText("Scan my 5×5").assertExists()
        compose.onNodeWithContentDescription("5 by 5 cube").assertIsSelected()
    }

    @Test
    fun twoByTwo() = compose.screenshot("home_2x2", advanceMillis = 900) {
        HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, size = 2)
    }

    @Test
    fun sevenBySeven() = compose.screenshot("home_7x7", advanceMillis = 900) {
        HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, size = 7)
    }

    @Test
    fun scrambling() = compose.screenshot("home_scrambling") {
        HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, scrambling = true)
    }

    /** Tapping a size selects it, the hero morphs into that size and the copy follows. */
    @Test
    fun pickingASize() {
        var picked = 0
        compose.screenshot("home_picking_before", advanceMillis = 900) {
            var size by remember { mutableIntStateOf(3) }
            HomeScreen(
                onScan = {},
                onManualEntry = {},
                onRandomScramble = {},
                size = size,
                onSizeChange = {
                    picked = it
                    size = it
                },
            )
        }
        compose.onNodeWithContentDescription("4 by 4 cube").performClick()
        // Mid-morph: the 4×4 pops back in with a twirl while the capsule springs over.
        compose.mainClock.advanceTimeBy(280)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/home_picking_morph.png")
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(4, picked)
        compose.onNodeWithContentDescription("4 by 4 cube").assertIsSelected()
        compose.onNodeWithContentDescription("3 by 3 cube").assertIsNotSelected()
        compose.onNodeWithText("Scan my 4×4").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/home_picked_4x4.png")
    }

    /** A small phone: the steps make way so the hero keeps its size; the page scrolls for the rest. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun compact() {
        compose.screenshot("home_compact", advanceMillis = 900) {
            HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, size = 7)
        }
        compose.onNodeWithText("Scan my 7×7").assertExists()
    }

    /** The largest common font size: step captions wrap instead of being cut off. */
    @Test
    fun largeText() = compose.screenshot("home_large_text", advanceMillis = 900) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
            HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, size = 4)
        }
    }
}
