package com.andhab.cubelens.ui.solve

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.components.AuroraBackground
import com.andhab.cubelens.ui.components.CircleIconButton
import com.andhab.cubelens.ui.components.ConfettiBurst
import com.andhab.cubelens.ui.components.Pill
import com.andhab.cubelens.ui.components.TopBar
import com.andhab.cubelens.ui.components.colorName
import com.andhab.cubelens.ui.cube.Cube3D
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.cube.rememberCubeViewState
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Step-by-step animated solution playback.
 *
 * The scrambled cube turns in 3D at the top (drag to look around); below it the current move is
 * spelled out big, the whole solution runs along a tappable timeline, and transport controls step,
 * play and pace it. While paused, the layer that turns next gives a little nudge the way it will
 * turn every few seconds. Reaching the end celebrates with confetti and offers to scan another
 * cube or replay the solution. A solution with no moves celebrates straight away.
 *
 * @param startColors the scrambled cube (54 colors, facelet order, standard orientation).
 * @param moves the solution; applying them to [startColors] solves the cube.
 * @param onBack leave playback without finishing (also the system back gesture).
 * @param onDone leave playback (e.g. "Scan another cube").
 */
@Composable
fun SolveScreen(
    startColors: List<CubeColor>,
    moves: List<Move>,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cubeState = rememberCubeViewState(startColors, HomeYaw, HomePitch)
    val playback = rememberSolvePlayback(startColors, moves, cubeState)
    BackHandler(onBack = onBack)
    SolveContent(
        playback = playback,
        cubeState = cubeState,
        onBack = onBack,
        onDone = onDone,
        modifier = modifier,
    )
}

/** Camera angles the cube starts at, and returns to with "Reset view". */
private const val HomeYaw = -35f
private const val HomePitch = 28f

/**
 * The solve screen for an existing [playback] showing on [cubeState]; split from [SolveScreen] so
 * previews and screenshot tests can set up any moment of playback.
 */
@Composable
internal fun SolveContent(
    playback: SolvePlayback,
    cubeState: CubeViewState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val finished = playback.isFinished
    val auroraIntensity by animateFloatAsState(
        targetValue = if (finished) 1.35f else 1f,
        animationSpec = tween(durationMillis = 900),
        label = "solveAurora",
    )
    val startColors = remember(playback) { playback.colorsAt(0) }
    val faceColor = remember(startColors) { { face: Face -> CubePalette.color(startColors[Facelets.center(face)]) } }
    val scope = rememberCoroutineScope()
    val animationsEnabled = rememberAnimationsEnabled()

    // While paused, nudge the layer that turns next (left out of static previews and screenshots).
    val waitingIndex = playback.waitingIndex
    if (waitingIndex != null && !LocalInspectionMode.current) {
        LaunchedEffect(cubeState, playback, waitingIndex) {
            cubeState.hintTurnWhileWaiting(playback.moves[waitingIndex]) { playback.waitingIndex == waitingIndex }
        }
    }

    AuroraBackground(modifier.fillMaxSize(), intensity = auroraIntensity) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            TopBar(
                onBack = onBack,
                actions = { ResetViewButton(cubeState, enabled = !finished) },
            )
            CubeStage(
                cubeState = cubeState,
                showHint = !finished,
                // A slow victory spin once solved.
                spin = finished && animationsEnabled,
                front = startColors[Facelets.center(Face.F)],
                top = startColors[Facelets.center(Face.U)],
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            AnimatedContent(
                targetState = finished,
                contentAlignment = Alignment.BottomCenter,
                transitionSpec = {
                    val enter = fadeIn(tween(420, delayMillis = 160)) + slideInVertically(tween(520, delayMillis = 120)) { it / 4 }
                    val exit = fadeOut(tween(200)) + slideOutVertically(tween(260)) { it / 6 }
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "solveBottom",
            ) { done ->
                if (done) {
                    SolvedCard(
                        moveCount = playback.moveCount,
                        onScanAnother = onDone,
                        onReplay = {
                            playback.restart()
                            playback.play()
                            scope.launch { cubeState.animateView(HomeYaw, HomePitch) }
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    )
                } else {
                    PlaybackSection(playback, faceColor)
                }
            }
        }
        ConfettiBurst(trigger = if (finished) playback.celebration else null)
    }
}

/**
 * The hero: the interactive 3D cube, with the orientation hint floating above it until the cube is
 * solved. [spin] sets it slowly turning and floating.
 */
@Composable
private fun CubeStage(
    cubeState: CubeViewState,
    showHint: Boolean,
    spin: Boolean,
    front: CubeColor,
    top: CubeColor,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Cube3D(
            state = cubeState,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 36.dp),
            interactive = true,
            autoRotate = spin,
        )
        AnimatedVisibility(
            visible = showHint,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
            label = "holdHint",
        ) {
            HoldHint(front = front, top = top)
        }
    }
}

