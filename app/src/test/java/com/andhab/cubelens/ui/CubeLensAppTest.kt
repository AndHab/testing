package com.andhab.cubelens.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.theme.CubeLensTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Drives the whole app shell through its UI: navigation, editing and system back. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CubeLensAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val viewModel = AppViewModel(
        solver = object : CubeSolver {
            override fun prepare() = Unit
            override fun solve(cube: FaceletCube): List<Move> = emptyList()
        },
        workDispatcher = Dispatchers.Main,
    )

    @Test
    fun manualEntryEditingAndBack() {
        // The idling hero cube animates forever, so the clock is driven by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent { CubeLensTheme { CubeLensApp(viewModel = viewModel) } }
        settle()

        compose.onNodeWithText("Enter colors manually").performClick()
        settle()
        compose.onNodeWithText("Check your cube").assertExists()
        compose.onNodeWithText("48 stickers to go").assertExists()
        compose.onNodeWithText("Solve it").assertIsNotEnabled()

        // The first sticker is selected; picking a color paints it and moves on.
        compose.onNodeWithContentDescription("Red, 1 of 9").performClick()
        settle()
        compose.onNodeWithContentDescription("Top face, row 1, column 1: Red").assertExists()
        compose.onNodeWithText("47 stickers to go").assertExists()

        compose.onNodeWithContentDescription("Undo").performClick()
        settle()
        compose.onNodeWithText("48 stickers to go").assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(Screen.Home, viewModel.state.value.screen)
        compose.onNodeWithText("Scan my cube").assertExists()
    }

    @Test
    fun topBarBackLeavesTheReview() {
        compose.mainClock.autoAdvance = false
        compose.setContent { CubeLensTheme { CubeLensApp(viewModel = viewModel) } }
        settle()
        compose.onNodeWithText("Enter colors manually").performClick()
        settle()
        assertTrue(viewModel.state.value.screen is Screen.Review)

        compose.onNodeWithContentDescription("Back").performClick()
        settle()
        assertEquals(Screen.Home, viewModel.state.value.screen)
    }

    /** Lets screen transitions and the staggered entrance finish. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }
}
