package com.tamaqai.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** A small selected/recent cache, not a mirror of any external database. */
@Entity(tableName = "catalog_cache", primaryKeys = ["owner", "versionId"], indices = [Index(value = ["owner", "lastUsed"])])
data class CatalogCacheEntity(
    val owner: String,
    val versionId: String,
    val entryJson: String,
    val lastUsed: Long,
    val favorite: Boolean = false,
)
