package com.tamaqai.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import com.tamaqai.domain.usecase.NutritionCalculator

@Serializable
data class AnalysisComponent(val name: String, @SerialName("mass_grams") val massGrams: Double?,
    val calories: Double, val protein: Double, val fat: Double, val carbs: Double) {
    fun toDomain() = MealComponent(name, massGrams, calories, protein, fat, carbs)
}
@Serializable
data class FoodResult(val status: String, val name: String, val components: List<AnalysisComponent>,
    val explanation: String?, val question: String?)
@Serializable
data class AnalysisResult(@SerialName("analysis_id") val analysisId: String,
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("prompt_version") val promptVersion: String, val model: String,
    @SerialName("is_demo") val isDemo: Boolean, val result: FoodResult) {
    fun validate(expectedId: String) {
        require(analysisId == expectedId && schemaVersion == "1" && promptVersion.isNotBlank() && model.isNotBlank())
        require(result.status in listOf("food", "no_food", "ambiguous"))
        require(result.name.isNotBlank() && result.name.length <= 120)
        require(result.components.size <= 20)
        require(result.explanation == null || result.explanation.length <= 600)
        require(result.question == null || result.question.length <= 600)
        if (result.status == "no_food") require(result.components.isEmpty())
        if (result.status == "ambiguous") require(!result.question.isNullOrBlank())
        if (result.status == "food" || result.components.isNotEmpty()) {
            NutritionCalculator().calculate(result.name, result.components.map { it.toDomain() }, 1.0)
        }
    }
}
