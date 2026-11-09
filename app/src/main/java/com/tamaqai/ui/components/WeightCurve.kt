package com.tamaqai.ui.components

import kotlin.math.abs
import kotlin.math.min

/** Display-only interpolation: no synthetic diary measurements are created. */
object WeightCurve {
    data class Point(val day: Long, val value: Double)
    data class Segment(val from: Point, val to: Point, val firstControlValue: Double, val secondControlValue: Double)

    fun points(values: List<Pair<Long, Double>>, start: Long, end: Long): List<Point> =
        values.filter { it.first in start..end && it.second.isFinite() }
            .associate { it.first to it.second }.toSortedMap().map { Point(it.key, it.value) }

    /** Monotone cubic Hermite interpolation with bounded controls: never invents extrema. */
    fun segments(points: List<Point>): List<Segment> {
        if (points.size < 2) return emptyList()
        require(points.zipWithNext().all { (a, b) -> b.day > a.day })
        val slopes = points.zipWithNext().map { (a, b) -> (b.value - a.value) / (b.day - a.day) }
        val tangents = DoubleArray(points.size)
        tangents[0] = slopes.first()
        tangents[tangents.lastIndex] = slopes.last()
        for (i in 1 until points.lastIndex) {
            val before = slopes[i - 1]
            val after = slopes[i]
            tangents[i] = if (before * after <= 0.0) 0.0 else {
                val left = (points[i].day - points[i - 1].day).toDouble()
                val right = (points[i + 1].day - points[i].day).toDouble()
                val a = 2 * right + left
                val b = right + 2 * left
                (a + b) / (a / before + b / after)
            }
        }
        // Controls stay within each pair of observations, including uneven date intervals.
        slopes.forEachIndexed { i, slope ->
            if (slope == 0.0) { tangents[i] = 0.0; tangents[i + 1] = 0.0 }
            else {
                val limit = 3 * abs(slope)
                tangents[i] = Math.copySign(min(abs(tangents[i]), limit), tangents[i])
                tangents[i + 1] = Math.copySign(min(abs(tangents[i + 1]), limit), tangents[i + 1])
            }
        }
        return points.zipWithNext().mapIndexed { i, (a, b) ->
            val third = (b.day - a.day) / 3.0
            Segment(a, b, a.value + tangents[i] * third, b.value - tangents[i + 1] * third)
        }
    }
}
