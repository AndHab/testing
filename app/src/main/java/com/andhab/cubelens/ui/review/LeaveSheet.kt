package com.andhab.cubelens.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.ui.components.HeroCardShape
import com.andhab.cubelens.ui.components.PrimaryButton
import com.andhab.cubelens.ui.components.SecondaryButton
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.glassSurface
import com.andhab.cubelens.ui.theme.Brand

/**
 * "Leave this cube?": a sheet that rises from the bottom over a dimmed screen when the user backs
 * out of a review that has work in it. Keeping on editing is the prominent choice; tapping the dim
 * area keeps editing too.
 *
 * Fills its [BoxScope] parent; place it last so it draws over the screen.
 */
@Composable
internal fun BoxScope.LeaveSheet(
    visible: Boolean,
    source: ReviewSource,
    onLeave: () -> Unit,
    onStay: () -> Unit,
) {
    val stay = stringResource(R.string.review_leave_stay)
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
        label = "leaveScrim",
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brand.Ink.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stay,
                    onClick = onStay,
                ),
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(220)) { it / 2 } + fadeOut(tween(160)),
        label = "leaveSheet",
    ) {
        val title = stringResource(R.string.review_leave_title)
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
                .fillMaxWidth()
                .drawBehind {
                    // Warm light spilling from behind the sheet's top edge.
                    drawSoftGlow(Brand.Magenta, alpha = 0.16f, center = Offset(size.width / 2f, 0f), radiusX = size.width * 0.6f, radiusY = size.height * 0.5f)
                }
                .glassSurface(HeroCardShape, fill = Brand.SurfaceHigh)
                .semantics { paneTitle = title }
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        Brush.linearGradient(listOf(Brand.Amber.copy(alpha = 0.26f), Brand.Tangerine.copy(alpha = 0.14f))),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Logout,
                    contentDescription = null,
                    tint = Brand.Amber,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Brand.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (source == ReviewSource.Scan) R.string.review_leave_message_scan else R.string.review_leave_message_manual,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.TextSecondary,
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = stay,
                onClick = onStay,
                icon = Icons.Rounded.Edit,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                text = stringResource(R.string.review_leave_confirm),
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
