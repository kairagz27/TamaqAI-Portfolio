package com.tamaqai.domain.repository

import com.tamaqai.domain.model.*
import kotlinx.coroutines.flow.Flow

/** Local, owner-scoped planning. Import is an explicit snapshot, not a server overwrite. */
interface PlanningRepository {
    val workspace: Flow<PlanningWorkspace?>
    val shopping: Flow<ShoppingDocument?>
    val recipes: List<Recipe>

    suspend fun initialize()
    suspend fun setPeriod(startDate: String, dayCount: Int)
    suspend fun addRecipe(date: String, mealType: MealSlot, recipeId: String, servings: Double): String
    suspend fun changeServings(entryId: String, servings: Double)
    suspend fun replaceRecipe(entryId: String, recipeId: String)
    suspend fun deleteEntry(entryId: String)
    /** Replaces the target day; every copied entry receives a new durable identity. */
    suspend fun copyDay(sourceDate: String, targetDate: String)
    suspend fun refreshShopping()
    suspend fun markRow(key: String, status: ShoppingStatus)
    suspend fun noteRow(key: String, note: String)
    suspend fun addManual(name: String, quantity: Double?, unit: RecipeUnit, category: ShoppingCategory, note: String): String
    suspend fun removeManual(key: String)
    /** Actual servings are explicit; repeated confirmation returns the existing diary UID. */
    suspend fun confirmEaten(entryId: String, actualServings: Double): String
    /** Imports only source IDs not previously imported, preserving all later local changes. */
    suspend fun importDailyPlans(plans: List<MealPlan>): Int
}
