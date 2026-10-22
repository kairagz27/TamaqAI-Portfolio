package com.tamaqai.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    val FROM_4_TO_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS catalog_cache (owner TEXT NOT NULL, versionId TEXT NOT NULL, entryJson TEXT NOT NULL, lastUsed INTEGER NOT NULL, favorite INTEGER NOT NULL, PRIMARY KEY(owner,versionId))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_cache_owner_lastUsed ON catalog_cache(owner,lastUsed)")
        }
    }
    val FROM_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf("meals", "favorite_meals", "weight_entries").forEach {
                db.execSQL("ALTER TABLE $it ADD COLUMN owner TEXT NOT NULL DEFAULT 'guest'")
            }
            db.execSQL("DROP INDEX index_weight_entries_date")
            db.execSQL("CREATE UNIQUE INDEX index_weight_entries_owner_date ON weight_entries(owner,date)")
            db.execSQL("CREATE TABLE IF NOT EXISTS local_links (operation TEXT NOT NULL PRIMARY KEY, owner TEXT NOT NULL)")
        }
    }

    val FROM_2_TO_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE meals ADD COLUMN mealType TEXT NOT NULL DEFAULT 'Other'")
            db.execSQL("CREATE TABLE IF NOT EXISTS favorite_meals (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, calories INTEGER NOT NULL, protein INTEGER NOT NULL, fat INTEGER NOT NULL, carbs INTEGER NOT NULL, detailsJson TEXT NOT NULL, mealType TEXT NOT NULL, source TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS weight_entries (id TEXT NOT NULL PRIMARY KEY, date TEXT NOT NULL, kilograms REAL NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_weight_entries_date ON weight_entries(date)")
        }
    }
    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE meals ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE meals SET uid = 'legacy-' || id")
            db.execSQL("ALTER TABLE meals ADD COLUMN status TEXT NOT NULL DEFAULT 'Confirmed'")
            db.execSQL("ALTER TABLE meals ADD COLUMN zoneId TEXT")
            db.execSQL("ALTER TABLE meals ADD COLUMN source TEXT NOT NULL DEFAULT 'LegacyUnknown'")
            db.execSQL("ALTER TABLE meals ADD COLUMN detailsJson TEXT")
            db.execSQL("ALTER TABLE meals ADD COLUMN originalUid TEXT")
            db.execSQL("CREATE UNIQUE INDEX index_meals_uid ON meals(uid)")
            db.execSQL("CREATE UNIQUE INDEX index_meals_originalUid ON meals(originalUid)")
        }
    }
}
