package com.andhab.cubelens.ui.scan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.ui.components.GlassCard
import com.andhab.cubelens.ui.components.drawSticker
import com.andhab.cubelens.ui.cube.Cube3D
import com.andhab.cubelens.ui.cube.FaceGrid
import com.andhab.cubelens.ui.cube.rememberCubeViewState
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.DisplayFont
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * What to do now: a small 3D cube held exactly as asked with an arrow showing the move, which face
 * to show, how to get there and how to hold it. Once everything is scanned it shows the scanned cube
 * instead.
 *
 * Cubes with fixed centers (odd sizes) are guided by color ("Green center facing you", "White on
 * top") and the cube shown is a solved one in the scan's colors. Cubes without (even sizes), and
 * those whose colors turn out to be arranged differently ([byColor] false), are guided by position
 * ("Turn the cube left", "First face at the bottom"), and the cube shown is the one being scanned:
 * the faces captured so far in place, the rest blank.
 *
 * @param size the cube's size N.
 * @param captures the captured faces, as in [ScanUiState.captures].
 * @param byColor whether the steps go by color ([ScanUiState.guidedByColor]).
 * @param followsPreviousStep whether the cube is still held as the previous step left it, so the
 *   step's short relative cue and its arrow apply; otherwise the card says how to get there from
 *   any hold.
 * @param complete all faces are scanned: the card shows the scanned cube.
 * @param compact a smaller cube and title, for short screens.
 */
@Composable
internal fun InstructionCard(
    step: ScanStep,
    size: Int,
    captures: List<List<CubeColor>?>,
    followsPreviousStep: Boolean,
    complete: Boolean,
    modifier: Modifier = Modifier,
    byColor: Boolean = size % 2 == 1,
    compact: Boolean = false,
) {
    val res = LocalResources.current
    val scanned = remember(captures, size) { scannedCubeColors(captures, size) }
    val instruction = remember(step, size, followsPreviousStep, complete, byColor, res) {
        if (complete) null else instructionFor(step, size, followsPreviousStep, res, byColor)
    }
    GlassCard(modifier, contentPadding = PaddingValues(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeldCube(
                step = step,
                size = size,
                byColor = byColor,
                scanned = scanned,
                complete = complete,
                cue = if (followsPreviousStep && !complete) MoveCue.of(step) else MoveCue.None,
                modifier = Modifier.size(if (compact) 80.dp else 104.dp),
            )
            Spacer(Modifier.width(6.dp))
            AnimatedContent(
                targetState = instruction,
                transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                label = "instruction",
            ) { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = shown?.title ?: res.getString(R.string.scan_card_done_title),
                        style = if (compact) InstructionTitle.copy(fontSize = 17.sp, lineHeight = 21.sp) else InstructionTitle,
                        color = Brand.TextPrimary,
                    )
                    Text(
                        text = shown?.cue ?: res.getString(R.string.scan_card_done_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.TextSecondary,
                    )
                    if (shown != null) {
                        HoldChip(shown.hold, firstFace = captures[ScanStep.Front.ordinal], modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

private val InstructionTitle = TextStyle(
    fontFamily = DisplayFont,
    fontWeight = FontWeight.Bold,
    fontSize = 19.sp,
    lineHeight = 23.sp,
)

/**
 * How to hold the cube, as a chip: "▢ White on top" with that color's sticker; "↑ Same side on
 * top"; or "▦ First face at the bottom" with a thumbnail of the first face scanned ([firstFace]),
 * so the user recognizes it by its pattern.
 */
@Composable
private fun HoldChip(hold: Hold, firstFace: List<CubeColor>?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 28.dp)
            .background(Brand.TextPrimary.copy(alpha = 0.07f), CircleShape)
            .border(1.dp, Brand.TextPrimary.copy(alpha = 0.12f), CircleShape)
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when (hold) {
            is Hold.TopColor -> {
                val color = LocalStickerPalette.current.color(hold.color)
                Box(
                    Modifier
                        .size(18.dp)
                        .background(Brand.Ink, RoundedCornerShape(6.dp))
                        .drawBehind {
                            val inset = 2.dp.toPx()
                            drawSticker(color, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), 0.26f)
                        },
                )
            }
            is Hold.FirstFace -> if (firstFace != null) {
                FaceGrid(firstFace, Modifier.size(20.dp))
            } else {
                HoldIcon(if (hold.onTop) Icons.Rounded.VerticalAlignTop else Icons.Rounded.VerticalAlignBottom)
            }
            is Hold.SameTop -> HoldIcon(Icons.Rounded.North)
        }
        Text(
            text = hold.text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Brand.TextPrimary,
        )
    }
}

@Composable
private fun HoldIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(18.dp)
            .background(Brand.Gold.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Brand.Gold, modifier = Modifier.size(13.dp))
    }
}

/**
 * The cube held as [step] asks, seen from where the hold reads best, with an arrow for the move
 * that gets there ([cue]). When the step changes, the cube swings in from where the previous step
 * left it: a quarter turn for the side faces, a tilt for the top and bottom faces. Once [complete]
 * it shows the [scanned] cube, slowly turning.
 *
 * @param byColor whether the steps go by color: a solved cube in the standard colors is shown, held
 *   as asked, instead of the [scanned] cube.
 * @param scanned the scanned cube so far (see [scannedCubeColors]), held as asked.
 */
@Composable
private fun HeldCube(
    step: ScanStep,
    size: Int,
    byColor: Boolean,
    scanned: List<CubeColor?>,
    complete: Boolean,
    cue: MoveCue,
    modifier: Modifier = Modifier,
) {
    val (yaw, pitch) = restView(step, byColor)
    val colors = when {
        complete -> scanned
        byColor -> remember(step, size) { step.heldSolvedCube(size) }
        else -> remember(step, size, scanned) { step.held(scanned, size) }
    }
    val state = rememberCubeViewState(colors, initialYaw = yaw, initialPitch = pitch)
    val shown = remember { ShownStep(step) }
    val arrow = remember { Animatable(1f) }
    LaunchedEffect(step) {
        val previous = shown.step
        if (previous == step) return@LaunchedEffect
        shown.step = step
        arrow.snapTo(0f)
        launch { arrow.animateTo(1f, tween(ArrowDrawMillis, delayMillis = ArrowDelayMillis, easing = FastOutSlowInEasing)) }
        when (step.face) {
            Face.U, Face.D -> {
                state.yaw = yaw
                state.pitch = SwingPitch
            }
            else -> {
                state.yaw = yaw + 90f
                state.pitch = pitch
            }
        }
        state.animateView(yaw, pitch)
    }
    Box(modifier) {
        Cube3D(
            state = state,
            modifier = Modifier.fillMaxSize(),
            interactive = false,
            autoRotate = complete,
            // A blank cube without center colors to go by: a frame marks the face to show. Once
            // faces are scanned, they show where things are, so they stay at full strength.
            focusFace = if (byColor || complete || scanned.any { it != null }) null else Face.F,
        )
        if (cue != MoveCue.None) {
            CueArrow(cue, progress = { arrow.value }, modifier = Modifier.fillMaxSize().clearAndSetSemantics {})
        }
    }
}

/**
 * The view of the held cube at rest, as (yaw, pitch). Cubes guided by color are seen from above
 * and to the right, so the front color and the top color both read. Cubes guided by position are seen so that
 * the face scanned before is in view: the side faces from the left (the face just scanned turned
 * away to the left), the top face from below (the first face at the bottom), the bottom face from
 * above (the first face on top).
 */
private fun restView(step: ScanStep, byColor: Boolean): Pair<Float, Float> = when {
    byColor -> CenterViewYaw to HeldCubePitch
    step == ScanStep.Top -> SideViewYaw to -HeldCubePitch
    step == ScanStep.Bottom -> CenterViewYaw to HeldCubePitch
    else -> SideViewYaw to HeldCubePitch
}

/** Tilt of the held cube: enough to read the top color clearly, front face still dominant. */
private const val HeldCubePitch = 27f

/** Yaw that shows the front and right faces (as on the solve screen's front preset). */
private const val CenterViewYaw = -24f

/** Yaw that shows the front and left faces. */
private const val SideViewYaw = 24f

/** Where a top or bottom face swings in from: looking up from below, where the previous face went. */
private const val SwingPitch = -80f

private const val ArrowDrawMillis = 650
private const val ArrowDelayMillis = 280

/** The step the held cube last animated to; plain field, read only inside effects. */
private class ShownStep(var step: ScanStep)

/** The move that gets the cube from the previous step's hold to this one's, drawn as an arrow. */
internal enum class MoveCue {
    /** Nothing to show: the first step, or the cube is no longer held as the previous step left it. */
    None,

    /** A quarter turn to the left about the vertical axis (the right face comes to the front). */
    TurnLeft,

    /** The top tipped toward the viewer (the top face comes to the front). */
    TipToward,

    /** Flipped over, top to bottom (the bottom face comes to the front). */
    Flip,
    ;

    companion object {
        /** The cue for reaching [step] from the step before it. */
        fun of(step: ScanStep): MoveCue = when (step) {
            ScanStep.Front -> None
            ScanStep.Right, ScanStep.Back, ScanStep.Left -> TurnLeft
            ScanStep.Top -> TipToward
            ScanStep.Bottom -> Flip
        }
    }
}

/**
 * A glowing sunset arrow around the held cube showing [cue], drawn on along its length as
 * [progress] goes from 0 to 1: a sweep around the cube's front from right to left for a turn, an
 * arc over its right side, from the top down toward the viewer, for a tip, and a longer one that
 * carries on under the cube for a flip.
 */
@Composable
private fun CueArrow(cue: MoveCue, progress: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val path = cuePath(cue, size)
            val measure = PathMeasure().apply { setPath(path, false) }
            val length = measure.length
            val brush = Brush.linearGradient(Brand.SunsetColors, start = Offset(size.width, 0f), end = Offset(0f, size.height))
            val core = 2.4.dp.toPx()
            val partial = Path()
            onDrawWithContent {
                drawContent()
                val t = progress().coerceIn(0f, 1f)
                if (t <= 0f) return@onDrawWithContent
                partial.reset()
                measure.getSegment(0f, length * t, partial, true)
                for ((width, alpha) in ArrowGlow) {
                    drawPath(partial, brush, alpha = alpha * t, style = Stroke(core * width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawArrowHead(measure, length * t, core, brush, t)
            }
        },
    )
}

/** Glow passes of the cue arrow: (stroke width multiple, opacity); the last one is the core. */
private val ArrowGlow = listOf(4.2f to 0.1f, 2.4f to 0.22f, 1f to 1f)

/** The cue arrow's path in a box of [size] (the held cube's bounds). */
private fun cuePath(cue: MoveCue, size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        when (cue) {
            MoveCue.None -> Unit
            // A flat ellipse around the cube's lower half, passing in front of it, right to left.
            MoveCue.TurnLeft -> arcTo(Rect(w * 0.1f, h * 0.54f, w * 0.9f, h * 0.9f), -6f, 188f, true)
            // Over the right side, from the top down toward the viewer.
            MoveCue.TipToward -> arcTo(Rect(w * 0.54f, h * 0.1f, w, h * 0.88f), -80f, 165f, true)
            // The same, carrying on under the cube: over and round to the other side.
            MoveCue.Flip -> arcTo(Rect(w * 0.08f, h * 0.08f, w * 0.98f, h * 0.94f), -78f, 230f, true)
        }
    }
}

/** A rounded chevron at [distance] along the arrow, pointing the way it travels. */
private fun DrawScope.drawArrowHead(measure: PathMeasure, distance: Float, core: Float, brush: Brush, alpha: Float) {
    if (distance <= 0f) return
    val tip = measure.getPosition(distance)
    val tangent = measure.getTangent(distance)
    val angle = atan2(tangent.y, tangent.x)
    val arm = core * 3.4f
    fun wing(side: Float): Offset {
        val a = angle + Math.PI.toFloat() + side * ArrowHeadSpread
        return tip + Offset(cos(a) * arm, sin(a) * arm)
    }
    val head = Path().apply {
        moveTo(wing(1f).x, wing(1f).y)
        lineTo(tip.x, tip.y)
        lineTo(wing(-1f).x, wing(-1f).y)
    }
    drawPath(head, brush, alpha = 0.22f * alpha, style = Stroke(core * 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(head, brush, alpha = alpha, style = Stroke(core, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Half the opening angle of the arrow head, in radians (about 38 degrees). */
private const val ArrowHeadSpread = 0.66f

/**
 * The cube as scanned so far, in [NxNGeometry] order: every captured face's N² colors in place on
 * the face its step scans (the steps' holds read each face in that order). Faces not captured yet
 * are unknown.
 */
internal fun scannedCubeColors(captures: List<List<CubeColor>?>, size: Int): List<CubeColor?> {
    val geometry = NxNGeometry.of(size)
    val colors = MutableList<CubeColor?>(geometry.stickerCount) { null }
    for (step in ScanStep.entries) {
        val face = captures[step.ordinal] ?: continue
        for (i in 0 until geometry.stickersPerFace) colors[step.face.ordinal * geometry.stickersPerFace + i] = face[i]
    }
    return colors
}
