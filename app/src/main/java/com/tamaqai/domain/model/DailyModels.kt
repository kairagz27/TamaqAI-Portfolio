package com.tamaqai.domain.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class MealType { Breakfast, Lunch, Dinner, Snack, Other }

data class DailyGoals(val calories: Int? = null, val protein: Int? = null,
    val fat: Int? = null, val carbs: Int? = null) {
    fun validate() {
        require(calories == null || calories in 1..10000)
        require(listOf(protein, fat, carbs).all { it == null || it in 1..1000 })
    }
    val isEmpty get() = listOf(calories, protein, fat, carbs).all { it == null }
}

data class WeightEntry(val id: String = UUID.randomUUID().toString(), val date: LocalDate, val kilograms: Double) {
    fun validate(today: LocalDate = LocalDate.now()) {
        require(kilograms.isFinite() && kilograms in 1.0..500.0)
        require(!date.isAfter(today)) { "weight_date_error" }
    }
}

data class FavoriteMeal(val id: String = UUID.randomUUID().toString(), val nutrition: Nutrition,
    val details: MealDetails, val type: MealType, val source: NutritionSource)

object MealCopies {
    fun onDate(meal: Meal, date: LocalDate): Meal {
        require(meal.status == MealStatus.Confirmed)
        val zone = ZoneId.systemDefault()
        return meal.copy(id = 0, uid = UUID.randomUUID().toString(), originalUid = null,
            timestamp = date.atTime(LocalTime.now()).atZone(zone).toInstant().toEpochMilli(),
            zoneId = zone.id, status = MealStatus.NeedsReview,
            details = meal.details.copy(photoName = null, analysisId = null))
    }
    fun fromFavorite(favorite: FavoriteMeal, date: LocalDate) = onDate(Meal(nutrition = favorite.nutrition,
        details = favorite.details, status = MealStatus.Confirmed, source = favorite.source,
        mealType = favorite.type), date)
}
