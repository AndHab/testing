package com.andhab.cubelens.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
import org.junit.Assert.assertTrue
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

    /**
     * Picking another size and straight back while the hero is still shrinking away: the hero grows
     * back to its full size and opacity instead of staying half faded.
     */
    @Test
    fun quickSwitchBack() {
        compose.screenshot("home_switch_back_before", advanceMillis = 900) {
            var size by remember { mutableIntStateOf(3) }
            HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, size = size, onSizeChange = { size = it })
        }
        val hero = compose.onNodeWithContentDescription("3D cube", substring = true)
        val peakBefore = hero.captureToImage().peakBrightness()
        assertTrue("A fully shown hero has bright stickers, peak $peakBefore", peakBefore >= FULLY_SHOWN_PEAK)
        compose.onNodeWithContentDescription("4 by 4 cube").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithContentDescription("3 by 3 cube").performClick()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/home_switch_back.png")
        compose.onNodeWithContentDescription("3 by 3 cube").assertIsSelected()
        val peakAfter = hero.captureToImage().peakBrightness()
        assertTrue(
            "The hero should be fully back (peak $peakAfter, $peakBefore before the switch)",
            peakAfter >= FULLY_SHOWN_PEAK,
        )
    }

    /**
     * A small phone: the steps make way, the gaps tighten and the hero shrinks a little, so the size
     * switch and all three ways in still fit on screen.
     */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun compact() {
        compose.screenshot("home_compact", advanceMillis = 900) {
            HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, size = 7)
        }
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (action in listOf("7 by 7 cube", "Scan my 7×7", "Enter colors manually", "Try a random scramble")) {
            val bounds = compose.onNode(hasText(action) or hasContentDescription(action)).fetchSemanticsNode().boundsInRoot
            assertTrue("\"$action\" should be fully on screen: $bounds in $viewport", bounds.bottom <= viewport.bottom)
        }
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

/**
 * The brightest channel value in the image. The hero cube's lit stickers come close to 255, and
 * whatever the angle, a cube drawn at partial opacity over the dark background can't get close.
 */
private fun ImageBitmap.peakBrightness(): Int {
    val pixels = IntArray(width * height)
    readPixels(pixels)
    return pixels.maxOf { argb -> maxOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF) }
}

/** [peakBrightness] of a fully shown hero is at least this; a partly faded one stays well below. */
private const val FULLY_SHOWN_PEAK = 215
