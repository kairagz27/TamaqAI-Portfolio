package com.tamaqai.data.local.dao

import androidx.room.*
import com.tamaqai.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyDao {
    @Query("SELECT * FROM favorite_meals WHERE owner=:owner ORDER BY name COLLATE NOCASE")
    fun favorites(owner: String = "guest"): Flow<List<FavoriteEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFavorite(entry: FavoriteEntity)
    @Query("DELETE FROM favorite_meals WHERE id=:id AND owner=:owner") suspend fun deleteFavorite(id: String, owner: String = "guest")
    @Query("SELECT * FROM weight_entries WHERE owner=:owner ORDER BY date") fun weights(owner: String = "guest"): Flow<List<WeightEntity>>
    @Query("SELECT * FROM weight_entries WHERE date=:date AND owner=:owner") suspend fun weightOnDate(date: String, owner: String = "guest"): WeightEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertWeight(entry: WeightEntity)
    @Update suspend fun updateWeight(entry: WeightEntity)
    @Transaction suspend fun saveWeight(entry: WeightEntity) {
        val existing = weightOnDate(entry.date,entry.owner)
        require(existing == null || existing.id == entry.id) { "weight_date_exists" }
        if (existing != null) updateWeight(entry) else {
            deleteWeight(entry.id,entry.owner)
            insertWeight(entry)
        }
    }
    @Query("DELETE FROM weight_entries WHERE id=:id AND owner=:owner") suspend fun deleteWeight(id: String, owner: String = "guest")
    @Query("DELETE FROM favorite_meals WHERE owner=:owner") suspend fun clearFavorites(owner: String = "guest")
    @Query("DELETE FROM weight_entries WHERE owner=:owner") suspend fun clearWeights(owner: String = "guest")
    @Query("DELETE FROM meals WHERE owner=:owner") suspend fun clearMeals(owner: String = "guest")
    @Query("DELETE FROM catalog_cache WHERE owner=:owner") suspend fun clearCatalog(owner: String)
    @Query("DELETE FROM shopping_documents WHERE owner=:owner") suspend fun clearShopping(owner:String)
    @Query("DELETE FROM planning_workspaces WHERE owner=:owner") suspend fun clearPlanning(owner:String)
    @Transaction suspend fun clearLocalRecords(owner: String = "guest") { clearMeals(owner); clearFavorites(owner); clearWeights(owner); clearCatalog(owner); clearShopping(owner); clearPlanning(owner) }
}
