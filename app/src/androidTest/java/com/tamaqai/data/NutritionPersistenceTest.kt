package com.tamaqai.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.data.local.*
import com.tamaqai.data.remote.*
import com.tamaqai.data.repository.MealRepositoryImpl
import com.tamaqai.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.time.LocalDate
import java.util.UUID

class NutritionPersistenceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun legacyGoalsHistoryOriginsAndFutureDatePreserved() {
        val owner="goal-history-"+UUID.randomUUID();val prefs=context.getSharedPreferences("daily_settings_$owner",0)
        try {
            prefs.edit().putInt("calories",2100).putInt("protein",90).commit()
            val settings=UserSettings(context,owner);val today=LocalDate.now()
            settings.save(DailyGoals(2300,120,70,290),today,"calculated")
            settings.save(DailyGoals(2500,150,80,280),today.plusDays(1),"manual")
            val restored=UserSettings(context,owner)
            assertEquals(2100,restored.onDate(today.minusDays(1)).calories)
            assertEquals(2300,restored.dailyGoals.value.calories);assertEquals(2500,restored.onDate(today.plusDays(1)).calories)
            assertEquals("calculated",restored.originOn(today));assertEquals("manual",restored.originOn(today.plusDays(1)))
        } finally {prefs.edit().clear().commit()}
    }
    @Test fun offlineDraftAndPlanSurviveRestartAndEatIsIdempotent()=runBlocking<Unit> {
        val owner="offline-plan-"+UUID.randomUUID();val origin="http://127.0.0.1:"+ServerSocket(0).use {it.localPort}
        val store=SecureSessions(context,origin);store.login(JSONObject().put("user_id",owner).put("access_token","offline-access-fixture").put("refresh_token","offline-refresh-fixture").put("expires_in",600))
        val client=SessionClient(store,origin,200,200)
        val name="plan-cache-"+UUID.randomUUID();val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        try {
            val meals=MealRepositoryImpl(db.mealDao(),owner);val plans=PlanRepository(context,client,meals)
            val ingredient=PlanIngredient(name="Test",quantity=100.0,nutrition=PlanNutrients(120.0,5.0,4.0,16.0))
            val plan=MealPlan(meals=listOf(PlannedMeal(dish="Test",ingredients=listOf(ingredient))))
            plans.local(plan);assertTrue(runCatching {plans.sync(plan.id)}.isFailure)
            plans.local(plan.copy(meals=listOf(plan.meals.single().copy(ingredients=listOf(ingredient.copy(quantity=50.0))))))
            val restored=PlanRepository(context,client,meals)
            assertTrue(restored.plans.value.single().dirty);assertEquals(60.0,restored.plans.value.single().total!!.calories,0.0)
            val uid=restored.eat(plan.id,plan.meals.single().id,.5)
            assertEquals(uid,restored.eat(plan.id,plan.meals.single().id,1.0))
            assertEquals(1,meals.getAllMeals().first().size);assertEquals(30,meals.getMeal(uid)!!.nutrition.calories)
            val nutrition=NutritionRepository(context,client,UserSettings(context,owner))
            nutrition.draft(Questionnaire(step=1,goal="maintain",age=30,height=175.0,weight=70.0))
            assertTrue(runCatching {nutrition.sync()}.isFailure)
            val cache=NutritionRepository(context,client,UserSettings(context,owner)).state.value
            assertTrue(cache.dirty);assertFalse(cache.document.completed);assertEquals(1,cache.document.step)
        } finally {db.close();context.deleteDatabase(name);listOf("plans_$owner","nutrition_$owner","daily_settings_$owner").forEach {context.getSharedPreferences(it,0).edit().clear().commit()};store.logout()}
    }
    @Test fun loginTimeoutIsReportedAndCredentialsNeverPersist()=runBlocking<Unit> {
        ServerSocket(0).use {server->
            val origin="http://127.0.0.1:${server.localPort}";val store=SecureSessions(context,origin)
            val client=SessionClient(store,origin,200,150);val worker=Executors.newSingleThreadExecutor()
            val job=worker.submit {server.accept().use {socket->Thread.sleep(500)}}
            try {
                val failure=runCatching {AccountRepository(context,client).login("fixture@example.com","never-store-test-password")}.exceptionOrNull()
                assertTrue(failure is java.net.SocketTimeoutException)
                assertTrue(store.access.isBlank());assertTrue(store.refresh.isBlank())
                assertFalse(context.getSharedPreferences("encrypted_sessions",0).all.values.joinToString().contains("never-store-test-password"))
                job.get()
            } finally {worker.shutdownNow()}
        }
    }
    @Test fun unavailableLoginDoesNotReplaceTheExistingSession()=runBlocking<Unit> {
        val origin="http://127.0.0.1:"+ServerSocket(0).use {it.localPort}
        val store=SecureSessions(context,origin)
        store.login(JSONObject().put("user_id","offline-account-fixture").put("access_token","offline-access-fixture").put("refresh_token","offline-refresh-fixture").put("expires_in",600))
        try {
            val result=runCatching {AccountRepository(context,SessionClient(store,origin,200,200)).login("fixture@example.com","never-store-test-password")}
            assertTrue(result.isFailure)
            assertEquals("offline-account-fixture",store.owner)
            assertEquals("offline-access-fixture",store.access)
            assertFalse(store.snapshot().toString().contains("never-store-test-password"))
        } finally {store.logout()}
    }
}
