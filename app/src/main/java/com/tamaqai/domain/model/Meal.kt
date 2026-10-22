package com.tamaqai.domain.model

data class Meal(
    val id: Long = 0,
    val uid: String = java.util.UUID.randomUUID().toString(),
    val nutrition: Nutrition,
    val timestamp: Long = System.currentTimeMillis(),
    val zoneId: String? = java.time.ZoneId.systemDefault().id,
    val status: MealStatus = MealStatus.Draft,
    val source: NutritionSource = NutritionSource.Manual,
    val details: MealDetails,
    val originalUid: String? = null,
    val mealType: MealType = MealType.Other,
)
