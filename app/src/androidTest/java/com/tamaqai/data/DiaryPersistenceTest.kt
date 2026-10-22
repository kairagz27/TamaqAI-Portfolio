package com.tamaqai.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.Migrations
import com.tamaqai.data.repository.MealRepositoryImpl
import com.tamaqai.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiaryPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    @Test fun migrationPreservesExistingRows() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL("INSERT INTO meals VALUES(7,'Old meal',520,22,15,70,1234567)")
        }
        helper.runMigrationsAndValidate("migration-test", 2, true, Migrations.FROM_1_TO_2).use { db ->
            db.query("SELECT * FROM meals WHERE id=7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(520, cursor.getInt(cursor.getColumnIndexOrThrow("calories")))
                assertEquals("legacy-7", cursor.getString(cursor.getColumnIndexOrThrow("uid")))
                assertEquals("Confirmed", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("zoneId")))
            }
        }
    }
    @Test fun confirmEditAndRestartPreserveStableIdAndTotals() = runBlocking {
        context.deleteDatabase("diary-test")
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, "diary-test").addMigrations(Migrations.FROM_1_TO_2).build()
        var db = open()
        var repository = MealRepositoryImpl(db.mealDao())
        val c = MealComponent("Еда", 100.0, 120.0, 5.0, 4.0, 16.0)
        val draft = Meal(nutrition = Nutrition("Еда", 120, 5, 4, 16), details = MealDetails(listOf(c)))
        repository.saveDraft(draft)
        repository.confirm(draft)
        repository.confirm(draft) // repeated confirm cannot insert a duplicate
        val saved = requireNotNull(repository.getMeal(draft.uid))
        val editingUid = repository.prepareEdit(saved.uid)
        val editing = requireNotNull(repository.getMeal(editingUid)).copy(details = saved.details.copy(portion = 2.0))
        repository.saveDraft(editing)
        assertEquals(120, repository.getMeal(saved.uid)!!.nutrition.calories)
        repository.confirm(editing)
        db.close()
        db = open()
        repository = MealRepositoryImpl(db.mealDao())
        val restarted = repository.getAllMeals().first().single()
        assertEquals(saved.uid, restarted.uid)
        assertEquals(saved.id, restarted.id)
        assertEquals(240, restarted.nutrition.calories)
        assertEquals(saved.timestamp, restarted.timestamp)
        db.close()
    }
    @Test fun interruptedAnalysisRecoversWithoutAutomaticRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = MealRepositoryImpl(db.mealDao())
        val draft = Meal(nutrition = Nutrition("Фото", 0,0,0,0), status = MealStatus.Analyzing,
            details = MealDetails(emptyList(), analysisId = "stable-key"))
        repository.saveDraft(draft)
        repository.recoverInterruptedAnalyses()
        val recovered = repository.getMeal(draft.uid)!!
        assertEquals(MealStatus.Failed, recovered.status)
        assertEquals("stable-key", recovered.details.analysisId)
        db.close()
    }
}