/** Current move, timeline and transport controls. */
@Composable
private fun PlaybackSection(playback: SolvePlayback, faceColor: (Face) -> Color) {
    Column(Modifier.fillMaxWidth()) {
        MovePanel(
            moves = playback.moves,
            currentIndex = playback.currentIndex,
            position = playback.position,
            faceColor = faceColor,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        MoveTimeline(
            moves = playback.moves,
            currentIndex = playback.currentIndex,
            onMoveClick = playback::jumpTo,
        )
        Spacer(Modifier.height(4.dp))
        PlaybackControls(playback, Modifier.padding(bottom = 20.dp))
    }
}

/**
 * The orientation the solution assumes, in the cube's own colors: "Hold green toward you, white
 * on top". A [Pill] when it fits on one line inside the screen gutter; on very narrow screens with
 * large text, a matching capsule that wraps, since every word of it matters.
 */
@Composable
private fun HoldHint(front: CubeColor, top: CubeColor, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val text = stringResource(
        R.string.solve_hold_hint,
        colorName(front).lowercase(locale),
        colorName(top).lowercase(locale),
    )
    BoxWithConstraints(modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.TopCenter) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = MaterialTheme.typography.labelMedium
        val oneLine = remember(text, style, density, maxWidth) {
            val width = with(density) { measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toDp() }
            width + PillChrome <= maxWidth
        }
        if (oneLine) {
            Pill(text = text, icon = Icons.Rounded.ViewInAr)
        } else {
            // Balanced lines ("Hold green toward / you, white on top"), not a lone last word, and
            // the capsule hugs the longest line instead of stretching across the screen.
            val wrapStyle = style.copy(lineBreak = LineBreak.Heading)
            val textWidth = remember(text, wrapStyle, density, maxWidth) {
                val maxTextWidth = with(density) { (maxWidth - WrappingHintChrome).roundToPx().coerceAtLeast(0) }
                val layout = measurer.measure(text, wrapStyle, constraints = Constraints(maxWidth = maxTextWidth))
                val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
                with(density) { ceil(widest).toDp() }
            }
            WrappingHint(text = text, style = wrapStyle, textWidth = textWidth)
        }
    }
}

/** Width a [Pill] with an icon adds around its text: start padding, icon, gap and end padding. */
private val PillChrome = 10.dp + 15.dp + 6.dp + 12.dp

/** Width [WrappingHint] adds around its text. */
private val WrappingHintChrome = 12.dp + 15.dp + 8.dp + 14.dp

/** The [Pill] look for a hint that needs two lines, its text [textWidth] wide. */
@Composable
private fun WrappingHint(text: String, style: TextStyle, textWidth: Dp) {
    val color = Brand.TextSecondary
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape)
            .border(1.dp, color.copy(alpha = 0.24f), shape)
            .padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.ViewInAr, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(text = text, style = style, color = color, modifier = Modifier.width(textWidth))
    }
}

/**
 * Glides the camera back to the starting view; shown (while [enabled]) only once the cube has been
 * dragged away from it, since the solution is easiest to follow from there.
 */
@Composable
private fun ResetViewButton(cubeState: CubeViewState, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val awayFromHome by remember(cubeState) {
        derivedStateOf {
            abs(wrapDegrees(cubeState.yaw - HomeYaw)) > ViewTolerance || abs(cubeState.pitch - HomePitch) > ViewTolerance
        }
    }
    AnimatedVisibility(
        visible = enabled && awayFromHome,
        enter = fadeIn() + scaleIn(initialScale = 0.7f),
        exit = fadeOut() + scaleOut(targetScale = 0.7f),
        label = "resetView",
    ) {
        CircleIconButton(
            icon = Icons.Rounded._3dRotation,
            contentDescription = stringResource(R.string.solve_reset_view),
            onClick = { scope.launch { cubeState.animateView(HomeYaw, HomePitch) } },
            size = 44.dp,
        )
    }
}

/** Degrees the view may drift from home before "Reset view" appears. */
private const val ViewTolerance = 4f

/** [degrees] wrapped into [-180, 180). */
private fun wrapDegrees(degrees: Float): Float = ((degrees + 180f) % 360f + 360f) % 360f - 180f

/**
 * False while the user has animations turned off (animator duration scale 0). Reads the same live
 * [MotionDurationScale] that [SolvePlayback] uses to decide between turning and snapping, so the
 * two always agree, also when the setting changes while the screen is showing.
 */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val enabled = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val scale = coroutineContext[MotionDurationScale] ?: return@LaunchedEffect
        snapshotFlow { scale.scaleFactor != 0f }.collect { enabled.value = it }
    }
    return enabled.value
}
