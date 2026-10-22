package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.MealComponent
import com.tamaqai.domain.model.Nutrition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Boundary for a future verified food catalogue. Current values are estimates or user input. */
class NutritionCalculator {
    fun calculate(name: String, components: List<MealComponent>, portion: Double): Nutrition {
        require(name.isNotBlank() && name.length <= 120) { "ui_error_035" }
        require(portion.isFinite() && portion in 0.05..20.0) { "ui_error_007" }
        require(components.size in 1..20) { "ui_error_036" }
        components.forEach {
            val factor = it.consumedPortion ?: portion
            require(factor.isFinite() && factor in 0.0..20.0) { "ui_error_007" }
            require(it.name.isNotBlank() && it.name.length <= 120) { "ui_error_037" }
            require(it.massGrams == null || (it.massGrams.isFinite() && it.massGrams in 0.001..10000.0 && it.massGrams * factor <= 10000)) { "ui_error_038" }
            require(listOf(it.calories, it.protein, it.fat, it.carbs).all { v -> v.isFinite() && v >= 0 }) { "ui_error_039" }
            require(it.calories <= 10000 && maxOf(it.protein, it.fat, it.carbs) <= 1000) { "ui_error_040" }
        }
        require(components.sumOf { (it.massGrams ?: 0.0) * (it.consumedPortion ?: portion) } <= 10000) { "ui_error_041" }
        val calories = components.sumOf { it.calories * (it.consumedPortion ?: portion) }
        val protein = components.sumOf { it.protein * (it.consumedPortion ?: portion) }
        val fat = components.sumOf { it.fat * (it.consumedPortion ?: portion) }
        val carbs = components.sumOf { it.carbs * (it.consumedPortion ?: portion) }
        require(calories <= 10000 && maxOf(protein, fat, carbs) <= 1000) { "ui_error_042" }
        return Nutrition(name.trim(), calories.roundToInt(), protein.roundToInt(), fat.roundToInt(), carbs.roundToInt())
    }

    fun hasEnergyWarning(nutrition: Nutrition): Boolean {
        val macroEnergy = nutrition.protein * 4 + nutrition.fat * 9 + nutrition.carbs * 4
        return abs(nutrition.calories - macroEnergy) > max(50.0, nutrition.calories * 0.25)
    }
}
