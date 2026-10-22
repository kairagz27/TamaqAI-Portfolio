package com.tamaqai.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.data.local.*
import com.tamaqai.data.repository.*
import com.tamaqai.domain.model.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DailyPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    @Test fun migrationTwoToThreePreservesDraftPhotoRequestAndConfirmedSnapshot() {
        val name = "redesign-migration-" + UUID.randomUUID()
        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO meals (id,name,calories,protein,fat,carbs,timestamp,uid,status,zoneId,source,detailsJson,originalUid) VALUES(17,'Старый дневник',540,30,20,60,1234567,'stable','Confirmed','Asia/Qyzylorda','Manual',NULL,NULL)")
            db.execSQL("INSERT INTO meals (id,name,calories,protein,fat,carbs,timestamp,uid,status,zoneId,source,detailsJson,originalUid) VALUES(18,'Фото',0,0,0,0,1234568,'pending','Failed','Asia/Qyzylorda','Manual','{\"components\":[],\"photoName\":\"pending.jpg\",\"analysisId\":\"request-id\"}',NULL)")
        }
        helper.runMigrationsAndValidate(name, 3, true, Migrations.FROM_2_TO_3).use { db ->
            db.query("SELECT * FROM meals ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count); assertTrue(cursor.moveToFirst())
                assertEquals(17, cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                assertEquals(540, cursor.getInt(cursor.getColumnIndexOrThrow("calories")))
                assertEquals("stable", cursor.getString(cursor.getColumnIndexOrThrow("uid")))
                assertEquals("Other", cursor.getString(cursor.getColumnIndexOrThrow("mealType")))
                assertTrue(cursor.moveToNext())
                assertTrue(cursor.getString(cursor.getColumnIndexOrThrow("detailsJson")).contains("request-id"))
            }
        }
        context.deleteDatabase(name) // Only this isolated test database, never tamaq_meals.db.
    }
    @Test fun migrationOneToThreeKeepsLegacyId() {
        val name = "redesign-legacy-" + UUID.randomUUID()
        helper.createDatabase(name, 1).use { it.execSQL("INSERT INTO meals VALUES(7,'Legacy',520,22,15,70,1234567)") }
        helper.runMigrationsAndValidate(name, 3, true, Migrations.FROM_1_TO_2, Migrations.FROM_2_TO_3).use { db ->
            db.query("SELECT uid,calories FROM meals WHERE id=7").use { c -> assertTrue(c.moveToFirst()); assertEquals("legacy-7",c.getString(0)); assertEquals(520,c.getInt(1)) }
        }
        context.deleteDatabase(name)
    }
    @Test fun favoritesCopiesWeightCrudAndRestartUseRealRoom() = runBlocking<Unit> {
        val name = "redesign-data-" + UUID.randomUUID()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        var meals = MealRepositoryImpl(db.mealDao())
        var daily = DailyRepository(db.dailyDao())
        val original = Meal(nutrition = Nutrition("Шаблон",120,5,4,16), details = MealDetails(listOf(MealComponent("Еда",100.0,120.0,5.0,4.0,16.0))), mealType = MealType.Lunch)
        meals.confirm(original)
        val favoriteId = daily.favorite(requireNotNull(meals.getMeal(original.uid)))
        val favorite = daily.favorites.first().single()
        val copy = MealCopies.fromFavorite(favorite, LocalDate.now())
        meals.saveDraft(copy)
        meals.confirm(copy.copy(details = copy.details.copy(portion = 2.0)))
        meals.confirm(copy.copy(details = copy.details.copy(portion = 2.0)))
        val entry = WeightEntry(date = LocalDate.now(), kilograms = 70.5)
        daily.saveWeight(entry)
        daily.saveWeight(entry.copy(kilograms = 70.2, date = entry.date.minusDays(1)))
        assertTrue(runCatching { daily.saveWeight(WeightEntry(date = entry.date.minusDays(1), kilograms = 80.0)) }.isFailure)
        db.close(); db = open(); meals = MealRepositoryImpl(db.mealDao()); daily = DailyRepository(db.dailyDao())
        assertEquals(2, meals.getAllMeals().first().size)
        assertEquals(120, meals.getMeal(original.uid)!!.nutrition.calories)
        assertEquals(240, meals.getMeal(copy.uid)!!.nutrition.calories)
        assertEquals(favoriteId, daily.favorites.first().single().id)
        assertEquals(70.2, daily.weights.first().single().kilograms, 0.0)
        daily.deleteWeight(entry.id); daily.removeFavorite(favoriteId)
        assertTrue(daily.weights.first().isEmpty()); assertTrue(daily.favorites.first().isEmpty())
        db.close(); context.deleteDatabase(name)
    }
    @Test fun goalsPersistWithoutRewritingMeals() {
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("redesign-goals-test", mode)
        }
        val goals = DailyGoals(2400,120,80,290)
        UserSettings(isolated).save(goals)
        assertEquals(goals, UserSettings(isolated).dailyGoals.value)
        UserSettings(isolated).save(DailyGoals())
        assertTrue(UserSettings(isolated).dailyGoals.value.isEmpty)
        isolated.getSharedPreferences("daily_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
