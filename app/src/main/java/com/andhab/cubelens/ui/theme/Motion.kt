package com.andhab.cubelens.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Shared motion vocabulary: springy and confident (see docs/DESIGN.md, "Motion").
 * Use these instead of ad-hoc specs so presses, selections and entrances feel like one product.
 */
object CubeLensMotion {
    /** Scale applied to buttons while pressed. */
    const val PressedScale = 0.96f

    /** Delay between consecutive items of a staggered entrance. */
    const val StaggerMillis = 50L

    /** Press / release feedback on tappable surfaces. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)

    /** Selection changes (tabs, step dots, toggles): slightly bouncy. */
    fun <T> select(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

    /** Items appearing on screen: soft, no visible overshoot. */
    fun <T> enter(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
}
