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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.core.nxn.NxNSolver
import com.andhab.cubelens.core.nxn.SolveStage
import com.andhab.cubelens.core.nxn.toLayerMove
import com.andhab.cubelens.ui.components.AuroraBackground
import com.andhab.cubelens.ui.components.CircleIconButton
import com.andhab.cubelens.ui.components.ConfettiBurst
import com.andhab.cubelens.ui.components.TopBar
import com.andhab.cubelens.ui.cube.Cube3D
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.cube.rememberCubeViewState
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Step-by-step animated playback of a staged solution, for a cube of any size.
 *
 * The scrambled cube turns in 3D at the top (drag to look around) under a hint on how to hold it;
 * below it the current move is spelled out big, the whole solution runs along a tappable timeline,
 * and transport controls step, play and pace it (up to 4× for the hundreds of moves of a big cube).
 * A solution in several stages ("Corners", "Edges", "Centers") names the stage under way, marks
 * where each stage starts on the timeline and offers jumps to the previous and next stage. While
 * paused, the layers that turn next give a little nudge the way they will turn every few seconds.
 * Reaching the end celebrates with confetti and offers to scan another cube or replay the solution.
 * A solution with no moves celebrates straight away.
 *
 * Sticker colors everywhere (the cube, the turn pictogram, the hint swatches, the confetti) come
 * from [LocalStickerPalette], so a pastel cube is shown in its own colors.
 *
 * @param startColors the scrambled cube: 6·N² colors in [NxNGeometry] order, in the orientation
 *   it was scanned in (N is inferred from their number).
 * @param solution the solution, stage by stage; applying its moves to [startColors] solves the cube.
 * @param onBack leave playback without finishing (also the system back gesture).
 * @param onDone leave playback (e.g. "Scan another cube").
 */
@Composable
fun SolveScreen(
    startColors: List<CubeColor>,
    solution: NxNSolution,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cubeState = rememberCubeViewState(startColors, HomeYaw, HomePitch)
    val playback = rememberSolvePlayback(startColors, solution, cubeState)
    BackHandler(onBack = onBack)
    SolveContent(
        playback = playback,
        cubeState = cubeState,
        onBack = onBack,
        onDone = onDone,
        modifier = modifier,
    )
}

/**
 * Step-by-step animated playback of a 3×3 solution: the [SolveScreen] for [moves] as a single stage.
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
    val solution = remember(moves) {
        val stages = if (moves.isEmpty()) emptyList() else listOf(SolveStage(NxNSolver.STAGE_SOLVE, moves.map { it.toLayerMove() }))
        NxNSolution(3, stages)
    }
    SolveScreen(startColors, solution, onBack, onDone, modifier)
}

/** Color of the turning layers in the pictogram of a cube without fixed centers. */
private val NeutralHighlight = Brand.TextSecondary

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
    val orientation = remember(playback) { CubeOrientation.of(playback.n, playback.colorsAt(0)) }
    val palette = LocalStickerPalette.current
    // The turn pictogram paints a side in its center color; a side of an even cube has none to
    // point to while scrambled, so its turning layers light up in a neutral pearl instead.
    val faceColor = remember(orientation, palette) {
        { face: Face -> orientation.scheme?.get(face)?.let(palette::color) ?: NeutralHighlight }
    }
    val stages = rememberTimelineStages(playback)
    val scope = rememberCoroutineScope()
    val animationsEnabled = rememberAnimationsEnabled()

    // While paused, nudge the layers that turn next (left out of static previews and screenshots).
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
                hold = orientation.hold,
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
                        staged = stages.size > 1,
                        onScanAnother = onDone,
                        onReplay = {
                            playback.restart()
                            playback.play()
                            scope.launch { cubeState.animateView(HomeYaw, HomePitch) }
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    )
                } else {
                    PlaybackSection(playback, stages, faceColor)
                }
            }
        }
        // Confetti in the cube's own sticker colors (the palette is read by the burst).
        ConfettiBurst(trigger = if (finished) playback.celebration else null)
    }
}

/** The stages of [playback] as the timeline and panel label them, localized. */
@Composable
private fun rememberTimelineStages(playback: SolvePlayback): List<TimelineStage> {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(playback, resources, configuration) {
        playback.stages.mapIndexed { s, stage -> TimelineStage(resources.stageName(stage.name), playback.stageStart(s)) }
    }
}

/**
 * What the start colors say about holding the cube: the color of each side ([scheme], from the
 * fixed centers of an odd size; null for an even size, which has no fixed centers, so its sides
 * show no single color until solved) and the [hold] hint.
 */
@Immutable
private class CubeOrientation(val scheme: Map<Face, CubeColor>?, val hold: HoldOrientation) {
    companion object {
        fun of(n: Int, colors: List<CubeColor>): CubeOrientation {
            if (n % 2 == 0) return CubeOrientation(scheme = null, hold = HoldOrientation.AsScanned)
            val geometry = NxNGeometry.of(n)
            val centers = Face.entries.associateWith { colors[geometry.index(it, n / 2, n / 2)] }
            return CubeOrientation(
                scheme = centers,
                hold = HoldOrientation.Centers(
                    front = centers.getValue(Face.F),
                    top = centers.getValue(Face.U),
                    right = centers.getValue(Face.R),
                ),
            )
        }
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
    hold: HoldOrientation,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Cube3D(
            state = cubeState,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 40.dp),
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
            HoldHint(hold)
        }
    }
}

/** Current move, timeline and transport controls. */
@Composable
private fun PlaybackSection(playback: SolvePlayback, stages: List<TimelineStage>, faceColor: (Face) -> Color) {
    val panelStages = if (stages.size > 1) {
        PanelStages(
            stages = stages,
            current = playback.currentStage,
            hasPrevious = playback.hasPreviousStage,
            hasNext = playback.hasNextStage,
            onPrevious = playback::previousStage,
            onNext = playback::nextStage,
        )
    } else {
        null
    }
    Column(Modifier.fillMaxWidth()) {
        MovePanel(
            moves = playback.moves,
            n = playback.n,
            currentIndex = playback.currentIndex,
            position = playback.position,
            faceColor = faceColor,
            stages = panelStages,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        MoveTimeline(
            moves = playback.moves,
            stages = stages,
            currentIndex = playback.currentIndex,
            onMoveClick = playback::jumpTo,
            onStageClick = { playback.jumpTo(playback.stageStart(it)) },
        )
        Spacer(Modifier.height(4.dp))
        PlaybackControls(playback, Modifier.padding(bottom = 20.dp))
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
