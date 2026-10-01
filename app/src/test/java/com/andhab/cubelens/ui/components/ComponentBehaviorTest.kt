package com.andhab.cubelens.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.theme.CubeLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentBehaviorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryButtonClicksAndMeetsMinimumHeight() {
        var clicks = 0
        compose.setContent { CubeLensTheme { PrimaryButton("Scan my cube", onClick = { clicks++ }) } }

        compose.onNodeWithText("Scan my cube")
            .assertIsEnabled()
            .assertHasClickAction()
            .assertHeightIsAtLeast(ButtonHeight)
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun primaryButtonIgnoresClicksWhileLoadingAndSaysSo() {
        var clicks = 0
        compose.setContent { CubeLensTheme { PrimaryButton("Solve", onClick = { clicks++ }, loading = true) } }

        compose.onNodeWithText("Solve")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading"))
            .performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun disabledButtonsIgnoreClicks() {
        var clicks = 0
        compose.setContent {
            CubeLensTheme {
                Box {
                    PrimaryButton("Primary", onClick = { clicks++ }, enabled = false)
                }
            }
        }
        compose.onNodeWithText("Primary").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun secondaryButtonClicks() {
        var clicks = 0
        compose.setContent { CubeLensTheme { SecondaryButton("Enter colors", onClick = { clicks++ }) } }

        compose.onNodeWithText("Enter colors").assertHeightIsAtLeast(ButtonHeight).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun textActionClicksAndIsTallEnoughToTap() {
        var clicks = 0
        compose.setContent { CubeLensTheme { TextAction("Skip", onClick = { clicks++ }) } }

        compose.onNodeWithText("Skip").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun smallCircleIconButtonStillHasA48dpTouchTargetAndALabel() {
        var clicks = 0
        compose.setContent {
            CubeLensTheme {
                CircleIconButton(Icons.Rounded.FlashOn, contentDescription = "Torch", onClick = { clicks++ }, size = 36.dp)
            }
        }
        compose.onNodeWithContentDescription("Torch")
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun topBarBackButtonIsLabelledAndClickable() {
        var backs = 0
        compose.setContent { CubeLensTheme { TopBar(title = "Review", onBack = { backs++ }) } }

        compose.onNodeWithText("Review").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun stepDotsAnnounceOneBasedProgress() {
        var current by mutableIntStateOf(0)
        compose.setContent { CubeLensTheme { StepDots(count = 6, current = current) } }

        compose.onNodeWithContentDescription("Step 1 of 6").assertExists()
        current = 3
        compose.onNodeWithContentDescription("Step 4 of 6").assertExists()
    }

    @Test
    fun stickerGridStickersAreLabelledAndTappable() {
        var tapped = -1
        val face = listOf(
            CubeColor.WHITE, CubeColor.RED, CubeColor.GREEN,
            CubeColor.BLUE, CubeColor.ORANGE, CubeColor.YELLOW,
            CubeColor.RED, null, CubeColor.WHITE,
        )
        compose.setContent {
            CubeLensTheme { StickerGrid(face, Modifier, onStickerClick = { tapped = it }) }
        }
        compose.onNodeWithContentDescription("Row 2, column 3: Yellow").performClick()
        assertEquals(5, tapped)
        compose.onNodeWithContentDescription("Row 3, column 2: Not set").assertExists()
    }

    @Test
    fun confettiOverlayDoesNotBlockTouches() {
        var clicks = 0
        var trigger by mutableStateOf<Any?>(null)
        compose.setContent {
            CubeLensTheme {
                Box {
                    PrimaryButton("Again", onClick = { clicks++ })
                    ConfettiBurst(trigger = trigger)
                }
            }
        }
        trigger = 1
        compose.onNodeWithText("Again").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun statusBannerShowsTitleAndMessage() {
        compose.setContent {
            CubeLensTheme {
                StatusBanner(BannerKind.Warning, "Two pieces look swapped", message = "Re-scan the front face.")
            }
        }
        compose.onNodeWithText("Two pieces look swapped", substring = true).assertExists()
        compose.onNodeWithText("Re-scan the front face.", substring = true).assertExists()
    }

    @Test
    fun flaggedStickersAnnounceThatTheyNeedChecking() {
        val face = listOf(
            CubeColor.RED, CubeColor.RED, CubeColor.GREEN,
            CubeColor.BLUE, CubeColor.ORANGE, CubeColor.YELLOW,
            CubeColor.RED, CubeColor.WHITE, CubeColor.WHITE,
        )
        compose.setContent { CubeLensTheme { StickerGrid(face, Modifier, highlighted = setOf(0)) } }

        compose.onNodeWithContentDescription("Row 1, column 1: Red")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Needs checking"))
        compose.onNodeWithContentDescription("Row 1, column 2: Red")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun moveChipsSpeakTheMoveAndItsPlaybackState() {
        compose.setContent {
            CubeLensTheme {
                Row {
                    MoveChip("U2", state = MoveState.Done)
                    MoveChip("R'", state = MoveState.Current)
                    MoveChip("F")
                }
            }
        }
        compose.onNodeWithContentDescription("U two")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Done"))
        compose.onNodeWithContentDescription("R prime")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Current move"))
        compose.onNodeWithContentDescription("F")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Upcoming"))
        // The raw glyphs are not read out on top of the spoken form.
        compose.onNodeWithText("R′").assertDoesNotExist()
    }

    @Test
    fun notationIsDisplayedWithATruePrimeMark() {
        assertEquals("R\u2032", displayNotation("R'"))
        assertEquals("U2", displayNotation("U2"))
        assertEquals("F", displayNotation("F"))
    }

    @Test
    fun brandLockupReadsAsTheAppName() {
        compose.setContent { CubeLensTheme { BrandLockup() } }

        compose.onNodeWithContentDescription("CubeLens").assertExists()
        compose.onNodeWithText("Cube").assertDoesNotExist()
        compose.onNodeWithText("Lens").assertDoesNotExist()
    }

    @Test
    fun topBarTitleStaysCenteredBetweenTheSideSlots() {
        val title = "A rather long screen title that cannot fit"
        compose.setContent {
            CubeLensTheme {
                Box(Modifier.width(300.dp)) {
                    TopBar(title = title, onBack = {}) {
                        CircleIconButton(Icons.Rounded.Share, contentDescription = "Share", onClick = {})
                        CircleIconButton(Icons.Rounded.MoreHoriz, contentDescription = "More", onClick = {})
                    }
                }
            }
        }
        val titleBounds = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
        val back = compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot()
        val share = compose.onNodeWithContentDescription("Share").getUnclippedBoundsInRoot()
        assertTrue("title overlaps the back button", titleBounds.left >= back.right)
        assertTrue("title runs under the actions", titleBounds.right <= share.left)
        val center = (titleBounds.left + titleBounds.right) / 2
        assertEquals(150f, center.value, 0.5f)
    }
}
