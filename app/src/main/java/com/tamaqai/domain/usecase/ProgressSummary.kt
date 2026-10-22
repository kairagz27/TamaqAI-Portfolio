package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.Meal
import java.time.LocalDate

data class DayConsumption(val date: LocalDate, val calories: Int, val protein: Int,
    val incompleteNutritionCount: Int = 0)

object ProgressSummary {
    /** Only days with at least one confirmed known meal are observations. Unknown-only days are gaps. */
    fun observedDays(meals: List<Meal>, start: LocalDate, end: LocalDate): List<DayConsumption> {
        require(!end.isBefore(start))
        val incomplete = DiaryTotals.incompleteCatalogEntries(meals).groupingBy { DiaryTotals.dateOf(it) }.eachCount()
        return meals.filter(DiaryTotals::isCounted)
            .groupBy { DiaryTotals.dateOf(it) }
            .filterKeys { !it.isBefore(start) && !it.isAfter(end) }.toSortedMap()
            .map { (day, entries) -> DayConsumption(day, entries.sumOf { it.nutrition.calories },
                entries.sumOf { it.nutrition.protein }, incomplete[day] ?: 0) }
    }
}
