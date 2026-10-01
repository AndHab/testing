package com.andhab.cubelens

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.andhab.cubelens.ui.CubeLensApp
import com.andhab.cubelens.ui.theme.CubeLensTheme

/**
 * The single activity: shows the brand splash, draws edge to edge behind transparent system bars
 * with light icons (the UI is always dark), and hosts [CubeLensApp].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            CubeLensTheme {
                CubeLensApp()
            }
        }
    }
}
