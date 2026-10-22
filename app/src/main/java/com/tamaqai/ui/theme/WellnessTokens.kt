package com.tamaqai.ui.theme

import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

/** Shared geometry and motion. Compose honors the system animator duration scale. */
object WellnessSpace {
    val page = 20.dp
    val small = 8.dp
    val compact = 12.dp
    val gap = 16.dp
    val section = 24.dp
    val large = 32.dp
    val touch = 48.dp
    val card = 28.dp
    val control = 18.dp
    val macroCard = 22.dp
    val previewCard = 24.dp
    val calorieRing = 172.dp
    val calorieStroke = 11.dp
    val drawerMaximum = 360.dp
    const val drawerFraction = .88f
}

object WellnessMotion {
    const val press = 180
    const val exit = 180
    const val enter = 240
    const val indicator = 260
    fun <T> standard() = tween<T>(enter)
}

object WellnessGlass {
    const val minimumBlurMemoryBytes = 2L * 1024 * 1024 * 1024
    // Cards share the warm page source. Navigation, panels and fields also stay readable
    // over a photo, so their tint is deliberately denser than a diary card.
    const val cardOpacity = .68f
    const val darkCardOpacity = .74f
    const val navigationOpacity = .86f
    const val darkNavigationOpacity = .86f
    const val panelOpacity = .90f
    const val darkPanelOpacity = .90f
    const val fieldOpacity = .94f
    const val darkFieldOpacity = .94f
    const val scrimOpacity = .16f
    const val shadowOpacity = .07f
    val blur = 24.dp
    val shadow = 6.dp
}

/**
 * Match Haze 1.5.4: API 31 uses its solid fallback because of RenderNode invalidation
 * issues. Do not allocate a source layer there, on software canvases or small-memory devices.
 */
fun supportsWellnessBlur(api:Int,hardwareAccelerated:Boolean,lowRam:Boolean,totalMemoryBytes:Long):Boolean =
    api>=32 && hardwareAccelerated && !lowRam && totalMemoryBytes>=WellnessGlass.minimumBlurMemoryBytes
