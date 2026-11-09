package com.tamaqai.ui

import com.tamaqai.ui.components.WeightCurve
import org.junit.Assert.*
import org.junit.Test

class WeightCurveTest {
    @Test fun filtersInvalidAndOutOfRangeValuesSortsAndKeepsLastDuplicate() {
        val result = WeightCurve.points(listOf(8L to 70.0, 2L to 75.0, 2L to 74.0,
            3L to Double.NaN, 4L to Double.POSITIVE_INFINITY, 12L to 72.0), 1, 10)
        assertEquals(listOf(WeightCurve.Point(2, 74.0), WeightCurve.Point(8, 70.0)), result)
    }
    @Test fun emptyAndSingleObservationsHaveNoInventedLine() {
        assertTrue(WeightCurve.segments(emptyList()).isEmpty())
        assertTrue(WeightCurve.segments(listOf(WeightCurve.Point(2, 72.0))).isEmpty())
    }
    @Test fun gapsRemainDateBasedAndAreConnected() {
        val points = WeightCurve.points(listOf(1L to 80.0, 12L to 78.0, 30L to 77.0), 1, 30)
        val segments = WeightCurve.segments(points)
        assertEquals(2, segments.size)
        assertEquals(11, (segments[0].to.day - segments[0].from.day).toInt())
        assertEquals(18, (segments[1].to.day - segments[1].from.day).toInt())
    }
    @Test fun curveDoesNotOvershootUnevenRisingFallingOrFlatMeasurements() {
        val points = WeightCurve.points(listOf(1L to 80.0, 2L to 78.0, 20L to 79.0,
            21L to 79.0, 22L to 70.0, 100L to 71.0), 1, 100)
        WeightCurve.segments(points).forEach { segment ->
            val low = minOf(segment.from.value, segment.to.value)
            val high = maxOf(segment.from.value, segment.to.value)
            for (step in 0..100) {
                val t = step / 100.0; val u = 1 - t
                val value = u*u*u*segment.from.value + 3*u*u*t*segment.firstControlValue +
                    3*u*t*t*segment.secondControlValue + t*t*t*segment.to.value
                assertTrue("$value outside [$low, $high]", value >= low - 1e-8 && value <= high + 1e-8)
            }
        }
    }
    @Test fun adjacentSegmentsHaveMatchingTangents() {
        val segments = WeightCurve.segments(WeightCurve.points(listOf(1L to 80.0, 3L to 79.0, 9L to 77.0), 1, 9))
        val left = segments[0]; val right = segments[1]
        val outgoing = 3 * (left.to.value - left.secondControlValue) / (left.to.day - left.from.day)
        val incoming = 3 * (right.firstControlValue - right.from.value) / (right.to.day - right.from.day)
        assertEquals(outgoing, incoming, 1e-9)
    }
}
