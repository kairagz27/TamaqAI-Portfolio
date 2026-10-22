package com.tamaqai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.tamaqai.data.local.entity.MealEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MealDao {
    @Insert
    suspend fun insertMeal(meal: MealEntity): Long

    @Query("SELECT * FROM meals WHERE owner=:owner ORDER BY timestamp DESC")
    fun getAllMeals(owner: String = "guest"): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE uid=:uid AND owner=:owner")
    suspend fun get(uid: String, owner: String = "guest"): MealEntity?

    @Query("SELECT * FROM meals WHERE originalUid=:uid AND owner=:owner")
    suspend fun editingDraft(uid: String, owner: String = "guest"): MealEntity?

    @Update
    suspend fun updateRow(meal: MealEntity)

    suspend fun update(meal: MealEntity) {
        require(get(meal.uid,meal.owner)?.id == meal.id)
        updateRow(meal)
    }

    @Transaction
    suspend fun save(meal: MealEntity) {
        val existing = get(meal.uid,meal.owner)
        if (existing == null) insertMeal(meal.copy(id = 0)) else update(meal.copy(id = existing.id))
    }

    @Transaction
    suspend fun saveEditingDraft(original: MealEntity, draft: MealEntity): String {
        require(original.owner == draft.owner && get(original.uid,original.owner) != null)
        editingDraft(original.uid,original.owner)?.let { return it.uid }
        insertMeal(draft.copy(id = 0))
        return draft.uid
    }

    @Transaction
    suspend fun confirm(meal: MealEntity) {
        if (meal.originalUid != null) {
            val original = requireNotNull(get(meal.originalUid,meal.owner)) { "ui_error_051" }
            update(meal.copy(id = original.id, uid = original.uid, originalUid = null, status = "Confirmed"))
            delete(meal.uid,meal.owner)
        } else save(meal.copy(status = "Confirmed"))
    }

    @Query("DELETE FROM meals WHERE (uid=:uid OR originalUid=:uid) AND owner=:owner")
    suspend fun delete(uid: String, owner: String = "guest")

    @Query("UPDATE meals SET status='Failed' WHERE status='Analyzing' AND owner=:owner")
    suspend fun recoverInterruptedAnalyses(owner: String = "guest")
}
