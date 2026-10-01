package com.andhab.cubelens

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Launches the real [MainActivity] and drives the main flows end to end, to catch wiring problems
 * that screen-level tests cannot see (theme, view model, navigation, solver on a background thread).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppLaunchTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun pauseClock() {
        // Home has endless ambient animations; drive the clock by hand.
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun randomScrambleOpensAnAnimatedSolution() {
        node("Scan my cube")
        node("Try a random scramble").performClick()
        // The solver runs on a real background thread; keep frames flowing until it answers.
        awaitText("Move 1 of", timeoutMillis = 60_000)
        Espresso.pressBack()
        awaitText("Scan my cube")
    }

    @Test
    fun manualEntryOpensTheEditorAndBackReturnsHome() {
        node("Enter colors manually").performClick()
        awaitText("Copy your cube's colors")
        Espresso.pressBack()
        awaitText("Scan my cube")
    }

    @Test
    fun scanAsksForCameraPermissionFirst() {
        node("Scan my cube").performClick()
        awaitText("Allow camera")
    }

    private fun node(text: String): SemanticsNodeInteraction {
        awaitText(text)
        return compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)[0]
    }

    private fun awaitText(text: String, timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(64)
            val found = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            if (found) return
            Thread.sleep(10)
        }
        throw ComposeTimeoutException("'$text' did not appear within $timeoutMillis ms")
    }
}
