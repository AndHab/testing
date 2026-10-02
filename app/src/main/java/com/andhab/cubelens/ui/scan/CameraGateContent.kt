package com.andhab.cubelens.ui.scan

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
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
import kotlin.random.Random

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
 * Stands in for the camera until it can be used: a slowly turning cube of the size being scanned
 * in a glowing viewfinder, a short explanation, one main action and a way out to manual entry.
 *
 * @param onPrimary [CameraGate.Rationale]: ask for the permission; [CameraGate.Denied]: open the
 *   app's settings; [CameraGate.Unavailable]: try starting the camera again.
 * @param size the size N of the cube about to be scanned.
 */
@Composable
fun CameraGateContent(
    gate: CameraGate,
    onPrimary: () -> Unit,
    onManualEntry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 3,
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
                ViewfinderCube(size, Modifier.sizeIn(maxWidth = 300.dp, maxHeight = 300.dp).fillMaxWidth().aspectRatio(1f))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Overline(stringResource(copy.overline))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                    Text(stringResource(copy.headline), style = MaterialTheme.typography.displaySmall, color = Brand.TextPrimary)
                    GradientText(stringResource(copy.headlineAccent), style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(copy.body), style = MaterialTheme.typography.bodyLarge, color = Brand.TextSecondary)
                if (gate == CameraGate.Rationale) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(R.string.scan_gate_private), icon = Icons.Rounded.Lock, color = Brand.Mint)
                        Pill(stringResource(R.string.scan_gate_quick), icon = Icons.Rounded.Timer)
                    }
                }
                Spacer(Modifier.height(28.dp))
                PrimaryButton(
                    text = stringResource(copy.action),
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
                    text = stringResource(R.string.scan_gate_manual),
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
    @StringRes val overline: Int,
    @StringRes val headline: Int,
    @StringRes val headlineAccent: Int,
    @StringRes val body: Int,
    @StringRes val action: Int,
) {
    companion object {
        fun of(gate: CameraGate): GateCopy = when (gate) {
            CameraGate.Rationale -> GateCopy(
                overline = R.string.scan_gate_rationale_overline,
                headline = R.string.scan_gate_rationale_headline,
                headlineAccent = R.string.scan_gate_rationale_accent,
                body = R.string.scan_gate_rationale_body,
                action = R.string.scan_gate_rationale_action,
            )
            CameraGate.Denied -> GateCopy(
                overline = R.string.scan_gate_denied_overline,
                headline = R.string.scan_gate_denied_headline,
                headlineAccent = R.string.scan_gate_denied_accent,
                body = R.string.scan_gate_denied_body,
                action = R.string.scan_gate_denied_action,
            )
            CameraGate.Unavailable -> GateCopy(
                overline = R.string.scan_gate_unavailable_overline,
                headline = R.string.scan_gate_unavailable_headline,
                headlineAccent = R.string.scan_gate_unavailable_accent,
                body = R.string.scan_gate_unavailable_body,
                action = R.string.scan_gate_unavailable_action,
            )
        }
    }
}

/**
 * A scrambled cube of size [n], turning slowly inside glowing viewfinder brackets: for a 3×3 the
 * user's own cube, for other sizes a fixed scramble, so the illustration looks lived-in.
 */
@Composable
private fun ViewfinderCube(n: Int, modifier: Modifier = Modifier) {
    val colors = remember(n) {
        if (n == 3) {
            FaceletCube.parse(HeroCube).toColors()
        } else {
            NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(HeroSeed))).toColors()
        }
    }
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

/** Seed of the fixed scramble shown for other sizes. */
private const val HeroSeed = 7L
