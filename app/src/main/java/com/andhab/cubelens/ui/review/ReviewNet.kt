package com.andhab.cubelens.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.ui.cube.CubeNet
import com.andhab.cubelens.ui.cube.NetMetrics
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import kotlin.math.roundToInt

/**
 * The editable cube net of the review screen: a [CubeNet] of any size centered in the available
 * width, with two extra marks drawn on top of it:
 *  - a small embossed lock on each [locked] sticker (the fixed centers of odd cubes), which never
 *    changes; left out where stickers are too small to carry it;
 *  - a subtle amber dot on the corner of each [uncertain] sticker, the ones worth a second look
 *    (stickers that are [flagged] already carry the stronger danger ring instead).
 *
 * A small legend for the marks sits in the empty corner beside the bottom face. Inside a scrolling
 * parent, the face of the [selected] sticker is scrolled into view whenever the selection moves.
 *
 * @param colors 6·N² sticker colors in [NxNGeometry] order.
 * @param onStickerTap makes stickers tappable (small cubes).
 * @param onFaceTap makes each face's plate tappable instead, e.g. to open it in a face editor when
 *   the stickers are too small to tap (big cubes).
 */
@Composable
internal fun ReviewNet(
    colors: List<CubeColor?>,
    flagged: Set<Int>,
    uncertain: Set<Int>,
    locked: Set<Int>,
    selected: Int?,
    onStickerTap: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    onFaceTap: ((Face) -> Unit)? = null,
) {
    val n = remember(colors.size) { cubeSizeOf(colors.size) }
    val geometry = NxNGeometry.of(n)
    val placement = remember { NetPlacement() }
    val lock = rememberVectorPainter(Icons.Rounded.Lock)
    val dots = uncertain.filter { it in colors.indices && it !in flagged }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        val metrics = placement.metrics ?: return@LaunchedEffect
        if (selected == null || selected !in colors.indices) return@LaunchedEffect
        val face = geometry.faceOf(selected)
        val topLeft = Offset(placement.offsetX + metrics.plateX(face), metrics.plateY(face))
        bringIntoView.bringIntoView(Rect(topLeft, Size(metrics.plate, metrics.plate)))
    }
    Layout(
        content = {
            CubeNet(
                colors = colors,
                highlightFacelets = flagged,
                selectedFacelet = selected,
                onStickerClick = onStickerTap,
                onFaceClick = onFaceTap,
            )
            NetLegend(
                lock = lock,
                showDots = dots.isNotEmpty(),
                showFlags = flagged.isNotEmpty(),
                showLocks = locked.isNotEmpty(),
            )
        },
        modifier = modifier
            .bringIntoViewRequester(bringIntoView)
            .drawWithContent {
                drawContent()
                val metrics = placement.metrics ?: return@drawWithContent
                translate(left = placement.offsetX) {
                    if (metrics.sticker >= MinLockSticker.toPx()) {
                        for (index in locked) {
                            drawLock(lock, Offset(metrics.stickerX(index), metrics.stickerY(index)), metrics.sticker)
                        }
                    }
                    val radius = (metrics.sticker * DOT_FRACTION).coerceAtMost(DotRadius.toPx())
                    val inset = (metrics.sticker * DOT_INSET_FRACTION).coerceAtMost(DotInset.toPx())
                    for (index in dots) {
                        val corner = Offset(metrics.stickerX(index) + metrics.sticker, metrics.stickerY(index))
                        drawUncertainDot(corner + Offset(-radius - inset, radius + inset), radius)
                    }
                }
            },
    ) { measurables, constraints ->
        // The same metrics CubeNet computes from the same constraints, so the marks line up exactly.
        val metrics = NetMetrics.fit(constraints, n)
        val net = measurables[0].measure(constraints)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else net.width
        val offsetX = (width - net.width) / 2
        placement.metrics = metrics
        placement.offsetX = offsetX.toFloat()

        // The legend fills the corner right of the bottom face: two plates wide, one plate tall.
        val legendLeft = (metrics.plateX(Face.R) + metrics.faceGap).roundToInt()
        val legendWidth = (2 * metrics.plate).roundToInt()
        val legend = measurables[1].measure(Constraints(maxWidth = legendWidth, maxHeight = metrics.plate.roundToInt()))
        val legendTop = metrics.plateY(Face.D).roundToInt() + (metrics.plate.roundToInt() - legend.height) / 2
        layout(width, net.height) {
            net.place(offsetX, 0)
            legend.place(offsetX + legendLeft, legendTop)
        }
    }
}

