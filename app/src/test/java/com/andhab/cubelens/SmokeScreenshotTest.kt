package com.andhab.cubelens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SmokeScreenshotTest {
    @Test
    fun rendersCompose() {
        captureRoboImage("build/outputs/roborazzi/smoke.png") {
            Box(Modifier.size(200.dp).background(Color(0xFF0B0B12))) {
                Text("Hello cube", color = Color(0xFFFF3D7F))
            }
        }
    }
}
