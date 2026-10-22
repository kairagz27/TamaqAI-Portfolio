package com.tamaqai.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "favorite_meals")
data class FavoriteEntity(@PrimaryKey val id: String, val name: String, val calories: Int,
    val protein: Int, val fat: Int, val carbs: Int, val detailsJson: String,
    val mealType: String, val source: String, @ColumnInfo(defaultValue = "'guest'") val owner: String = "guest")

@Entity(tableName = "weight_entries", indices = [Index(value = ["owner", "date"], unique = true)])
data class WeightEntity(@PrimaryKey val id: String, val date: String, val kilograms: Double,
    @ColumnInfo(defaultValue = "'guest'") val owner: String = "guest")
