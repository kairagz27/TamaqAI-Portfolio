package com.tamaqai.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.data.local.*
import com.tamaqai.data.repository.*
import com.tamaqai.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class AccountIsolationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),AppDatabase::class.java)
    @Test fun threeToFourPreservesAllRowsAndGuestOwnership() {
        val name="auth-migration-"+UUID.randomUUID()
        helper.createDatabase(name,3).use {
            it.execSQL("INSERT INTO meals(id,name,calories,protein,fat,carbs,timestamp,uid,status,zoneId,source,detailsJson,originalUid,mealType) VALUES(17,'Сохранено',540,30,20,60,123,'stable','Failed','Asia/Qyzylorda','AiEstimate','{\"components\":[],\"analysisId\":\"stable-id\",\"photoName\":\"p.jpg\"}',NULL,'Lunch')")
            it.execSQL("INSERT INTO weight_entries VALUES('weight','2026-10-01',72.5)")
            it.execSQL("INSERT INTO favorite_meals VALUES('f','Любимое',120,5,4,16,'{}','Lunch','Manual')")
        }
        helper.runMigrationsAndValidate(name,4,true,Migrations.FROM_3_TO_4).use {
            it.query("SELECT id,calories,detailsJson,owner FROM meals").use { c -> assertTrue(c.moveToFirst());assertEquals(17,c.getInt(0));assertEquals(540,c.getInt(1));assertTrue(c.getString(2).contains("stable-id"));assertEquals("guest",c.getString(3)) }
            listOf("weight_entries","favorite_meals").forEach { table -> it.query("SELECT owner FROM $table").use { c -> assertTrue(c.moveToFirst());assertEquals("guest",c.getString(0)) } }
        }
        context.deleteDatabase(name)
    }
    @Test fun ownersCannotReadEditDeleteOrClearEachOthersDiary() = runBlocking<Unit> {
        val name="auth-isolation-"+UUID.randomUUID()
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        try {
            val first=MealRepositoryImpl(db.mealDao(),"user-first"); val second=MealRepositoryImpl(db.mealDao(),"user-second")
            val food=Meal(nutrition=Nutrition("Первый",120,5,4,16),details=MealDetails(listOf(MealComponent("Еда",100.0,120.0,5.0,4.0,16.0))))
            first.confirm(food)
            val id=first.getMeal(food.uid)!!.id
            assertNull(second.getMeal(food.uid)); assertTrue(second.getAllMeals().first().isEmpty())
            assertTrue(runCatching {second.prepareEdit(food.uid)}.isFailure)
            assertTrue(runCatching {second.confirm(food)}.isFailure)
            second.delete(food.uid);DailyRepository(db.dailyDao(),"user-second").clearLocalRecords()
            assertEquals(id,first.getMeal(food.uid)!!.id)
            assertEquals(120,first.getMeal(food.uid)!!.nutrition.calories)
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun weightsFavoritesAndGoalsHaveIndependentOwnerScopes() = runBlocking<Unit> {
        val name="auth-daily-"+UUID.randomUUID();val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        val one="scope-one-"+UUID.randomUUID();val two="scope-two-"+UUID.randomUUID()
        try {
            val first=DailyRepository(db.dailyDao(),one); val second=DailyRepository(db.dailyDao(),two)
            val weight=WeightEntry(date=LocalDate.now(),kilograms=70.5);first.saveWeight(weight)
            second.saveWeight(WeightEntry(date=weight.date,kilograms=90.0));second.deleteWeight(weight.id)
            assertEquals(70.5,first.weights.first().single().kilograms,0.0)
            first.favorite(Meal(nutrition=Nutrition("Еда",120,5,4,16),status=MealStatus.Confirmed,details=MealDetails(emptyList())))
            assertTrue(second.favorites.first().isEmpty());second.clearLocalRecords();assertEquals(1,first.favorites.first().size)
            UserSettings(context,one).save(DailyGoals(2100,100,70,250));UserSettings(context,two).save(DailyGoals(1800,90,60,200))
            assertEquals(2100,UserSettings(context,one).dailyGoals.value.calories)
            assertEquals(1800,UserSettings(context,two).dailyGoals.value.calories)
        } finally {db.close();context.deleteDatabase(name);context.getSharedPreferences("daily_settings_$one",0).edit().clear().commit();context.getSharedPreferences("daily_settings_$two",0).edit().clear().commit()}
    }
}
