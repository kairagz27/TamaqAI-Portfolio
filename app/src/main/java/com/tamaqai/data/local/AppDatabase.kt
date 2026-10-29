package com.tamaqai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tamaqai.data.local.dao.MealDao
import com.tamaqai.data.local.entity.MealEntity
import com.tamaqai.data.local.entity.FavoriteEntity
import com.tamaqai.data.local.entity.WeightEntity
import com.tamaqai.data.local.dao.DailyDao

@Database(
    entities = [MealEntity::class, FavoriteEntity::class, WeightEntity::class, com.tamaqai.data.local.entity.LocalLinkEntity::class,
        com.tamaqai.data.local.entity.CatalogCacheEntity::class,
        com.tamaqai.data.local.entity.PlanningWorkspaceEntity::class,
        com.tamaqai.data.local.entity.ShoppingDocumentEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao
    abstract fun dailyDao(): DailyDao
    abstract fun catalogDao(): com.tamaqai.data.local.dao.CatalogDao
    abstract fun planningDao(): com.tamaqai.data.local.dao.PlanningDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(Migrations.FROM_1_TO_2, Migrations.FROM_2_TO_3, Migrations.FROM_3_TO_4, Migrations.FROM_4_TO_5, Migrations.FROM_5_TO_6).build().also { instance = it }
            }

        private const val DATABASE_NAME = "tamaq_meals.db"
    }
}
