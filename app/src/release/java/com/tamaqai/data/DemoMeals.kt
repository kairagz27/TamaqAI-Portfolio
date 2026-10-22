package com.tamaqai.data

import com.tamaqai.domain.model.Meal

object DemoMeals {
    const val available = false
    fun fill(meal: Meal, text: (Int) -> String): Meal = error("Demo is forbidden in release")
}
