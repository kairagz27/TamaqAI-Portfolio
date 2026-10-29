package com.tamaqai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tamaqai.data.local.entity.PlanningWorkspaceEntity
import com.tamaqai.data.local.entity.ShoppingDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanningDao {
    @Query("SELECT * FROM planning_workspaces WHERE owner=:owner AND id=:id")
    fun observeWorkspace(owner: String, id: String): Flow<PlanningWorkspaceEntity?>

    @Query("SELECT * FROM shopping_documents WHERE owner=:owner AND workspaceId=:id")
    fun observeShopping(owner: String, id: String): Flow<ShoppingDocumentEntity?>

    @Query("SELECT * FROM planning_workspaces WHERE owner=:owner AND id=:id")
    suspend fun workspace(owner: String, id: String): PlanningWorkspaceEntity?

    @Query("SELECT * FROM shopping_documents WHERE owner=:owner AND workspaceId=:id")
    suspend fun shopping(owner: String, id: String): ShoppingDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWorkspace(value: PlanningWorkspaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveShopping(value: ShoppingDocumentEntity)

    @Query("DELETE FROM planning_workspaces WHERE owner=:owner")
    suspend fun clearWorkspaces(owner: String)

    @Query("DELETE FROM shopping_documents WHERE owner=:owner")
    suspend fun clearShopping(owner: String)
}
