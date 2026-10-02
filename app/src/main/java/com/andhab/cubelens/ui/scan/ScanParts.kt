package com.andhab.cubelens.ui.scan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.glassSurface
import com.andhab.cubelens.ui.cube.FaceGrid
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import com.andhab.cubelens.ui.theme.DisplayFont

/**
 * Status line under the guide: what's happening or a gentle heads-up, and under it either a
 * warning about two captured faces that look the same or a reassurance that small slips get
 * checked on the next screen.
 *
 * @param showReassurance whether there is room for the reassurance (the warning is always shown).
 */
@Composable
internal fun ScanStatus(state: ScanUiState, modifier: Modifier = Modifier, showReassurance: Boolean = true) {
    val res = LocalResources.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val message = statusMessage(state, res)
        AnimatedContent(
            targetState = message,
            transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.94f)) togetherWith fadeOut(tween(120)) },
            contentAlignment = Alignment.Center,
            label = "scanStatus",
        ) { shown ->
            // Only heads-ups and the finish are announced; "Hold still…" comes and goes with every wobble.
            StatusChip(shown, announce = shown.tone == StatusTone.Warning || state.isComplete)
        }
        val lookAlikes = lookAlikeWarning(state, res)
        when {
            // The live heads-up comes first; the look-alike warning waits for it to clear.
            lookAlikes != null && state.hint == null -> Text(
                text = lookAlikes,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, lineBreak = LineBreak.Heading),
                color = Brand.Amber,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            // The reassurance gives way to a heads-up, which may need two lines on narrow screens.
            showReassurance && !state.isComplete && state.hint == null -> Text(
                text = res.getString(R.string.scan_reassurance),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
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
 * glowing, the rest empty slots (with only their center color on cubes with fixed centers, with
 * their number on cubes without). Tapping a face makes it the current one, to retake it. A face
 * the user is pointed to (to redo it) gets an amber ring.
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
    val res = LocalResources.current
    val pointedAt = state.hint.pointsAtThumbnail(state)
    val lookAlike = shownLookAlike(state)?.takeIf { state.hint == null }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (step in ScanStep.entries) {
            val capture = state.captures[step.ordinal].takeIf { step != landing }
            val current = step == state.currentStep && !state.isComplete
            val name = faceName(step, state, res)
            FaceThumbnail(
                colors = capture ?: placeholderColors(step, state.size),
                label = if (capture == null && !state.hasFixedCenters) step.number.toString() else null,
                captured = capture != null,
                current = current,
                attention = pointedAt == step || lookAlike?.contains(step) == true,
                enabled = !state.isComplete && !current,
                onClick = { onSelect(step) },
                description = res.getString(
                    when {
                        current -> R.string.scan_thumb_now
                        capture != null -> R.string.scan_thumb_done
                        else -> R.string.scan_thumb_missing
                    },
                    name,
                ),
                clickLabel = res.getString(if (capture != null) R.string.scan_thumb_action_again else R.string.scan_thumb_action_scan),
                modifier = Modifier.onGloballyPositioned { onPlaced(step, it.boundsInRoot()) },
            )
        }
    }
}

/**
 * What a face not scanned yet shows: its center color on a cube with fixed centers (that much is
 * known), nothing on a cube without.
 */
internal fun placeholderColors(step: ScanStep, size: Int): List<CubeColor?> {
    val center = if (size % 2 == 1) size * size / 2 else -1
    return List(size * size) { if (it == center) step.color else null }
}

@Composable
private fun FaceThumbnail(
    colors: List<CubeColor?>,
    label: String?,
    captured: Boolean,
    current: Boolean,
    attention: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String,
    clickLabel: String,
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
                val gap = 3.dp.toPx()
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
                onClickLabel = clickLabel,
                onClick = onClick,
            )
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        FaceGrid(colors = colors, active = current, modifier = Modifier.size(ThumbnailSize))
        if (label != null) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(Brand.Ink.copy(alpha = 0.9f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = ThumbnailNumber,
                    color = if (current) Brand.Gold else Brand.TextSecondary,
                )
            }
        }
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

/** The number on an empty face slot (cubes without fixed centers). */
private val ThumbnailNumber = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 13.sp)

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
    val res = LocalResources.current
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
                onClickLabel = res.getString(R.string.scan_shutter_action),
                onClick = onClick,
            )
            .semantics {
                contentDescription = res.getString(R.string.scan_cd_shutter)
                if (progress > 0f) stateDescription = res.getString(R.string.scan_shutter_auto)
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
    val res = LocalResources.current
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
            .semantics { contentDescription = res.getString(R.string.scan_cd_auto_capture) },
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
