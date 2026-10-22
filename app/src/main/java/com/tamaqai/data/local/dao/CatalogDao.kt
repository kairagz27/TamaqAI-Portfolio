package com.tamaqai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tamaqai.data.local.entity.CatalogCacheEntity

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_cache WHERE owner=:owner ORDER BY favorite DESC,lastUsed DESC LIMIT 100")
    suspend fun recent(owner: String): List<CatalogCacheEntity>

    @Query("SELECT * FROM catalog_cache WHERE owner=:owner AND versionId=:version LIMIT 1")
    suspend fun get(owner: String, version: String): CatalogCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CatalogCacheEntity)

    @Query("UPDATE catalog_cache SET favorite=:favorite WHERE owner=:owner AND versionId=:version")
    suspend fun favorite(owner: String, version: String, favorite: Boolean)

    @Query("DELETE FROM catalog_cache WHERE owner=:owner AND favorite=0 AND versionId NOT IN (SELECT versionId FROM catalog_cache WHERE owner=:owner ORDER BY lastUsed DESC LIMIT 40)")
    suspend fun trim(owner: String)

    @Query("DELETE FROM catalog_cache WHERE owner=:owner")
    suspend fun clear(owner: String)

    @Transaction
    suspend fun remember(entry: CatalogCacheEntity) {
        val favorite = get(entry.owner, entry.versionId)?.favorite ?: false
        put(entry.copy(favorite = favorite))
        trim(entry.owner)
    }
}
