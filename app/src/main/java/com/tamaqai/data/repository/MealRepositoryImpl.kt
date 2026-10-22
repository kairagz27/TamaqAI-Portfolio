package com.tamaqai.data.repository

import com.tamaqai.data.local.dao.MealDao
import com.tamaqai.data.local.entity.MealEntity
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.NutritionCalculator
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

class MealRepositoryImpl(private val mealDao: MealDao, private val owner: String = "guest") : MealRepository {
    private val json = Json
    private val calculator = NutritionCalculator()
    override fun getAllMeals() = mealDao.getAllMeals(owner).map { rows -> rows.map { it.toDomain() } }
    override suspend fun getMeal(uid: String) = mealDao.get(uid,owner)?.toDomain()
    override suspend fun saveDraft(meal: Meal) {
        require(meal.status != MealStatus.Confirmed) { "ui_error_049" }
        mealDao.save(meal.toEntity())
    }
    override suspend fun prepareEdit(uid: String): String {
        val meal = requireNotNull(getMeal(uid))
        if (meal.status != MealStatus.Confirmed) return uid
        val draft = meal.copy(id = 0, uid = UUID.randomUUID().toString(), status = MealStatus.NeedsReview, originalUid = uid)
        return mealDao.saveEditingDraft(meal.toEntity(), draft.toEntity())
    }
    override suspend fun confirm(meal: Meal) {
        require(meal.status != MealStatus.Analyzing) { "ui_error_050" }
        require(!meal.details.catalogPendingManual) { "ui_error_004" }
        val snapshot = calculator.calculate(meal.nutrition.name, meal.details.components, meal.details.portion)
        mealDao.confirm(meal.copy(nutrition = snapshot, status = MealStatus.Confirmed,
            details = meal.details.copy(photoName = null)).toEntity())
    }
    override suspend fun delete(uid: String) = mealDao.delete(uid,owner)
    override suspend fun recoverInterruptedAnalyses() = mealDao.recoverInterruptedAnalyses(owner)

    private fun MealEntity.toDomain(): Meal {
        val nutrition = Nutrition(name, calories, protein, fat, carbs)
        val details = detailsJson?.let { json.decodeFromString<MealDetails>(it) }
            ?: MealDetails(listOf(MealComponent(name, null, calories.toDouble(), protein.toDouble(), fat.toDouble(), carbs.toDouble())))
        return Meal(id, uid, nutrition, timestamp, zoneId, MealStatus.valueOf(status), NutritionSource.valueOf(source), details, originalUid, MealType.valueOf(mealType))
    }
    private fun Meal.toEntity() = MealEntity(id, nutrition.name, nutrition.calories, nutrition.protein,
        nutrition.fat, nutrition.carbs, timestamp, uid, status.name, zoneId, source.name,
        json.encodeToString(details), originalUid, mealType.name, owner)
}
