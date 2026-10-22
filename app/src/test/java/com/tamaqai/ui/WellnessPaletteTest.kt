package com.tamaqai.ui

import androidx.compose.ui.graphics.Color
import com.tamaqai.ui.theme.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/** Fixed warmth and readable foregrounds are independent of wallpaper / dynamic color. */
class WellnessPaletteTest {
    @Test fun requestedWellnessAnchorsArePreserved() {
        assertEquals(Color(0xFFF7F2EA), LightPalette.PageBackground)
        assertEquals(Color(0xFF211C18), DarkPalette.PageBackground)
        assertEquals(Color(0xFFF4EBDD), DarkPalette.TextPrimary)
    }

    @Test fun surfacesAndMaterialAccentRolesRemainWarm() {
        for ((palette, dark) in listOf(LightPalette to false, DarkPalette to true)) {
            val colors = wellnessColorScheme(palette, dark)
            listOf(palette.PageBackground, palette.CardSurface, palette.SecondarySurface,
                palette.SelectedSurface, palette.DisabledSurface, palette.BorderSoft,
                palette.ChartTrack, colors.primary, colors.secondary, colors.tertiary,
                colors.surface, colors.surfaceVariant, colors.surfaceContainerLowest,
                colors.surfaceContainerLow, colors.surfaceContainer,
                colors.surfaceContainerHigh, colors.surfaceContainerHighest,
                colors.surfaceBright, colors.surfaceDim).forEach { color ->
                assertTrue("Warm colors cannot acquire an olive tint: $color",
                    color.red >= color.green && color.green >= color.blue)
            }
            assertEquals(Color.Transparent, colors.surfaceTint)
        }
    }

    @Test fun textAndStateColorsKeepNormalTextContrast() {
        for (p in listOf(LightPalette, DarkPalette)) {
            for (surface in listOf(p.PageBackground, p.CardSurface, p.SecondarySurface,
                p.SelectedSurface, p.DisabledSurface)) {
                assertContrast(p.TextPrimary, surface, 4.5)
                assertContrast(p.TextSecondary, surface, 4.5)
            }
            for (color in listOf(p.AccentBrown, p.ErrorColor, p.WarningColor,
                p.SuccessColor, p.ProteinColor, p.FatColor, p.CarbColor)) {
                assertContrast(color, p.PageBackground, 4.5)
                assertContrast(color, p.CardSurface, 4.5)
            }
            assertContrast(p.ControlOutline, p.CardSurface, 3.0)
        }
    }

    @Test fun buttonsAndErrorRolesKeepReadableLabels() {
        for ((p, dark) in listOf(LightPalette to false, DarkPalette to true)) {
            val colors = wellnessColorScheme(p, dark)
            assertContrast(colors.onPrimary, colors.primary, 4.5)
            assertContrast(colors.onSecondary, colors.secondary, 4.5)
            assertContrast(colors.onTertiary, colors.tertiary, 4.5)
            assertContrast(colors.onError, colors.error, 4.5)
            assertContrast(colors.onErrorContainer, colors.errorContainer, 4.5)
            assertNotEquals(p.ErrorColor, p.SuccessColor)
            assertNotEquals(p.ProteinColor, p.FatColor)
            assertNotEquals(p.FatColor, p.CarbColor)
        }
    }

    @Test fun translucentMaterialsKeepAReadableDensityHierarchy() {
        assertTrue(WellnessGlass.cardOpacity < WellnessGlass.navigationOpacity)
        assertTrue(WellnessGlass.navigationOpacity < WellnessGlass.panelOpacity)
        assertTrue(WellnessGlass.panelOpacity < WellnessGlass.fieldOpacity)
        assertTrue(WellnessGlass.darkCardOpacity < WellnessGlass.darkNavigationOpacity)
        assertTrue(WellnessGlass.darkNavigationOpacity < WellnessGlass.darkPanelOpacity)
        assertTrue(WellnessGlass.darkPanelOpacity < WellnessGlass.darkFieldOpacity)
        for (opacity in listOf(WellnessGlass.cardOpacity, WellnessGlass.darkCardOpacity,
            WellnessGlass.navigationOpacity, WellnessGlass.darkNavigationOpacity,
            WellnessGlass.panelOpacity, WellnessGlass.darkPanelOpacity,
            WellnessGlass.fieldOpacity, WellnessGlass.darkFieldOpacity)) {
            assertTrue(opacity > 0f && opacity < 1f)
        }
    }

    @Test fun navigationPanelsAndFieldsKeepNormalTextContrastOverPhotoExtremes() {
        for ((p, dark) in listOf(LightPalette to false, DarkPalette to true)) {
            val opacities = if (dark) listOf(WellnessGlass.darkNavigationOpacity, WellnessGlass.darkPanelOpacity, WellnessGlass.darkFieldOpacity)
                else listOf(WellnessGlass.navigationOpacity, WellnessGlass.panelOpacity, WellnessGlass.fieldOpacity)
            for (opacity in opacities) {
                for (background in listOf(Color.Black, Color.White)) {
                    val filled = Color(
                        p.CardSurface.red * opacity + background.red * (1f - opacity),
                        p.CardSurface.green * opacity + background.green * (1f - opacity),
                        p.CardSurface.blue * opacity + background.blue * (1f - opacity),
                    )
                    assertContrast(p.TextPrimary, filled, 4.5)
                    assertContrast(p.TextSecondary, filled, 4.5)
                }
            }
        }
    }

    @Test fun drawerNestedWarmLayersRetainTextAndIconContrast() {
        for ((p, dark) in listOf(LightPalette to false, DarkPalette to true)) {
            val opacity = if (dark) WellnessGlass.darkPanelOpacity else WellnessGlass.panelOpacity
            for (photo in listOf(Color.Black, Color.White)) {
                val panel = composite(p.CardSurface, photo, opacity)
                assertContrast(p.TextSecondary, composite(p.SecondarySurface, panel, .82f), 4.5)
                for (iconFill in listOf(p.Pistachio, p.Apricot, p.ProteinPastel)) {
                    assertContrast(PastelContent, composite(iconFill, panel, .55f), 3.0)
                }
            }
        }
    }

    @Test fun cameraLabelsStayReadableOverPhotoExtremes() {
        for (photo in listOf(Color.Black, Color.White)) {
            for (opacity in listOf(.94f, .96f)) {
                assertContrast(CameraContent, composite(CameraSurface, photo, opacity), 4.5)
            }
        }
    }

    private fun composite(tint: Color, background: Color, opacity: Float) = Color(
        tint.red * opacity + background.red * (1f - opacity),
        tint.green * opacity + background.green * (1f - opacity),
        tint.blue * opacity + background.blue * (1f - opacity),
    )

    private fun assertContrast(foreground: Color, background: Color, minimum: Double) {
        val high = maxOf(luminance(foreground), luminance(background))
        val low = minOf(luminance(foreground), luminance(background))
        assertTrue("Contrast $foreground on $background is below $minimum: ${(high + .05) / (low + .05)}",
            (high + .05) / (low + .05) >= minimum)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = channel.toDouble().let {
            if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }
}
