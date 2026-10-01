package com.andhab.cubelens.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.ui.screenshot
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
    fun hero() = compose.screenshot("home_hero", advanceMillis = 900) {
        HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {})
    }

    @Test
    fun scrambling() = compose.screenshot("home_scrambling") {
        HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {}, scrambling = true)
    }

    /** A small phone: the steps make way so the hero keeps its size; the page scrolls for the rest. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun compact() {
        compose.screenshot("home_compact", advanceMillis = 900) {
            HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {})
        }
        compose.onNodeWithText("Scan my cube").assertExists()
    }

    /** The largest common font size: step captions wrap instead of being cut off. */
    @Test
    fun largeText() = compose.screenshot("home_large_text", advanceMillis = 900) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
            HomeScreen(onScan = {}, onManualEntry = {}, onRandomScramble = {})
        }
    }
}
