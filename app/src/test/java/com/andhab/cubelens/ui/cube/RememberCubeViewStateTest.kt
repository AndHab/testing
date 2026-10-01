package com.andhab.cubelens.ui.cube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [rememberCubeViewState] keeps one state and follows changes of its colors argument. */
@RunWith(AndroidJUnit4::class)
class RememberCubeViewStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun snapsWhenTheArgumentChangesAndKeepsAnimatedColorsOtherwise() {
        val solved: List<CubeColor?> = FaceletCube.SOLVED.toColors()
        var input by mutableStateOf(solved)
        var tick by mutableStateOf(0)
        val states = mutableListOf<CubeViewState>()
        compose.setContent {
            @Suppress("UNUSED_EXPRESSION") tick // recompose on demand
            states += rememberCubeViewState(input, initialYaw = 10f, initialPitch = 20f)
        }
        val state = states.first()
        assertEquals(10f, state.yaw, 0f)
        assertEquals(20f, state.pitch, 0f)

        // Colors committed by a turn survive recomposition with an unchanged (equal) argument.
        compose.runOnIdle { state.setPreview(null, 0f) }
        compose.runOnIdle {
            state.snapTo(FaceletCube.SOLVED.apply(Move.R1).toColors())
            input = FaceletCube.SOLVED.toColors() // a new but equal list
            tick++
        }
        compose.waitForIdle()
        assertEquals(FaceletCube.SOLVED.apply(Move.R1).toColors(), state.colors)

        // A different argument snaps the cube to it.
        val scrambled = FaceletCube.scrambled(Move.parseSequence("F2 U' L")).toColors()
        compose.runOnIdle { input = scrambled }
        compose.waitForIdle()
        assertEquals(scrambled, state.colors)
        states.forEach { assertSame(state, it) }
    }
}
