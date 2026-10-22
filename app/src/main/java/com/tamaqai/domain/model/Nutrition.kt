package com.tamaqai.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Nutrition(
    val name: String,
    val calories: Int,
    val protein: Int,
    val fat: Int,
    val carbs: Int,
) {
    init {
        require(name.isNotBlank()) { "Meal name must not be blank." }
        require(calories in 0..10_000) { "Calories are outside the supported range." }
        require(protein in 0..1_000) { "Protein is outside the supported range." }
        require(fat in 0..1_000) { "Fat is outside the supported range." }
        require(carbs in 0..1_000) { "Carbohydrates are outside the supported range." }
    }
}
