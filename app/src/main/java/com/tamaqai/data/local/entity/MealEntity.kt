package com.tamaqai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(tableName = "meals", indices = [Index(value = ["uid"], unique = true), Index(value = ["originalUid"], unique = true)])
data class MealEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val calories: Int,
    val protein: Int,
    val fat: Int,
    val carbs: Int,
    val timestamp: Long,
    @ColumnInfo(defaultValue = "''") val uid: String,
    @ColumnInfo(defaultValue = "'Confirmed'") val status: String,
    val zoneId: String?,
    @ColumnInfo(defaultValue = "'LegacyUnknown'") val source: String,
    val detailsJson: String?,
    val originalUid: String?,
    @ColumnInfo(defaultValue = "'Other'") val mealType: String = "Other",
    @ColumnInfo(defaultValue = "'guest'") val owner: String = "guest",
)
