package com.andhab.cubelens.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.ui.components.AuroraBackground
import com.andhab.cubelens.ui.components.GradientText
import com.andhab.cubelens.ui.components.Overline
import com.andhab.cubelens.ui.components.Pill
import com.andhab.cubelens.ui.components.PrimaryButton
import com.andhab.cubelens.ui.components.TextAction
import com.andhab.cubelens.ui.components.TopBar
import com.andhab.cubelens.ui.cube.Cube3D
import com.andhab.cubelens.ui.cube.rememberCubeViewState
import com.andhab.cubelens.ui.theme.Brand

/** Why the camera isn't showing yet. */
enum class CameraGate {
    /** Permission not granted yet: explain and ask. */
    Rationale,

    /** Permission denied for good: only Settings can turn it back on. */
    Denied,

    /** Permission granted but the camera could not be started. */
    Unavailable,
}

/**
 * Stands in for the camera until it can be used: a slowly turning cube in a glowing viewfinder,
 * a short explanation, one main action and a way out to manual entry.
 *
 * @param onPrimary [CameraGate.Rationale]: ask for the permission; [CameraGate.Denied]: open the
 *   app's settings; [CameraGate.Unavailable]: try starting the camera again.
 */
@Composable
fun CameraGateContent(
    gate: CameraGate,
    onPrimary: () -> Unit,
    onManualEntry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = GateCopy.of(gate)
    AuroraBackground(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopBar(onBack = onBack)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                ViewfinderCube(Modifier.sizeIn(maxWidth = 300.dp, maxHeight = 300.dp).fillMaxWidth().aspectRatio(1f))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Overline(copy.overline)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                    Text(copy.headline, style = MaterialTheme.typography.displaySmall, color = Brand.TextPrimary)
                    GradientText(copy.headlineAccent, style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(12.dp))
                Text(copy.body, style = MaterialTheme.typography.bodyLarge, color = Brand.TextSecondary)
                if (gate == CameraGate.Rationale) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Stays on your phone", icon = Icons.Rounded.Lock, color = Brand.Mint)
                        Pill("About a minute", icon = Icons.Rounded.Timer)
                    }
                }
                Spacer(Modifier.height(28.dp))
                PrimaryButton(
                    text = copy.action,
                    onClick = onPrimary,
                    icon = when (gate) {
                        CameraGate.Rationale -> Icons.Rounded.CameraAlt
                        CameraGate.Denied -> Icons.Rounded.Settings
                        CameraGate.Unavailable -> Icons.Rounded.Refresh
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextAction(
                    text = "Enter colors manually instead",
                    onClick = onManualEntry,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The words for one [CameraGate]. The headline's second line is painted in the sunset. */
private class GateCopy(
    val overline: String,
    val headline: String,
    val headlineAccent: String,
    val body: String,
    val action: String,
) {
    companion object {
        fun of(gate: CameraGate): GateCopy = when (gate) {
            CameraGate.Rationale -> GateCopy(
                overline = "Camera",
                headline = "Show me",
                headlineAccent = "your cube.",
                body = "CubeLens reads your cube's colors with the camera. Nothing is saved or uploaded.",
                action = "Allow camera",
            )
            CameraGate.Denied -> GateCopy(
                overline = "One quick setting",
                headline = "Camera access",
                headlineAccent = "is turned off.",
                body = "Open Settings, tap Permissions and allow the camera. CubeLens only reads " +
                    "your cube's colors. Nothing is saved or uploaded.",
                action = "Open settings",
            )
            CameraGate.Unavailable -> GateCopy(
                overline = "Camera",
                headline = "The camera",
                headlineAccent = "didn't start.",
                body = "Another app may be using it. Try again, or type your cube's colors in.",
                action = "Try again",
            )
        }
    }
}

/** The user's own scrambled cube, turning slowly inside glowing viewfinder brackets. */
@Composable
private fun ViewfinderCube(modifier: Modifier = Modifier) {
    val colors = remember { FaceletCube.parse(HeroCube).toColors() }
    val cube = rememberCubeViewState(colors, initialYaw = -32f, initialPitch = 26f)
    Box(
        modifier.drawWithCache {
            val inset = size.width * 0.04f
            val frame = Rect(Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset))
            val brush = Brush.linearGradient(Brand.SunsetColors, start = frame.topLeft, end = frame.bottomRight)
            onDrawBehind { drawGuideBrackets(frame, lock = 0.05f, brush = brush, strokeWidth = 3.5.dp) }
        },
        contentAlignment = Alignment.Center,
    ) {
        Cube3D(cube, Modifier.fillMaxSize().padding(36.dp), interactive = true, autoRotate = true)
    }
}

/** A real scramble (the cube the photo fixtures show), so the illustration looks lived-in. */
private const val HeroCube = "DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR"
