package com.tamaqai.data.repository

import com.tamaqai.data.local.dao.DailyDao
import com.tamaqai.data.local.entity.*
import com.tamaqai.domain.model.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.time.LocalDate

class DailyRepository(private val dao: DailyDao, private val owner: String = "guest") {
    val favorites = dao.favorites(owner).map { rows -> rows.map { FavoriteMeal(it.id,
        Nutrition(it.name, it.calories, it.protein, it.fat, it.carbs),
        Json.decodeFromString<MealDetails>(it.detailsJson), MealType.valueOf(it.mealType), NutritionSource.valueOf(it.source)) } }
    val weights = dao.weights(owner).map { rows -> rows.map { WeightEntry(it.id, LocalDate.parse(it.date), it.kilograms) } }
    suspend fun favorite(meal: Meal): String {
        require(meal.status == MealStatus.Confirmed)
        val id = java.util.UUID.randomUUID().toString()
        val n = meal.nutrition
        dao.insertFavorite(FavoriteEntity(id, n.name, n.calories, n.protein, n.fat, n.carbs,
            Json.encodeToString(meal.details.copy(photoName = null, analysisId = null)), meal.mealType.name, meal.source.name,owner))
        return id
    }
    suspend fun removeFavorite(id: String) = dao.deleteFavorite(id,owner)
    suspend fun saveWeight(value: WeightEntry) {
        value.validate()
        dao.saveWeight(WeightEntity(value.id, value.date.toString(), value.kilograms,owner))
    }
    suspend fun deleteWeight(id: String) = dao.deleteWeight(id,owner)
    suspend fun clearFavorites() = dao.clearFavorites(owner)
    suspend fun clearWeights() = dao.clearWeights(owner)
    suspend fun clearLocalRecords() = dao.clearLocalRecords(owner)
}
