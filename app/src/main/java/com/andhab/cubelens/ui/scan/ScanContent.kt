package com.andhab.cubelens.ui.scan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.andhab.cubelens.camera.GuideGeometry
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.components.CircleIconButton
import com.andhab.cubelens.ui.components.TopBar
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.cube.FaceGrid
import com.andhab.cubelens.ui.theme.Brand
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The camera scanning screen, without the camera: everything is drawn from [state] over the
 * [preview], which fills the screen behind the controls. Screenshot tests pass a still image as
 * the preview; the real screen passes the live camera.
 *
 * Layout, top to bottom: the bar (back, "Face 2 of 6" or "Redo green", flashlight), the
 * instruction card, the guide window with live colors and the status line, the six face
 * thumbnails, and the shutter flanked by the auto-capture switch and manual entry. A capture
 * flashes the screen, gives a haptic tick and flies the captured face into its thumbnail.
 *
 * @param onCapture the shutter was pressed.
 * @param onAutoCaptureChange the auto-capture switch was flipped.
 * @param onTorchChange the flashlight button was pressed (shown only if [ScanUiState.torchAvailable]).
 * @param onSelectStep a face thumbnail was tapped (to retake it).
 * @param onManualEntry the user prefers to type the colors in.
 * @param onGuideChange reports where the guide window is, in pixels of the preview (which fills this
 *   composable); the camera analysis samples exactly this square.
 */
