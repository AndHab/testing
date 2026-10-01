package com.andhab.cubelens.ui.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
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
}
