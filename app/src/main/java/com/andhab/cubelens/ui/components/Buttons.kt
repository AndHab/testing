package com.andhab.cubelens.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
import com.andhab.cubelens.ui.theme.BodyFont
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion

/** Height of [PrimaryButton] and [SecondaryButton]. */
val ButtonHeight: Dp = 58.dp

private val ButtonTextStyle = TextStyle(
    fontFamily = BodyFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.01.em,
)

/** Muted, desaturated take on the sunset for disabled primary buttons. */
private val DisabledSunsetBrush = Brush.horizontalGradient(
    listOf(Color(0xFF2E2329), Color(0xFF2F2724), Color(0xFF2F2B22)),
)

private val DisabledLabelColor = Color(0xFF7D7688)

/**
 * The main call to action: a glowing sunset-gradient pill with a glossy top sheen and a
 * magenta/tangerine glow pooled underneath. Springs down to 96% while pressed and gives a confirm
 * haptic on click. Use at most one per screen.
 *
 * Wraps its label by default; pass `Modifier.fillMaxWidth()` for a full-width button.
 *
 * @param enabled when false the pill turns desaturated, loses its glow and ignores taps.
 * @param loading shows a spinner in place of the label (keeping the button's size) and ignores taps.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val interactive = enabled && !loading
    val scale by animateFloatAsState(
        targetValue = if (pressed && interactive) CubeLensMotion.PressedScale else 1f,
        animationSpec = CubeLensMotion.press(),
        label = "primaryScale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            pressed -> 0.35f
            else -> 0.6f
        },
        animationSpec = CubeLensMotion.press(),
        label = "primaryGlow",
    )
    val loadingProgress by animateFloatAsState(
        targetValue = if (loading) 1f else 0f,
        animationSpec = CubeLensMotion.press(),
        label = "primaryLoading",
    )
    val haptics = LocalHapticFeedback.current
    val loadingDescription = stringResource(R.string.state_loading)
    val labelColor = if (enabled) Brand.OnAccent else DisabledLabelColor

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .sunsetGlow(alpha = glowAlpha)
            .defaultMinSize(minWidth = 160.dp)
            .heightIn(min = ButtonHeight)
            .clip(CircleShape)
            .background(if (enabled) Brand.SunsetHorizontalBrush else DisabledSunsetBrush)
            .topSheen(alpha = if (enabled) 0.26f else 0.05f)
            .drawBehind { if (pressed && interactive) drawRect(Brand.OnAccent.copy(alpha = 0.08f)) }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = interactive,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .semantics { if (loading) stateDescription = loadingDescription }
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The label stays laid out while loading so the button never changes size.
        ButtonLabel(
            text = text,
            icon = icon,
            color = labelColor,
            modifier = Modifier.graphicsLayer { alpha = 1f - loadingProgress },
        )
        if (loadingProgress > 0f) {
            Spinner(
                color = Brand.OnAccent,
                size = 24.dp,
                strokeWidth = 2.75.dp,
                modifier = Modifier.graphicsLayer {
                    alpha = loadingProgress
                    scaleX = 0.6f + 0.4f * loadingProgress
                    scaleY = 0.6f + 0.4f * loadingProgress
                },
            )
        }
    }
}

/**
 * A quieter companion to [PrimaryButton]: a dark-glass pill with a hairline border, for the
 * alternative path ("Enter colors manually", "Scan again").
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) CubeLensMotion.PressedScale else 1f,
        animationSpec = CubeLensMotion.press(),
        label = "secondaryScale",
    )
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.42f
            }
            .defaultMinSize(minWidth = 120.dp)
            .heightIn(min = ButtonHeight)
            .glassSurface(
                shape = CircleShape,
                fill = if (pressed) Brand.GlassHigh else Brand.Glass,
                border = SecondaryBorderBrush,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonLabel(text = text, icon = icon, color = Brand.TextPrimary)
    }
}

private val SecondaryBorderBrush = Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x14FFFFFF)))

/**
 * Lowest-emphasis action ("Skip", "How does this work?"): label only, no container. Brightens from
 * secondary to primary text color while pressed. At least 48dp tall.
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val color by animateColorAsState(
        targetValue = if (pressed) Brand.TextPrimary else Brand.TextSecondary,
        animationSpec = CubeLensMotion.press(),
        label = "textActionColor",
    )
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text,
            style = ButtonTextStyle.copy(fontSize = 15.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Round icon-only button. Dark glass by default; [highlighted] turns it into a glowing sunset disc
 * (e.g. the shutter or an active toggle). Always offers a 48dp touch target even when [size] is
 * smaller.
 *
 * @param contentDescription spoken label; required unless a neighbouring text already says it.
 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = CubeLensMotion.press(),
        label = "circleScale",
    )
    val surface = if (highlighted) {
        Modifier
            .sunsetGlow(alpha = if (enabled) 0.45f else 0f, spread = size * 0.2f, offsetY = size * 0.08f)
            .clip(CircleShape)
            .background(Brand.SunsetBrush)
            .topSheen(alpha = 0.24f)
    } else {
        Modifier.glassSurface(
            shape = CircleShape,
            fill = if (pressed) Brand.GlassHigh else Brand.Glass,
            border = SecondaryBorderBrush,
        )
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .then(surface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) Brand.OnAccent else Brand.TextPrimary,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

@Composable
private fun ButtonLabel(
    text: String,
    icon: ImageVector?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        }
        Text(
            text = text,
            style = ButtonTextStyle,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
