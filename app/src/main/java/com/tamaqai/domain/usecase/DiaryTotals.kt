package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.Meal
import com.tamaqai.domain.model.MealStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object DiaryTotals {
    /** Unknown catalogue values are never a zero-valued confirmed observation. */
    fun isCounted(meal: Meal) = meal.status == MealStatus.Confirmed && !meal.details.catalogPendingManual

    fun forDate(meals: List<Meal>, date: LocalDate, fallbackZone: ZoneId = ZoneId.systemDefault()) =
        meals.filter { isCounted(it) && dateOf(it, fallbackZone) == date }

    /**
     * A new catalogue draft with unpublished nutrients leaves this day's intake incomplete.
     * An editing draft does not replace its confirmed snapshot until the user confirms it.
     */
    fun incompleteCatalogEntries(meals: List<Meal>): List<Meal> {
        val confirmedIds = meals.filter { it.status == MealStatus.Confirmed }.mapTo(mutableSetOf(), Meal::uid)
        return meals.filter { it.details.catalogPendingManual && it.originalUid?.let(confirmedIds::contains) != true }
    }

    fun incompleteCountForDate(meals: List<Meal>, date: LocalDate,
        fallbackZone: ZoneId = ZoneId.systemDefault()) =
        incompleteCatalogEntries(meals).count { dateOf(it, fallbackZone) == date }

    fun dateOf(meal: Meal, fallbackZone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(meal.timestamp).atZone(meal.zoneId?.let(ZoneId::of) ?: fallbackZone).toLocalDate()
}
