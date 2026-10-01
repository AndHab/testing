package com.andhab.cubelens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.ui.theme.Brand

/**
 * Transparent app bar that floats over the aurora: a glass back button on the left, an optional
 * centered [title] and trailing [actions] (typically [CircleIconButton]s). Pads itself below the
 * status bar, so screens drawn edge to edge can place it at the very top.
 *
 * The title stays centered on the bar and is given the width left between the wider of the two
 * side slots, so it never runs under the back button or the actions; long titles end in an
 * ellipsis.
 *
 * @param onBack shows the back button when non-null.
 */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(horizontal = 12.dp),
        content = {
            Box(Modifier.layoutId(TopBarSlot.Navigation)) {
                if (onBack != null) {
                    CircleIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        onClick = onBack,
                        size = 44.dp,
                    )
                }
            }
            Box(Modifier.layoutId(TopBarSlot.Title)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Brand.TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            Row(
                modifier = Modifier.layoutId(TopBarSlot.Actions),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val navigation = measurables.first { it.layoutId == TopBarSlot.Navigation }.measure(loose)
        val actionRow = measurables.first { it.layoutId == TopBarSlot.Actions }.measure(loose)
        val side = maxOf(navigation.width, actionRow.width)
        val reserved = if (side > 0) 2 * (side + TitleGap.roundToPx()) else 0
        val titleMaxWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - reserved).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        val titleText = measurables.first { it.layoutId == TopBarSlot.Title }
            .measure(loose.copy(maxWidth = titleMaxWidth))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else reserved + titleText.width
        val height = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            maxOf(navigation.height, actionRow.height, titleText.height)
        }
        layout(width, height) {
            navigation.placeRelative(0, (height - navigation.height) / 2)
            titleText.placeRelative((width - titleText.width) / 2, (height - titleText.height) / 2)
            actionRow.placeRelative(width - actionRow.width, (height - actionRow.height) / 2)
        }
    }
}

private enum class TopBarSlot { Navigation, Title, Actions }

/** Minimum space between the centered title and either side slot. */
private val TitleGap = 8.dp
