package com.andhab.cubelens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.ui.theme.Brand

/**
 * Dark-glass card: translucent surface over the aurora, a hairline border lit from the top and
 * 20dp corners. The default container for grouped content.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .glassSurface(GlassCardShape)
            .padding(contentPadding),
        content = content,
    )
}

/** Tone of a [StatusBanner]. */
enum class BannerKind { Success, Warning, Error, Info }

/** Accent color of a banner kind: mint, amber, danger red, or neutral light for tips. */
val BannerKind.accent: Color
    get() = when (this) {
        BannerKind.Success -> Brand.Mint
        BannerKind.Warning -> Brand.Amber
        BannerKind.Error -> Brand.Danger
        BannerKind.Info -> Brand.TextPrimary
    }

private val BannerKind.icon: ImageVector
    get() = when (this) {
        BannerKind.Success -> Icons.Rounded.CheckCircle
        BannerKind.Warning -> Icons.Rounded.WarningAmber
        BannerKind.Error -> Icons.Rounded.ErrorOutline
        BannerKind.Info -> Icons.Rounded.Lightbulb
    }

/**
 * Inline status message ("Looks good, let's solve it", "Two pieces look swapped"). A full-width
 * glass strip tinted with the [kind]'s accent, with a glowing icon badge. Announced politely by
 * screen readers when it appears or changes. Constrain it with a width modifier if needed.
 */
@Composable
fun StatusBanner(
    kind: BannerKind,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val accent = kind.accent
    val tint = if (kind == BannerKind.Info) 0.06f else 0.13f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .glassSurface(
                shape = GlassCardShape,
                fill = Brand.Glass,
                border = Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.14f)),
                ),
            )
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = tint), accent.copy(alpha = tint * 0.25f)),
                ),
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = if (message == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .softGlow(accent, alpha = 0.22f, spread = 8.dp, offsetY = 0.dp)
                .background(accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(kind.icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.padding(top = if (message == null) 0.dp else 1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.TextPrimary)
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
            }
        }
    }
}
