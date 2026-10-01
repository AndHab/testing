package com.andhab.cubelens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeError
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.ui.review.ReviewCheck
import com.andhab.cubelens.ui.review.ReviewSource
import com.andhab.cubelens.ui.review.ReviewState
import com.andhab.cubelens.ui.theme.CubeLensTheme
import com.github.takahirom.roborazzi.captureRoboImage

/** The user's real scanned cube, used for realistic previews. */
val UserCubeColors: List<CubeColor> =
    FaceletCube.parse("DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR").toColors()

/**
 * The user's cube with two edge stickers of different colors swapped, chosen (deterministically) so
 * that the first problem found is an edge whose color pair no real edge has.
 *
 * @return the colors and the two swapped sticker indices.
 */
fun impossibleEdgeSwap(): Pair<List<CubeColor>, Set<Int>> {
    val edgeStickers = UserCubeColors.indices.filter { it % 9 in setOf(1, 3, 5, 7) }
    for (a in edgeStickers) {
        for (b in edgeStickers) {
            if (b <= a || UserCubeColors[a] == UserCubeColors[b]) continue
            val swapped = UserCubeColors.toMutableList().also {
                it[a] = UserCubeColors[b]
                it[b] = UserCubeColors[a]
            }
            val check = ReviewState(swapped, ReviewSource.Scan).check
            if (check is ReviewCheck.Invalid && check.error is CubeError.ImpossibleEdge) return swapped to setOf(a, b)
        }
    }
    error("No swap of two edge stickers gives an impossible edge")
}

/**
 * Renders [content] full screen in the app theme and saves it as
 * `app/build/outputs/roborazzi/[name].png`.
 *
 * Runs in inspection mode (the aurora and entrance animations render a fixed frame) on a paused
 * clock, advanced by one frame plus [advanceMillis]: endless animations such as the idling hero
 * cube or pulsing flags would otherwise keep the test from ever going idle.
 */
fun ComposeContentTestRule.screenshot(
    name: String,
    advanceMillis: Long = 0,
    content: @Composable () -> Unit,
) {
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            CubeLensTheme(content)
        }
    }
    mainClock.advanceTimeByFrame()
    if (advanceMillis > 0) mainClock.advanceTimeBy(advanceMillis)
    onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
}
