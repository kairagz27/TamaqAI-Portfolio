package com.tamaqai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.tamaqai.ui.screens.ChartReveal
import com.tamaqai.ui.screens.DataChart
import com.tamaqai.ui.theme.TamaqTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Component-only clock checks: no navigation, database, network or system-setting changes. */
class WeightChartAnimationTest {
    @get:Rule val rule = createComposeRule()
    private val start = LocalDate.of(2026, 1, 1)
    private val points = listOf(start.toEpochDay() to 80.0,
        start.plusDays(9).toEpochDay() to 78.5, start.plusDays(29).toEpochDay() to 77.0)
    private fun reveal() = rule.onNodeWithTag("weight-chart").fetchSemanticsNode().config[ChartReveal]

    @Test fun manualClockRevealsPartialThenCompleteCurve() {
        rule.mainClock.autoAdvance = false
        rule.setContent { TamaqTheme(darkTheme = false) {
            Column { DataChart(points, start, start.plusDays(29), false) }
        } }
        rule.mainClock.advanceTimeBy(400)
        val partial = reveal()
        assertTrue("Expected a partially drawn curve, got $partial", partial > 0f && partial < 1f)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(1f, reveal(), .0001f)
    }

    @Test fun zeroDurationScaleCompletesWithoutChangingGlobalSettings() {
        rule.mainClock.autoAdvance = false
        val reducedMotion = object : MotionDurationScale { override val scaleFactor = 0f }
        rule.setContent { TamaqTheme(darkTheme = false) {
            Column { DataChart(points, start, start.plusDays(29), false, animationContext = reducedMotion) }
        } }
        rule.mainClock.advanceTimeBy(64)
        assertEquals(1f, reveal(), .0001f)
    }
}
