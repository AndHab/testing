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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.andhab.cubelens.R
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.ui.components.MoveChip
import com.andhab.cubelens.ui.components.MoveState
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** A stage as the timeline marks it: its localized [name] and the index of its first move. */
@Immutable
internal data class TimelineStage(val name: String, val start: Int)

/**
 * The whole solution as a scrollable strip of [MoveChip]s: done moves recede and carry a mint
 * check, the current move glows in sunset, upcoming moves wait in plain glass. When the solution
 * has more than one stage, a labeled divider ("Step 2 · Edges") opens each stage; tapping it jumps
 * to the stage's first move. The strip glides to keep the current move centered, and its ends fade
 * into the background.
 *
 * Only the chips on screen are composed, so solutions of hundreds of moves scroll smoothly.
 *
 * @param stages the solution's stages in order; dividers are shown only for two or more.
 * @param currentIndex index of the current move; `moves.size` marks every move as done.
 * @param onMoveClick called with a move's index when its chip is tapped.
 * @param onStageClick called with a stage's index when its divider is tapped.
 */
@Composable
internal fun MoveTimeline(
    moves: List<LayerMove>,
    stages: List<TimelineStage>,
    currentIndex: Int,
    onMoveClick: (Int) -> Unit,
    onStageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(moves.size, stages) { TimelineItems(moves.size, stages.map { it.start }) }
    val currentItem = if (moves.isEmpty()) 0 else items.itemOfMove(currentIndex.coerceIn(0, moves.lastIndex))
    val state: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = currentItem)
    val currentStage = if (items.hasDividers) items.stageOfMove(currentIndex.coerceIn(0, moves.lastIndex)) else 0
    val static = LocalInspectionMode.current
    LaunchedEffect(state, currentItem, items) {
        if (items.size > 0) state.centerOn(currentItem, animate = !static)
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
        items(
            count = items.size,
            key = { items.keyOf(it) },
            contentType = { if (items.moveAt(it) >= 0) ContentMove else ContentDivider },
        ) { item ->
            val index = items.moveAt(item)
            if (index >= 0) {
                TimelineChip(
                    move = moves[index],
                    state = when {
                        index < currentIndex -> MoveState.Done
                        index == currentIndex -> MoveState.Current
                        else -> MoveState.Upcoming
                    },
                    onClick = { onMoveClick(index) },
                )
            } else {
                val stage = items.stageAt(item)
                StageDivider(
                    number = stage + 1,
                    name = stages[stage].name,
                    state = when {
                        currentIndex >= moves.size || stage < currentStage -> MoveState.Done
                        stage == currentStage -> MoveState.Current
                        else -> MoveState.Upcoming
                    },
                    onClick = { onStageClick(stage) },
                )
            }
        }
    }
}

private const val ContentMove = 0
private const val ContentDivider = 1

/**
 * The timeline's items: every move, with a stage divider before each stage's first move when there
 * are [stageStarts] for two or more stages.
 */
private class TimelineItems(moveCount: Int, stageStarts: List<Int>) {
    val hasDividers = stageStarts.size > 1

    /** For each item: the move index, or `-(stage + 1)` for a divider. */
    private val entries: IntArray

    /** Item index of each move. */
    private val itemOfMove: IntArray

    /** Stage of each move (all 0 without dividers). */
    private val stageOfMove: IntArray

    init {
        val dividers = if (hasDividers) stageStarts.size else 0
        entries = IntArray(moveCount + dividers)
        itemOfMove = IntArray(moveCount)
        stageOfMove = IntArray(moveCount)
        var item = 0
        var stage = -1
        for (move in 0 until moveCount) {
            while (hasDividers && stage + 1 < stageStarts.size && stageStarts[stage + 1] == move) {
                stage++
                entries[item++] = -(stage + 1)
            }
            itemOfMove[move] = item
            stageOfMove[move] = stage.coerceAtLeast(0)
            entries[item++] = move
        }
    }

    val size: Int get() = entries.size

    /** The move shown by [item], or -1 for a divider. */
    fun moveAt(item: Int): Int = entries[item].let { if (it >= 0) it else -1 }

    /** The stage whose divider [item] is. */
    fun stageAt(item: Int): Int = -entries[item] - 1

    fun itemOfMove(move: Int): Int = itemOfMove[move]

    fun stageOfMove(move: Int): Int = stageOfMove[move]

    /** A stable key: moves by index, dividers by stage. */
    fun keyOf(item: Int): Int = entries[item]
}

/** Width of the fade at either end of the strip. */
private val EdgeFade: Dp = 28.dp

@Composable
private fun TimelineChip(move: LayerMove, state: MoveState, onClick: () -> Unit) {
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
        MoveChip(notation = move.notation, state = state, contentDescription = spokenMove(move))
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

/**
 * Where a stage begins: a slim glass marker with a glowing edge, "STEP 2" over the stage's name.
 * The edge and number are mint for stages already done, sunset for the stage under way, and quiet
 * for the ones still to come.
 */
@Composable
private fun StageDivider(number: Int, name: String, state: MoveState, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) CubeLensMotion.PressedScale else 1f,
        animationSpec = CubeLensMotion.press(),
        label = "stageDividerScale",
    )
    val stepLabel = stringResource(R.string.solve_stage_number, number)
    val jumpLabel = stringResource(R.string.solve_jump_to_stage)
    val accent: Brush = when (state) {
        MoveState.Done -> Brush.verticalGradient(listOf(Brand.Mint, Brand.Mint.copy(alpha = 0.35f)))
        MoveState.Current -> Brand.SunsetBrush
        MoveState.Upcoming -> Brush.verticalGradient(listOf(Brand.HairlineStrong, Brand.Hairline))
    }
    val numberColor = when (state) {
        MoveState.Done -> Brand.Mint
        MoveState.Current -> Brand.Tangerine
        MoveState.Upcoming -> Brand.TextTertiary
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent)), shape)
            .border(1.dp, Brush.horizontalGradient(listOf(Brand.HairlineStrong, Color.Transparent)), shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$stepLabel: $name"
                role = Role.Button
                onClick(label = jumpLabel) {
                    onClick()
                    true
                }
            }
            .padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .background(accent, CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                text = stepLabel.uppercase(currentLocale()),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.16.em),
                color = numberColor,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (state == MoveState.Upcoming) Brand.TextSecondary else Brand.TextPrimary,
                maxLines = 1,
                softWrap = false,
            )
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
