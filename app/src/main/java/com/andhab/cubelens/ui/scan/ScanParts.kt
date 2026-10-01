package com.andhab.cubelens.ui.scan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.ui.components.GlassCard
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.drawSticker
import com.andhab.cubelens.ui.components.glassSurface
import com.andhab.cubelens.ui.cube.Cube3D
import com.andhab.cubelens.ui.cube.FaceGrid
import com.andhab.cubelens.ui.cube.rememberCubeViewState
import com.andhab.cubelens.ui.cube.viewAnglesFor
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import com.andhab.cubelens.ui.theme.CubePalette
import com.andhab.cubelens.ui.theme.DisplayFont

/**
 * What to do now: a small 3D cube held exactly as asked, the face to show, how to get there and
 * which color goes on top. Once everything is scanned it shows the scanned cube instead.
 *
 * @param followsPreviousStep whether the cube is still held as the previous step left it, so the
 *   step's short relative cue applies; otherwise the card says how to get there from any hold.
 * @param scannedCube the cube as scanned (see [scannedCubeColors]), shown once [complete].
 * @param compact a smaller cube and title, for short screens.
 */
@Composable
internal fun InstructionCard(
    step: ScanStep,
    complete: Boolean,
    scannedCube: List<CubeColor?>?,
    modifier: Modifier = Modifier,
    followsPreviousStep: Boolean = true,
    compact: Boolean = false,
) {
    GlassCard(modifier, contentPadding = PaddingValues(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeldCube(
                step = step,
                scannedCube = if (complete) scannedCube else null,
                modifier = Modifier.size(if (compact) 80.dp else 104.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimatedContent(
                    targetState = when {
                        complete -> null
                        followsPreviousStep -> Instruction(step, step.cue)
                        else -> Instruction(step, step.anywhereCue)
                    },
                    transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                    label = "instruction",
                ) { shown ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = shown?.step?.title ?: "That's all six!",
                            style = if (compact) InstructionTitle.copy(fontSize = 17.sp, lineHeight = 21.sp) else InstructionTitle,
                            color = Brand.TextPrimary,
                        )
                        Text(
                            text = shown?.cue ?: "Putting your cube together…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.TextSecondary,
                        )
                        if (shown != null) {
                            HoldChip(shown.step.topColor, Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The instruction text for one step: which face, and how to get there from the current hold. */
private data class Instruction(val step: ScanStep, val cue: String)

private val InstructionTitle = TextStyle(
    fontFamily = DisplayFont,
    fontWeight = FontWeight.Bold,
    fontSize = 19.sp,
    lineHeight = 23.sp,
)

/** "▢ White on top": the color that belongs on top, as a sticker swatch and words. */
@Composable
private fun HoldChip(top: CubeColor, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 28.dp)
            .background(Color.White.copy(alpha = 0.07f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(CubePalette.Body, RoundedCornerShape(6.dp))
                .drawBehind {
                    val inset = 2.dp.toPx()
                    drawSticker(CubePalette.color(top), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), 0.26f)
                },
        )
        Text(
            text = "${top.displayName} on top",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Brand.TextPrimary,
        )
    }
}

/**
 * A solved cube held as [step] asks (its color facing the viewer, [ScanStep.topColor] on top),
 * seen from slightly above and to the right. No face is dimmed: the top color is half the instruction.
 * When the step changes, the cube swings in from where the previous step left it: a quarter turn
 * for the side faces, a tilt for the top and bottom faces. With [scannedCube] it shows those
 * colors, slowly turning, instead.
 */
@Composable
private fun HeldCube(step: ScanStep, scannedCube: List<CubeColor?>?, modifier: Modifier = Modifier) {
    val yaw = remember { viewAnglesFor(Face.F).first }
    val pitch = HeldCubePitch
    val colors = scannedCube ?: step.heldCubeColors
    val state = rememberCubeViewState(colors, initialYaw = yaw, initialPitch = pitch)
    val shownStep = remember { ShownStep(step) }
    LaunchedEffect(step) {
        val previous = shownStep.step
        if (previous == step) return@LaunchedEffect
        shownStep.step = step
        when (step.face) {
            Face.U, Face.D -> {
                state.yaw = yaw
                state.pitch = -60f
            }
            else -> {
                state.yaw = yaw + 90f
                state.pitch = pitch
            }
        }
        state.animateView(yaw, pitch)
    }
    Cube3D(state = state, modifier = modifier, interactive = false, autoRotate = scannedCube != null)
}

/** Tilt of the held cube: enough to read the top color clearly, front face still dominant. */
private const val HeldCubePitch = 27f

/** The step the held cube last animated to; plain field, read only inside effects. */
private class ShownStep(var step: ScanStep)

/**
 * The cube as scanned so far, assuming each face was held as instructed: every captured face's
 * nine colors, in place on its face. Faces not captured yet are unknown.
 */
internal fun scannedCubeColors(captures: List<List<CubeColor>?>): List<CubeColor?> {
    val colors = MutableList<CubeColor?>(Facelets.COUNT) { null }
    for (step in ScanStep.entries) {
        val face = captures[step.ordinal] ?: continue
        for (i in 0 until 9) colors[step.face.ordinal * 9 + i] = face[i]
    }
    return colors
}

/**
 * Status line under the guide: what's happening or a gentle heads-up, and a reassurance that
 * small slips get checked on the next screen.
 */
@Composable
internal fun ScanStatus(state: ScanUiState, modifier: Modifier = Modifier, showReassurance: Boolean = true) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val message = statusMessage(state)
        AnimatedContent(
            targetState = message,
            transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.94f)) togetherWith fadeOut(tween(120)) },
            contentAlignment = Alignment.Center,
            label = "scanStatus",
        ) { shown ->
            // Only heads-ups and the finish are announced; "Hold still…" comes and goes with every wobble.
            StatusChip(shown, announce = shown.tone == StatusTone.Warning || state.isComplete)
        }
        // The reassurance gives way to a heads-up, which may need two lines on narrow screens.
        if (showReassurance && !state.isComplete && state.hint == null) {
            Text(
                text = "Slightly off? We'll double-check it on the next screen.",
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One status message and how to show it. */
internal data class StatusMessage(val text: String, val tone: StatusTone)

internal enum class StatusTone { Neutral, Good, Warning }

/**
 * The face thumbnail this hint asks the user to tap (to redo it), if any: only when the face in
 * view is the right one but was already captured for another step.
 */
internal fun ScanHint?.pointsAtThumbnail(state: ScanUiState): ScanStep? =
    (this as? ScanHint.AlreadyScanned)?.takeIf { it.color == state.currentStep.color }?.step

private val CubeColor.lowerName: String get() = displayName.lowercase()

internal fun statusMessage(state: ScanUiState): StatusMessage {
    val hint = state.hint
    return when {
        state.isComplete -> StatusMessage("All six faces scanned", StatusTone.Good)
        hint is ScanHint.WrongFace -> StatusMessage(
            "Looks like the ${hint.seen.lowerName} face — show ${hint.expected.lowerName}, " +
                "${ScanStep.forColor(hint.expected).topColor.lowerName} on top",
            StatusTone.Warning,
        )
        // Usually the cube just hasn't been turned yet: say what to show next.
        hint is ScanHint.AlreadyScanned && hint.color != state.currentStep.color -> StatusMessage(
            "${hint.color.displayName}'s done — now show ${state.currentStep.color.lowerName}, " +
                "${state.currentStep.topColor.lowerName} on top",
            StatusTone.Warning,
        )
        // The right face, but it was already captured for another step (by hand, at the wrong step).
        hint is ScanHint.AlreadyScanned -> StatusMessage(
            "${hint.color.displayName} went into ${hint.step.color.lowerName}'s spot. " +
                "Tap ${hint.step.color.lowerName} below to redo it.",
            StatusTone.Warning,
        )
        state.liveColors == null -> StatusMessage("Starting the camera…", StatusTone.Neutral)
        state.centerMatches && state.autoCapture -> StatusMessage("Hold still…", StatusTone.Good)
        state.centerMatches && !state.autoCapture -> StatusMessage("Looks right. Tap the shutter.", StatusTone.Good)
        else -> StatusMessage("Fit the face inside the frame", StatusTone.Neutral)
    }
}

@Composable
private fun StatusChip(message: StatusMessage, announce: Boolean) {
    val accent = when (message.tone) {
        StatusTone.Neutral -> Brand.TextPrimary
        StatusTone.Good -> Brand.Mint
        StatusTone.Warning -> Brand.Amber
    }
    val background = when (message.tone) {
        StatusTone.Neutral -> Brand.Glass
        else -> lerpOver(accent)
    }
    Row(
        modifier = Modifier
            .semantics(mergeDescendants = true) { if (announce) liveRegion = LiveRegionMode.Polite }
            .heightIn(min = 36.dp)
            .glassSurface(
                shape = CircleShape,
                fill = background,
                border = Brush.verticalGradient(listOf(accent.copy(alpha = 0.36f), accent.copy(alpha = 0.12f))),
            )
            .padding(start = 12.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (message.tone) {
            StatusTone.Warning -> Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            StatusTone.Good -> Box(
                Modifier
                    .size(8.dp)
                    .drawBehind { drawSoftGlow(accent, 0.6f, center, size.width * 1.4f) }
                    .background(accent, CircleShape),
            )
            StatusTone.Neutral -> Box(Modifier.size(8.dp).background(Brand.TextTertiary, CircleShape))
        }
        Text(
            text = message.text,
            // Two-line heads-ups wrap evenly instead of leaving a word on its own.
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, lineBreak = LineBreak.Heading),
            color = if (message.tone == StatusTone.Neutral) Brand.TextPrimary else accent,
            textAlign = TextAlign.Center,
        )
    }
}

/** A dark glass fill tinted with [accent]. */
private fun lerpOver(accent: Color): Color = Color(
    red = Brand.Surface.red * 0.84f + accent.red * 0.16f,
    green = Brand.Surface.green * 0.84f + accent.green * 0.16f,
    blue = Brand.Surface.blue * 0.84f + accent.blue * 0.16f,
    alpha = 0.86f,
)

/**
 * The six faces as thumbnails: captured faces in their colors with a mint check, the current one
 * glowing, the rest showing only their center color. Tapping a face makes it the current one, to
 * retake it.
 *
 * @param landing a step whose capture is still flying in; shown as not captured until it lands.
 * @param onPlaced reports each thumbnail's bounds in root coordinates (for the capture animation).
 */
@Composable
internal fun FaceProgressRow(
    state: ScanUiState,
    landing: ScanStep?,
    onSelect: (ScanStep) -> Unit,
    onPlaced: (ScanStep, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (step in ScanStep.entries) {
            val capture = state.captures[step.ordinal].takeIf { step != landing }
            val current = step == state.currentStep && !state.isComplete
            FaceThumbnail(
                step = step,
                colors = capture ?: step.placeholderColors,
                captured = capture != null,
                current = current,
                attention = state.hint.pointsAtThumbnail(state) == step,
                enabled = !state.isComplete && !current,
                onClick = { onSelect(step) },
                modifier = Modifier.onGloballyPositioned { onPlaced(step, it.boundsInRoot()) },
            )
        }
    }
}

@Composable
private fun FaceThumbnail(
    step: ScanStep,
    colors: List<CubeColor?>,
    captured: Boolean,
    current: Boolean,
    attention: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ring by animateFloatAsState(if (attention) 1f else 0f, tween(220), label = "thumbAttention")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.9f
            current -> 1.08f
            else -> 1f
        },
        animationSpec = CubeLensMotion.press(),
        label = "thumbScale",
    )
    val description = buildString {
        append(step.color.displayName).append(" face")
        append(
            when {
                current -> ", scanning now"
                captured -> ", scanned"
                else -> ", not scanned yet"
            },
        )
    }
    Box(
        modifier = modifier
            .size(ThumbnailSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                // Amber ring around a face the user is pointed to ("Tap red below to redo it").
                if (ring <= 0f) return@drawBehind
                val gap = 4.dp.toPx()
                val width = 2.dp.toPx()
                drawSoftGlow(Brand.Amber, alpha = 0.35f * ring, center = center, radiusX = size.width * 0.9f)
                drawRoundRect(
                    color = Brand.Amber,
                    topLeft = Offset(-gap, -gap),
                    size = Size(size.width + 2 * gap, size.height + 2 * gap),
                    cornerRadius = CornerRadius(size.width * 0.24f + gap),
                    alpha = ring,
                    style = Stroke(width),
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (captured) "Scan again" else "Scan this face",
                onClick = onClick,
            )
            .semantics { contentDescription = description },
    ) {
        FaceGrid(colors = colors, active = current, modifier = Modifier.size(ThumbnailSize))
        if (captured && !current) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp, y = (-5).dp)
                    .size(17.dp)
                    .background(Brand.Ink, CircleShape)
                    .padding(1.5.dp)
                    .background(Brand.Mint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Brand.OnAccent, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** At least the minimum touch target: tapping a thumbnail is the only way to redo a face. */
private val ThumbnailSize: Dp = 48.dp

/**
 * The shutter: a light glossy disc inside a sunset ring. While a correct face is held steady,
 * a brighter glowing arc sweeps around the ring ([progress], 0..1) until auto-capture fires. When
 * [complete], it turns mint with a check.
 */
@Composable
internal fun ShutterButton(
    progress: Float,
    complete: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 84.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.9f else 1f, CubeLensMotion.press(), label = "shutterScale")
    val sweep by animateFloatAsState(progress, tween(90), label = "shutterSweep")
    val done by animateFloatAsState(if (complete) 1f else 0f, tween(360), label = "shutterDone")
    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = "Capture this face",
                onClick = onClick,
            )
            .semantics {
                contentDescription = "Shutter"
                if (progress > 0f) stateDescription = "Hold still, capturing automatically"
            }
            .drawWithCache {
                val center = Offset(size.width / 2f, size.height / 2f)
                val ringWidth = 2.5.dp.toPx()
                val arcWidth = 6.dp.toPx()
                val ringRadius = size.minDimension / 2f - arcWidth
                val sunsetSweep = Brush.sweepGradient(
                    listOf(Brand.Magenta, Brand.Tangerine, Brand.Gold, Brand.Tangerine, Brand.Magenta),
                    center = center,
                )
                val discRadius = ringRadius - 8.dp.toPx()
                val disc = Brush.verticalGradient(
                    listOf(Color(0xFFFFFFFF), Color(0xFFEDE7F5)),
                    startY = center.y - discRadius,
                    endY = center.y + discRadius,
                )
                val sheen = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.9f),
                    1f to Color.White.copy(alpha = 0f),
                    startY = center.y - discRadius,
                    endY = center.y,
                )
                onDrawBehind {
                    drawSoftGlow(Brand.Magenta, alpha = 0.22f + 0.25f * sweep, center = center + Offset(0f, 6.dp.toPx()), radiusX = size.width * 0.62f)
                    // Base ring: the sunset, quiet until the steadiness arc lights it up.
                    drawCircle(Color.White, radius = ringRadius, style = Stroke(ringWidth), alpha = 0.16f * (1f - done))
                    drawCircle(sunsetSweep, radius = ringRadius, style = Stroke(ringWidth), alpha = 0.55f * (1f - done))
                    // Steadiness arc, with a neon glow.
                    if (sweep > 0.001f && done < 1f) {
                        rotate(-90f) {
                            for ((width, alpha) in ShutterGlow) {
                                drawArc(
                                    brush = sunsetSweep,
                                    startAngle = 0f,
                                    sweepAngle = 360f * sweep,
                                    useCenter = false,
                                    topLeft = center - Offset(ringRadius, ringRadius),
                                    size = Size(2 * ringRadius, 2 * ringRadius),
                                    alpha = alpha * (1f - done),
                                    style = Stroke(arcWidth * width, cap = StrokeCap.Round),
                                )
                            }
                        }
                    }
                    if (done > 0f) {
                        drawCircle(Brand.Mint, radius = ringRadius, style = Stroke(ringWidth), alpha = done)
                    }
                    // The disc.
                    drawCircle(disc, radius = discRadius, alpha = if (enabled || complete) 1f else 0.5f)
                    drawCircle(sheen, radius = discRadius * 0.92f, center = center, alpha = 0.35f)
                    if (done > 0f) drawCircle(Brand.Mint, radius = discRadius, alpha = done)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (done > 0f) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = Brand.OnAccent,
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer { alpha = done },
            )
        }
    }
}