@Composable
fun ScanContent(
    state: ScanUiState,
    preview: @Composable () -> Unit,
    onBack: () -> Unit,
    onCapture: () -> Unit,
    onAutoCaptureChange: (Boolean) -> Unit,
    onTorchChange: (Boolean) -> Unit,
    onSelectStep: (ScanStep) -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
    onGuideChange: (GuideGeometry) -> Unit = {},
) {
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var guideInRoot by remember { mutableStateOf<Rect?>(null) }
    val thumbnailsInRoot = remember { mutableStateMapOf<ScanStep, Rect>() }
    val guide = guideInRoot?.translate(-rootOrigin)

    val currentOnGuideChange by rememberUpdatedState(onGuideChange)
    LaunchedEffect(guide, rootSize) {
        if (guide != null && rootSize.width > 0 && rootSize.height > 0 && guide.width > 0f) {
            currentOnGuideChange(GuideGeometry(rootSize.width, rootSize.height, guide.left, guide.top, guide.width))
        }
    }

    val capture = rememberCaptureEffects(state)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brand.Ink)
            .onGloballyPositioned {
                rootOrigin = it.positionInRoot()
                rootSize = it.size
            },
    ) {
        // Short screens trade the decorative extras for a bigger guide.
        val compact = maxHeight < CompactHeight
        Box(Modifier.fillMaxSize()) { preview() }
        ScanScrim(guide = guide, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopBar(
                title = scanTitle(state),
                onBack = onBack,
                actions = {
                    if (state.torchAvailable) {
                        CircleIconButton(
                            icon = if (state.torchOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                            contentDescription = if (state.torchOn) "Turn flashlight off" else "Turn flashlight on",
                            onClick = { onTorchChange(!state.torchOn) },
                            size = 44.dp,
                            highlighted = state.torchOn,
                        )
                    }
                },
            )
            InstructionCard(
                step = state.currentStep,
                complete = state.isComplete,
                scannedCube = remember(state.captures) { scannedCubeColors(state.captures) },
                followsPreviousStep = state.followsPreviousStep,
                compact = compact,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
            )

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val statusHeight = if (compact) 56.dp else 74.dp
                val gap = if (compact) 10.dp else 16.dp
                val fixed = statusHeight + gap + BracketOverhang * 2
                val guideSize = min(maxWidth * GuideWidthFraction, maxHeight - fixed).coerceAtLeast(MinGuideSize)
                val free = (maxHeight - guideSize - fixed).coerceAtLeast(0.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = BracketOverhang + free * 0.45f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ScanGuide(
                        liveColors = state.liveColors,
                        lockProgress = if (state.isComplete) 1f else capture.lockBoost.coerceAtLeast(state.captureProgress),
                        complete = state.isComplete,
                        flagCenter = state.hint != null,
                        modifier = Modifier
                            .size(guideSize)
                            .onGloballyPositioned { guideInRoot = it.boundsInRoot() },
                    )
                    Spacer(Modifier.height(gap))
                    ScanStatus(
                        state = state,
                        showReassurance = !compact,
                        modifier = Modifier
                            .height(statusHeight)
                            .padding(horizontal = 20.dp),
                    )
                }
            }

            val caption = progressCaption(state)
            if (!compact) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clearAndSetSemantics {},
                )
            }
            FaceProgressRow(
                state = state,
                landing = capture.landing,
                onSelect = onSelectStep,
                onPlaced = { step, bounds -> thumbnailsInRoot[step] = bounds },
                modifier = Modifier
                    .fillMaxWidth()
                    // Announces each capture ("2 of 6 scanned"), in compact layouts too.
                    .semantics {
                        contentDescription = caption
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            Spacer(Modifier.height(if (compact) 12.dp else 22.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabeledControl(label = if (state.autoCapture) "Auto on" else "Auto off") {
                    AutoCaptureToggle(
                        checked = state.autoCapture,
                        onCheckedChange = onAutoCaptureChange,
                        enabled = !state.isComplete,
                    )
                }
                ShutterButton(
                    progress = if (state.autoCapture) state.captureProgress else 0f,
                    complete = state.isComplete,
                    enabled = state.liveColors != null && !state.isComplete,
                    onClick = onCapture,
                    diameter = if (compact) 72.dp else 84.dp,
                )
                LabeledControl(label = "Type colors") {
                    CircleIconButton(
                        icon = Icons.Rounded.GridView,
                        contentDescription = "Enter colors manually",
                        onClick = onManualEntry,
                        size = 52.dp,
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        }

        CaptureFlight(
            effects = capture,
            guide = guide,
            thumbnail = capture.landing?.let { step -> thumbnailsInRoot[step]?.translate(-rootOrigin) },
        )
        if (capture.flash.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = capture.flash.value }
                    .background(Color.White),
            )
        }
    }
}

/** The bar's title: which face this is, or that a face is being redone. */
internal fun scanTitle(state: ScanUiState): String = when {
    state.isComplete -> "All done"
    state.isRetake -> "Redo ${state.currentStep.color.displayName.lowercase()}"
    else -> "Face ${(state.capturedCount + 1).coerceAtMost(ScanStep.entries.size)} of ${ScanStep.entries.size}"
}

/** The line above the face thumbnails: progress, and how to retake a face. */
private fun progressCaption(state: ScanUiState): String = when {
    state.isComplete -> "Nice scanning!"
    state.capturedCount == 0 -> "Six faces to go"
    else -> "${state.capturedCount} of ${ScanStep.entries.size} scanned · tap one to redo it"
}

/** The guide's side as a fraction of the screen width (it shrinks on short screens). */
private const val GuideWidthFraction = 0.78f
private val MinGuideSize = 150.dp

/** Screens shorter than this get the compact layout. */
private val CompactHeight = 720.dp

/** How far the guide's brackets and their glow reach outside the guide window. */
private val BracketOverhang = 12.dp

/** Running capture feedback: the flash, the flying face and a brief lock-on of the brackets. */
private class CaptureEffects {
    val flash = Animatable(0f)
    val flight = Animatable(0f)
    var landing: ScanStep? by mutableStateOf(null)
    var flightColors: List<CubeColor>? by mutableStateOf(null)
    var lockBoost: Float by mutableStateOf(0f)
}

/**
 * Plays the capture feedback whenever [ScanUiState.captureCount] goes up: a white flash, a haptic
 * tick, and the captured face flying from the guide into its thumbnail. Nothing plays for the
 * state the screen first appears with.
 */
@Composable
private fun rememberCaptureEffects(state: ScanUiState): CaptureEffects {
    val effects = remember { CaptureEffects() }
    val haptics = LocalHapticFeedback.current
    val seenCount = remember { CaptureCounter(state.captureCount) }
    LaunchedEffect(state.captureCount) {
        if (state.captureCount <= seenCount.count) return@LaunchedEffect
        seenCount.count = state.captureCount
        val step = state.lastCaptured ?: return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        effects.flightColors = state.captures[step.ordinal]
        effects.landing = step
        effects.lockBoost = 1f
        coroutineScope {
            launch {
                effects.flash.snapTo(0.55f)
                effects.flash.animateTo(0f, tween(360))
            }
            effects.flight.snapTo(0f)
            effects.flight.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        }
        effects.landing = null
        effects.flightColors = null
        effects.lockBoost = 0f
    }
    return effects
}

/** The capture count the effects last reacted to; plain field, read only inside effects. */
private class CaptureCounter(var count: Int)

/** The captured face, shrinking from the guide window into its thumbnail. */
@Composable
private fun CaptureFlight(effects: CaptureEffects, guide: Rect?, thumbnail: Rect?) {
    val colors = effects.flightColors ?: return
    if (guide == null || thumbnail == null) return
    val density = LocalDensity.current
    val sizeDp = with(density) { guide.width.toDp() }
    Box(
        Modifier
            .offset { IntOffset(guide.left.roundToInt(), guide.top.roundToInt()) }
            .size(sizeDp)
            .graphicsLayer {
                val t = effects.flight.value
                val current = lerp(guide, thumbnail, t)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = current.left - guide.left
                translationY = current.top - guide.top
                scaleX = current.width / guide.width
                scaleY = current.height / guide.height
                alpha = if (t < 0.85f) 1f else (1f - t) / 0.15f
            },
    ) {
        FaceGrid(
            colors = colors,
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawSoftGlow(Brand.Mint, alpha = 0.3f, center = center, radiusX = size.width * 0.75f) },
        )
    }
}

/** Linear interpolation between two rectangles. */
private fun lerp(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    topLeft = lerp(start.topLeft, stop.topLeft, fraction),
    bottomRight = lerp(start.bottomRight, stop.bottomRight, fraction),
)