/** N of a cube with [count] (6·N²) stickers. */
internal fun cubeSizeOf(count: Int): Int =
    (NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE).firstOrNull { 6 * it * it == count }
        ?: throw IllegalArgumentException("A cube has 6·N² stickers, got $count")

/** Where the net was last laid out; written by the measure pass and read by the draw pass. */
private class NetPlacement {
    var metrics: NetMetrics? = null
    var offsetX: Float = 0f
}

/** Explains the marks on the net: red rings, amber dots and locks, each only when there are any. */
@Composable
private fun NetLegend(lock: VectorPainter, showDots: Boolean, showFlags: Boolean, showLocks: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showFlags) {
            LegendRow(stringResource(R.string.review_legend_flagged)) { drawFlagRing() }
        }
        if (showDots) {
            LegendRow(stringResource(R.string.review_legend_uncertain)) {
                drawUncertainDot(Offset(size.width / 2f, size.height / 2f), DotRadius.toPx())
            }
        }
        if (showLocks) {
            LegendRow(stringResource(R.string.review_legend_locked)) {
                drawLock(lock, Offset.Zero, size.minDimension, background = LegendSticker)
            }
        }
    }
}

@Composable
private fun LegendRow(label: String, marker: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp), onDraw = marker)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Brand.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Size of the center lock relative to a sticker. */
private const val LOCK_FRACTION = 0.42f

/** Darkens whatever sticker color is underneath, so the lock reads as embossed into the plastic. */
private val LockTint = ColorFilter.tint(Color.Black.copy(alpha = 0.34f))

/** Neutral sticker behind the lock in the legend. */
private val LegendSticker = Color(0xFFB9B5C9)

/** The uncertain dot's radius at most (it shrinks with small stickers, see [DOT_FRACTION]). */
internal val DotRadius = 4.dp

/** The uncertain dot's inset from the sticker's corner at most (see [DOT_INSET_FRACTION]). */
internal val DotInset = 2.dp

/** On small stickers the dot's radius shrinks to this fraction of the sticker. */
internal const val DOT_FRACTION = 0.2f

/** On small stickers the dot's inset shrinks to this fraction of the sticker. */
internal const val DOT_INSET_FRACTION = 0.06f

/** Stickers smaller than this carry no lock: it would only be a smudge. */
private val MinLockSticker = 14.dp

/**
 * The center lock on a sticker of side [sticker] at [topLeft]; with a [background], draws that
 * sticker first.
 */
internal fun DrawScope.drawLock(lock: VectorPainter, topLeft: Offset, sticker: Float, background: Color? = null) {
    if (background != null) {
        drawRoundRect(background, topLeft, Size(sticker, sticker), CornerRadius(sticker * 0.22f))
    }
    val lockSize = sticker * LOCK_FRACTION
    val inset = (sticker - lockSize) / 2f
    translate(topLeft.x + inset, topLeft.y + inset) {
        with(lock) { draw(Size(lockSize, lockSize), colorFilter = LockTint) }
    }
}

/** An amber dot of [radius] inside a dark ring, centered on [center]. */
internal fun DrawScope.drawUncertainDot(center: Offset, radius: Float) {
    val ring = (radius * 0.45f).coerceAtMost(1.75.dp.toPx())
    drawCircle(CubePalette.Body, radius = radius + ring, center = center)
    drawCircle(Brand.Amber, radius = radius, center = center)
}

/** A miniature flagged sticker: a danger ring around a dark slot, like the net's flags. */
private fun DrawScope.drawFlagRing() {
    val stroke = 2.dp.toPx()
    drawRoundRect(
        color = Brand.Danger,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(size.minDimension * 0.24f),
        style = Stroke(stroke),
    )
}
