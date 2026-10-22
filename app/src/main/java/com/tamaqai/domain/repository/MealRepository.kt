package com.tamaqai.domain.repository

import com.tamaqai.domain.model.Meal
import com.tamaqai.domain.model.Nutrition
import kotlinx.coroutines.flow.Flow

interface MealRepository {
    fun getAllMeals(): Flow<List<Meal>>

    suspend fun getMeal(uid: String): Meal?
    suspend fun saveDraft(meal: Meal)
    suspend fun prepareEdit(uid: String): String
    suspend fun confirm(meal: Meal)
    suspend fun delete(uid: String)
    suspend fun recoverInterruptedAnalyses()
}
