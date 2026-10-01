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
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.ui.cube.CubeNet
import com.andhab.cubelens.ui.cube.NetMetrics
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import kotlin.math.roundToInt

/**
 * The editable cube net of the review screen: a [CubeNet] centered in the available width, with two
 * extra marks drawn on top of it:
 *  - a small embossed lock on each center, which never changes;
 *  - a subtle amber dot on the corner of each [uncertain] sticker, the ones worth a second look
 *    (stickers that are [flagged] already carry the stronger danger ring instead).
 *
 * A small legend for the marks sits in the empty corner beside the bottom face. Inside a scrolling
 * parent, the face of the [selected] sticker is scrolled into view whenever the selection moves.
 */
@Composable
internal fun ReviewNet(
    colors: List<CubeColor?>,
    flagged: Set<Int>,
    uncertain: Set<Int>,
    selected: Int?,
    onStickerTap: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val placement = remember { NetPlacement() }
    val lock = rememberVectorPainter(Icons.Rounded.Lock)
    val dots = uncertain.filter { it in 0 until Facelets.COUNT && it !in flagged }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        val metrics = placement.metrics ?: return@LaunchedEffect
        if (selected == null) return@LaunchedEffect
        val face = Facelets.faceOf(selected)
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
            )
            NetLegend(lock = lock, showDots = dots.isNotEmpty(), showFlags = flagged.isNotEmpty())
        },
        modifier = modifier
            .bringIntoViewRequester(bringIntoView)
            .drawWithContent {
                drawContent()
                val metrics = placement.metrics ?: return@drawWithContent
                translate(left = placement.offsetX) {
                    for (face in Face.entries) {
                        val center = Facelets.center(face)
                        drawLock(lock, Offset(metrics.stickerX(center), metrics.stickerY(center)), metrics.sticker)
                    }
                    for (index in dots) {
                        val corner = Offset(metrics.stickerX(index) + metrics.sticker, metrics.stickerY(index))
                        drawUncertainDot(corner + Offset(-DotRadius.toPx() - DotInset.toPx(), DotRadius.toPx() + DotInset.toPx()))
                    }
                }
            },
    ) { measurables, constraints ->
        // The same metrics CubeNet computes from the same constraints, so the marks line up exactly.
        val metrics = NetMetrics.fit(constraints)
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

/** Where the net was last laid out; written by the measure pass and read by the draw pass. */
private class NetPlacement {
    var metrics: NetMetrics? = null
    var offsetX: Float = 0f
}

/** Explains the marks on the net: red rings and amber dots when there are any, and the locks. */
@Composable
private fun NetLegend(lock: VectorPainter, showDots: Boolean, showFlags: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showFlags) {
            LegendRow(stringResource(R.string.review_legend_flagged)) { drawFlagRing() }
        }
        if (showDots) {
            LegendRow(stringResource(R.string.review_legend_uncertain)) {
                drawUncertainDot(Offset(size.width / 2f, size.height / 2f))
            }
        }
        LegendRow(stringResource(R.string.review_legend_locked)) {
            drawLock(lock, Offset.Zero, size.minDimension, background = LegendSticker)
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

private val DotRadius = 4.dp
private val DotInset = 2.dp

/**
 * The center lock on a sticker of side [sticker] at [topLeft]; with a [background], draws that
 * sticker first.
 */
private fun DrawScope.drawLock(lock: VectorPainter, topLeft: Offset, sticker: Float, background: Color? = null) {
    if (background != null) {
        drawRoundRect(background, topLeft, Size(sticker, sticker), CornerRadius(sticker * 0.22f))
    }
    val lockSize = sticker * LOCK_FRACTION
    val inset = (sticker - lockSize) / 2f
    translate(topLeft.x + inset, topLeft.y + inset) {
        with(lock) { draw(Size(lockSize, lockSize), colorFilter = LockTint) }
    }
}

/** An amber dot inside a dark ring, centered on [center]. */
private fun DrawScope.drawUncertainDot(center: Offset) {
    val radius = DotRadius.toPx()
    drawCircle(CubePalette.Body, radius = radius + 1.75.dp.toPx(), center = center)
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
