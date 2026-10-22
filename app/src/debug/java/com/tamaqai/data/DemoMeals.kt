package com.tamaqai.data

import com.tamaqai.domain.model.*
import com.tamaqai.R

object DemoMeals {
    const val available = true
    fun fill(meal: Meal, text: (Int) -> String): Meal = meal.copy(
        nutrition = Nutrition(text(R.string.demo_meal_name), 120, 5, 4, 16), source = NutritionSource.Demo,
        status = MealStatus.NeedsReview,
        details = meal.details.copy(components = listOf(MealComponent(text(R.string.demo_component_name), 100.0, 120.0, 5.0, 4.0, 16.0)),
            model = "FAKE-DEVELOPMENT", explanation = text(R.string.demo_explanation), question = null),
    )
}
