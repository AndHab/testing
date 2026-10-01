package com.andhab.cubelens.ui.solve

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.components.MoveChip
import com.andhab.cubelens.ui.components.MoveState
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The whole solution as a scrollable strip of [MoveChip]s: done moves recede and carry a mint
 * check, the current move glows in sunset, upcoming moves wait in plain glass. The strip glides to
 * keep the current move centered, and its ends fade into the background.
 *
 * @param currentIndex index of the current move; `moves.size` marks every move as done.
 * @param onMoveClick called with a move's index when its chip is tapped.
 */
@Composable
internal fun MoveTimeline(
    moves: List<Move>,
    currentIndex: Int,
    onMoveClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceIn(0, (moves.size - 1).coerceAtLeast(0))),
) {
    val static = LocalInspectionMode.current
    LaunchedEffect(state, currentIndex, moves.size) {
        if (moves.isNotEmpty()) state.centerOn(currentIndex.coerceIn(0, moves.lastIndex), animate = !static)
    }
    val timelineLabel = stringResource(R.string.solve_timeline)
    LazyRow(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = timelineLabel }
            .fadingEdges(EdgeFade),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(moves) { index, move ->
            TimelineChip(
                move = move,
                state = when {
                    index < currentIndex -> MoveState.Done
                    index == currentIndex -> MoveState.Current
                    else -> MoveState.Upcoming
                },
                onClick = { onMoveClick(index) },
            )
        }
    }
}

/** Width of the fade at either end of the strip. */
private val EdgeFade: Dp = 28.dp

@Composable
private fun TimelineChip(move: Move, state: MoveState, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) CubeLensMotion.PressedScale else 1f,
        animationSpec = CubeLensMotion.press(),
        label = "timelineChipScale",
    )
    val jumpLabel = stringResource(R.string.solve_jump_to_move)
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = jumpLabel,
                onClick = onClick,
            ),
    ) {
        MoveChip(notation = move.notation, state = state)
        AnimatedVisibility(
            visible = state == MoveState.Done,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 5.dp, y = (-5).dp),
            enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            label = "doneCheck",
        ) {
            DoneCheck()
        }
    }
}

/** Small mint check badge marking a finished move. */
@Composable
private fun DoneCheck() {
    Box(
        Modifier
            .size(18.dp)
            .background(Brand.Ink, CircleShape)
            .padding(2.dp)
            .background(Brand.Mint, CircleShape)
            .border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(11.dp))
    }
}

/**
 * Scrolls so the item at [index] sits in the middle of the viewport (as far as the ends allow).
 * An item far off screen is first brought into view instantly, then centered.
 */
private suspend fun LazyListState.centerOn(index: Int, animate: Boolean) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    val item = snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }
        .filterNotNull()
        .first()
    val info = layoutInfo
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val delta = item.offset + item.size / 2f - viewportCenter
    if (delta == 0f) return
    if (animate) {
        animateScrollBy(delta, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
    } else {
        scrollBy(delta)
    }
}

/** Fades the content out toward the left and right edges over [width]. */
private fun Modifier.fadingEdges(width: Dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = width.toPx() / size.width
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