/** Glow passes under the steadiness arc: (stroke width multiple, opacity). */
private val ShutterGlow = listOf(3f to 0.1f, 1.9f to 0.2f, 1f to 1f)

/**
 * Round control with a small label underneath, flanking the shutter.
 */
@Composable
internal fun LabeledControl(label: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Column(
        modifier = modifier.width(84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        control()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Brand.TextSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Auto-capture switch: a glass disc whose rim and icon light up in the sunset while on, quieter
 * than the shutter it sits next to.
 */
@Composable
internal fun AutoCaptureToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, CubeLensMotion.press(), label = "autoScale")
    val on by animateFloatAsState(if (checked) 1f else 0f, CubeLensMotion.select(), label = "autoOn")
    Box(
        modifier = modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .glassSurface(shape = CircleShape, fill = if (pressed) Brand.GlassHigh else Brand.Glass)
            .drawBehind {
                if (on <= 0f) return@drawBehind
                // A warm glow inside the disc and a sunset rim.
                drawSoftGlow(Brand.Magenta, alpha = 0.22f * on, center = Offset(size.width * 0.3f, size.height * 0.7f), radiusX = size.width * 0.7f)
                drawSoftGlow(Brand.Tangerine, alpha = 0.18f * on, center = Offset(size.width * 0.75f, size.height * 0.3f), radiusX = size.width * 0.6f)
                val rim = 2.dp.toPx()
                drawCircle(
                    brush = Brand.SunsetBrush,
                    radius = size.minDimension / 2f - rim / 2f,
                    alpha = on,
                    style = Stroke(rim),
                )
            }
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { contentDescription = "Auto-capture" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoMode,
            contentDescription = null,
            tint = if (checked) Brand.Gold else Brand.TextSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
}
