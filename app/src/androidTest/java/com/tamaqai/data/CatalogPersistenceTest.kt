package com.tamaqai.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.Migrations
import com.tamaqai.data.local.entity.CatalogCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun additiveMigrationPreservesDiaryFavoritesAndOwnership() {
        assumeTrue(instrumentation.targetContext.packageName.endsWith(".visual") || instrumentation.targetContext.packageName.endsWith(".demo"))
        helper.createDatabase("catalog-migration-only", 4).use { db ->
            db.execSQL("INSERT INTO meals (id,name,calories,protein,fat,carbs,timestamp,uid,status,source,mealType,owner) VALUES (701,'Migration fixture',222,10,8,24,1234567,'catalog-migration-meal','Confirmed','Manual','Other','fixture-owner')")
            db.execSQL("INSERT INTO weight_entries (id,date,kilograms,owner) VALUES ('fixture-weight','2026-10-20',70,'fixture-owner')")
            db.execSQL("INSERT INTO favorite_meals (id,name,calories,protein,fat,carbs,detailsJson,mealType,source,owner) VALUES ('fixture-favorite','Migration favorite',222,10,8,24,'{}','Other','Manual','fixture-owner')")
        }
        helper.runMigrationsAndValidate("catalog-migration-only", 5, true, Migrations.FROM_4_TO_5).use { db ->
            db.query("SELECT calories,owner,uid FROM meals WHERE id=701").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(222, cursor.getInt(0)); assertEquals("fixture-owner", cursor.getString(1)); assertEquals("catalog-migration-meal", cursor.getString(2))
            }
            db.query("SELECT kilograms FROM weight_entries WHERE owner='fixture-owner'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(70.0, cursor.getDouble(0), 0.0) }
            db.query("SELECT calories FROM favorite_meals WHERE owner='fixture-owner'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(222, cursor.getInt(0)) }
            db.query("SELECT COUNT(*) FROM catalog_cache").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
        }
    }

    @Test fun cacheIsOwnerScopedBoundedAndRetainsFrozenVersions() = runBlocking {
        assumeTrue(instrumentation.targetContext.packageName.endsWith(".visual") || instrumentation.targetContext.packageName.endsWith(".demo"))
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        try {
            val dao = db.catalogDao()
            dao.remember(CatalogCacheEntity("owner-a", "v1", "{\"nutrients\":null}", 1))
            dao.favorite("owner-a", "v1", true)
            dao.remember(CatalogCacheEntity("owner-b", "v1", "{\"private\":true}", 2))
            dao.remember(CatalogCacheEntity("owner-a", "v2", "{\"nutrients\":42}", 3))
            assertEquals("{\"nutrients\":null}", dao.get("owner-a", "v1")!!.entryJson)
            assertEquals("{\"private\":true}", dao.get("owner-b", "v1")!!.entryJson)
            assertNull(dao.get("owner-c", "v1"))
            repeat(50) { dao.remember(CatalogCacheEntity("owner-a", "next-$it", "{}", 100L + it)) }
            assertTrue(dao.get("owner-a", "v1")!!.favorite)
            assertEquals(41, dao.recent("owner-a").size)
            assertEquals(1, dao.recent("owner-b").size)
            dao.clear("owner-a")
            assertTrue(dao.recent("owner-a").isEmpty())
            assertEquals(1, dao.recent("owner-b").size)
        } finally { db.close() }
    }
    @Test fun unresolvedCatalogueValuesCannotBeConfirmedThroughRepository() = runBlocking {
        assumeTrue(instrumentation.targetContext.packageName.endsWith(".visual") || instrumentation.targetContext.packageName.endsWith(".demo"))
        val db=Room.inMemoryDatabaseBuilder(instrumentation.targetContext,AppDatabase::class.java).build()
        try {
            val repository=com.tamaqai.data.repository.MealRepositoryImpl(db.mealDao(),"fixture-owner")
            val meal=com.tamaqai.domain.model.Meal(nutrition=com.tamaqai.domain.model.Nutrition("Pending fixture",200,5,5,30),
                details=com.tamaqai.domain.model.MealDetails(listOf(com.tamaqai.domain.model.MealComponent("Old estimate",null,200.0,5.0,5.0,30.0)),
                    catalogPendingManual=true,catalogPendingIndex=0))
            repository.saveDraft(meal)
            var rejected=false
            try {repository.confirm(meal)} catch(_:IllegalArgumentException) {rejected=true}
            assertTrue(rejected)
            assertEquals(com.tamaqai.domain.model.MealStatus.Draft,repository.getMeal(meal.uid)!!.status)
        } finally {db.close()}
    }
}
