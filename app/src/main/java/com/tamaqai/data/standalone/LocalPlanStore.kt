package com.tamaqai.data.standalone

import android.content.Context
import com.tamaqai.data.contracts.PlanStore
import com.tamaqai.data.remote.document
import com.tamaqai.data.remote.planRecord
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.NutritionCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Editable local plan. Merely viewing or editing it never adds meals to the diary. */
@android.annotation.SuppressLint("UseKtx")
class LocalPlanStore(context: Context, owner: String, private val diary: MealRepository,
    private val clock: Clock = Clock.systemDefaultZone()) : PlanStore {
    private val prefs = context.getSharedPreferences("plans_$owner", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val consumeLock = Mutex()
    private fun read(): List<MealPlan> {
        val array = JSONArray(prefs.getString("plans", "[]"))
        return (0 until array.length()).map { planRecord(array.getJSONObject(it)) }
    }
    private val changes = MutableStateFlow(read())
    override val plans = changes.asStateFlow()
    override val hasPendingGeneration = false
    private fun persist(items: List<MealPlan>) {
        val array = JSONArray(items.map { JSONObject().put("id", it.id).put("version", it.version)
            .put("document", it.document()).put("dirty", false).put("is_demo", true) })
        check(prefs.edit().putString("plans", array.toString()).commit())
        changes.value = items
    }
    override suspend fun local(plan: MealPlan) = withContext(Dispatchers.IO) {
        lock.withLock { plan.validate(); persist(listOf(plan.copy(dirty = false, demo = true)) + changes.value.filterNot { it.id == plan.id }) }
    }
    override suspend fun load() = Unit
    override suspend fun sync(id: String) { require(changes.value.any { it.id == id }) }
    override suspend fun saveAsCopy(id: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val copy = changes.value.first { it.id == id }.copy(id = UUID.randomUUID().toString(), version = 0, dirty = false, source = "manual", demo = true)
            persist(listOf(copy) + changes.value); copy.id
        }
    }
    override suspend fun generate(date: LocalDate, fitnessVersion: Int, goalDate: LocalDate, language: String): String = unavailable()
    override fun abandonGeneration() = Unit
    override suspend fun eat(planId: String, mealId: String, portion: Double): String = consumeLock.withLock {
        require(portion.isFinite() && portion in .05..20.0)
        val plan = changes.value.first { it.id == planId }
        val meal = plan.meals.first { it.id == mealId }
        val uid = planMealUid(planId, mealId)
        if (diary.getMeal(uid)?.status == MealStatus.Confirmed) return@withLock uid
        require(meal.total != null) { "unknown_nutrition" }
        val components = meal.ingredients.map {
            val total = requireNotNull(it.total)
            MealComponent(it.name, if (it.unit == "g") it.quantity else null, total.calories, total.protein, total.fat, total.carbs)
        }
        diary.confirm(Meal(uid = uid, nutrition = NutritionCalculator().calculate(meal.dish, components, portion),
            timestamp = plan.date.atTime(LocalTime.parse(meal.time)).atZone(clock.zone).toInstant().toEpochMilli(),
            zoneId = clock.zone.id, source = NutritionSource.Demo,
            details = MealDetails(components, portion = portion, userEdited = true)))
        uid
    }
    internal suspend fun seed(plan: MealPlan) = lock.withLock {
        if (changes.value.none { it.id == plan.id }) persist(changes.value + plan)
    }
    internal suspend fun clear() = lock.withLock { check(prefs.edit().clear().commit()); changes.value = emptyList() }

    companion object {
        fun planMealUid(planId: String, mealId: String): String =
            UUID.nameUUIDFromBytes("tamaq-plan:$planId:$mealId".toByteArray(Charsets.UTF_8)).toString()
    }
}
