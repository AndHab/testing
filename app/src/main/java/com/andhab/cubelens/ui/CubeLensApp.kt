package com.andhab.cubelens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andhab.cubelens.R
import com.andhab.cubelens.ui.components.BannerKind
import com.andhab.cubelens.ui.components.StatusBanner
import com.andhab.cubelens.ui.home.HomeScreen
import com.andhab.cubelens.ui.review.ReviewScreen
import com.andhab.cubelens.ui.scan.ScanScreen
import com.andhab.cubelens.ui.solve.SolveScreen
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import kotlinx.coroutines.delay

/**
 * The whole app: shows the [AppViewModel]'s current screen, slides between screens (forward
 * moves come in from the right, back moves from the left), routes system back through
 * [AppViewModel.back] and floats short-lived error messages over the top. A screen on its way out
 * ignores touches, so a quick second tap can't act on the screen being left, and keeps drawing its
 * cube in its own colors while it slides away.
 */
@Composable
fun CubeLensApp(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.screen != Screen.Home) { viewModel.back() }

    Box(
        modifier
            .fillMaxSize()
            .background(Brand.Ink),
    ) {
        AnimatedContent(
            targetState = state.screen,
            // Same kind of screen (e.g. an edit on the review) updates in place without a transition.
            // The key must be the same in every process: the saved state of the screen's content
            // (a scan in progress, the playback position) is keyed by it, and comes back after
            // process death only if it matches. A class's hash code is not, its depth is.
            contentKey = { it.depth },
            transitionSpec = { screenTransition() },
            label = "screen",
        ) { screen ->
            val leaving = screen.depth != state.screen.depth
            Box(
                Modifier
                    .fillMaxSize()
                    .ignoreInputWhen(leaving),
            ) {
                ScreenContent(screen, state, viewModel)
            }
        }
        MessageBanner(
            message = state.message,
            onDismiss = viewModel::dismissMessage,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * The composable for [screen], wired to [viewModel], drawing its cube in the screen's own
 * [Screen.palette] (e.g. a pastel cube's colors on its review and solution).
 */
@Composable
private fun ScreenContent(screen: Screen, state: AppUiState, viewModel: AppViewModel) {
    CompositionLocalProvider(LocalStickerPalette provides screen.palette) {
        when (screen) {
            Screen.Home -> HomeScreen(
                onScan = viewModel::openScan,
                onManualEntry = viewModel::openManualEntry,
                onRandomScramble = viewModel::playRandomScramble,
                size = state.size,
                onSizeChange = viewModel::selectSize,
                scrambling = state.scrambling,
            )
            is Screen.Scan -> ScanScreen(
                onScanned = viewModel::onScanned,
                onBack = { viewModel.back() },
                onManualEntry = viewModel::openManualEntry,
                size = screen.size,
            )
            is Screen.Review -> ReviewScreen(
                review = screen.review,
                onBack = { viewModel.back() },
                onRescan = viewModel::rescan,
                onStickerTap = viewModel::onStickerTap,
                onColorTap = viewModel::onColorTap,
                onFaceTap = viewModel::onFaceTap,
                onFaceStep = viewModel::onFaceStep,
                onFaceClose = viewModel::onFaceClose,
                onUndo = viewModel::undo,
                onSolve = viewModel::solve,
                onLeave = viewModel::leaveReview,
                onStay = viewModel::keepEditing,
            )
            is Screen.Solve -> SolveScreen(
                startColors = screen.startColors,
                solution = screen.solution,
                onBack = { viewModel.back() },
                onDone = viewModel::onSolveDone,
                // Null for a random scramble, which has no cube in hand.
                source = screen.returnTo?.review?.source,
            )
        }
    }
}

/**
 * While [ignore] is true, swallows every pointer event before the content sees it and hides the
 * content from accessibility services.
 */
private fun Modifier.ignoreInputWhen(ignore: Boolean): Modifier =
    if (!ignore) {
        this
    } else {
        this
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
    }

/** Slide plus fade, in the direction of travel through Home → Scan → Review → Solve. */
private fun AnimatedContentTransitionScope<Screen>.screenTransition(): ContentTransform {
    val forward = targetState.depth >= initialState.depth
    val direction = if (forward) 1 else -1
    val slide = spring<IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    val enter = slideInHorizontally(slide) { width -> direction * width / 4 } +
        fadeIn(tween(durationMillis = 280, delayMillis = 60))
    val exit = slideOutHorizontally(slide) { width -> -direction * width / 8 } +
        fadeOut(tween(durationMillis = 200))
    return (enter togetherWith exit) using SizeTransform(clip = false)
}

/** How long a message stays up before it hides itself. */
private const val MessageMillis = 4_000L

/**
 * A friendly error banner that drops in from the top for a few seconds; tap to hide it sooner.
 */
@Composable
private fun MessageBanner(message: AppMessage?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        if (message != null) {
            delay(MessageMillis)
            dismiss()
        }
    }
    // AnimatedContent keeps showing the old message while it slides away.
    AnimatedContent(
        targetState = message,
        transitionSpec = {
            (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) using
                SizeTransform(clip = false)
        },
        modifier = modifier,
        label = "message",
    ) { current ->
        if (current == null) return@AnimatedContent
        val title = when (current) {
            AppMessage.ScanFailed -> stringResource(R.string.message_scan_failed)
            AppMessage.SolveFailed -> stringResource(R.string.message_solve_failed)
            AppMessage.ScrambleFailed -> stringResource(R.string.message_scramble_failed)
        }
        StatusBanner(
            kind = BannerKind.Error,
            title = title,
            message = stringResource(R.string.message_try_again),
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
}
