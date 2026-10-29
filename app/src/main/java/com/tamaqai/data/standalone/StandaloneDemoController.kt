package com.tamaqai.data.standalone

import android.content.Context
import androidx.room.withTransaction
import com.tamaqai.R
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.data.local.UserSettings
import com.tamaqai.data.local.entity.WeightEntity
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.AnalyzeMealPhotoUseCase
import com.tamaqai.domain.usecase.NutritionCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Only the standalone package owns this controller; production databases and preferences are unreachable. */
@android.annotation.SuppressLint("UseKtx")
class StandaloneDemoController(
    context: Context,
    private val database: AppDatabase,
    private val diary: MealRepository,
    private val settings: UserSettings,
    private val account: LocalAccountStore,
    private val nutrition: LocalNutritionStore,
    private val plans: LocalPlanStore,
    private val catalog: LocalCatalogStore,
    private val photos: PhotoStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val text: (Int) -> String,
) {
    init { require(context.packageName.endsWith(".standalone")) { "standalone_package_required" } }
    private val prefs = context.getSharedPreferences("standalone_demo", Context.MODE_PRIVATE)
    private val controllerLock = Mutex()
    private val startedChanges = MutableStateFlow(prefs.getBoolean("started", false))
    val started = startedChanges.asStateFlow()
    val analysis = LocalSampleFoodRepository(context.applicationContext, text)
    private val seed = OneTimeDemoSeed(clock, object : DemoSeedReceipt {
        override fun completed() = prefs.getBoolean("seed_complete", false)
        override fun anchor() = prefs.getString("seed_anchor", null)?.let(LocalDate::parse)
        override fun begin(date: LocalDate) { check(prefs.edit().putString("seed_anchor", date.toString()).commit()) }
        override fun complete() { check(prefs.edit().putBoolean("seed_complete", true).putBoolean("reset_pending", false).commit()) }
        override fun clear() { check(prefs.edit().remove("seed_anchor").remove("seed_complete").commit()) }
        override fun resetPending() = prefs.getBoolean("reset_pending", false)
        override fun requestReset() { check(prefs.edit().putBoolean("reset_pending", true).commit()) }
    })

    suspend fun initialize() = withContext(Dispatchers.IO) {
        controllerLock.withLock {
            seed.recoverReset(::clearData, ::seedData)
            seed.initialize(::seedData)
        }
    }
    fun start() {
        check(prefs.edit().putBoolean("started", true).commit())
        startedChanges.value = true
    }
    fun samples() = analysis.samples()

    suspend fun createSample(id: String, date: LocalDate): String = withContext(Dispatchers.IO) {
        controllerLock.withLock {
            val uid = UUID.randomUUID().toString()
            val bytes = analysis.image(id)
            val result = AnalyzeMealPhotoUseCase(analysis)(bytes, uid, "")
            val components = result.result.components.map { it.toDomain() }
            val name = photos.save(uid, bytes)
            try {
                diary.saveDraft(Meal(uid = uid, nutrition = NutritionCalculator().calculate(result.result.name, components, 1.0),
                    timestamp = date.atTime(LocalTime.now(clock)).atZone(clock.zone).toInstant().toEpochMilli(),
                    zoneId = clock.zone.id, status = MealStatus.NeedsReview, source = NutritionSource.Demo,
                    details = MealDetails(components, photoName = name, analysisId = uid, schemaVersion = result.schemaVersion,
                        promptVersion = result.promptVersion, model = result.model, explanation = result.result.explanation)))
            } catch (error: Exception) { photos.delete(name); throw error }
            uid
        }
    }

    /** Reset is explicit, scoped to this package/owner, and leaves appearance and language untouched. */
    suspend fun reset() = withContext(Dispatchers.IO + NonCancellable) {
        controllerLock.withLock {
            seed.reset(::clearData, ::seedData)
            check(prefs.edit().putBoolean("started", false).commit())
            startedChanges.value = false
        }
    }
    private suspend fun clearData() {
        check(prefs.edit().putBoolean("started", false).commit())
        database.dailyDao().clearLocalRecords(OWNER)
        photos.clear()
        account.clear()
        nutrition.clear()
        plans.clear()
        catalog.clear()
        settings.resetStandalone(DailyGoals(), LocalDate.now(clock))
    }

    private suspend fun seedData(anchor: LocalDate) {
        account.seed()
        nutrition.seed()
        // Stable IDs and the durable anchor also protect user changes after an interrupted first launch.
        database.withTransaction {
            seedMeals(anchor).forEach { meal ->
                if (database.mealDao().get(meal.uid, OWNER) == null) diary.confirm(meal)
            }
            listOf(21L to 73.0, 14L to 72.7, 7L to 72.3, 0L to 72.0).forEach { (offset, kg) ->
                val date = anchor.minusDays(offset).toString()
                if (database.dailyDao().weightOnDate(date, OWNER) == null) {
                    database.dailyDao().insertWeight(WeightEntity("standalone-weight-$offset", date, kg, OWNER))
                }
            }
        }
        if (settings.history.value.all { it.targets.isEmpty }) {
            settings.resetStandalone(DailyGoals(2100, 105, 70, 263), anchor.minusDays(30))
        }
        plans.seed(seedPlan(anchor))
        catalog.seed()
    }
    private fun seedMeals(anchor: LocalDate): List<Meal> {
        val calculator = NutritionCalculator()
        return (0L..2L).flatMap { offset ->
            listOf(
                Triple(MealType.Breakfast, LocalTime.of(8, 30), listOf(
                    MealComponent(text(R.string.standalone_seed_oats), 50.0, 185.0, 6.0, 3.5, 31.0),
                    MealComponent(text(R.string.standalone_seed_milk), 150.0, 78.0, 4.5, 3.75, 7.0))),
                Triple(MealType.Lunch, LocalTime.of(13, 0), listOf(
                    MealComponent(text(R.string.standalone_seed_chicken), 120.0, 198.0, 37.0, 4.3, 0.0),
                    MealComponent(text(R.string.standalone_seed_rice), 150.0, 195.0, 4.0, .4, 42.0),
                    MealComponent(text(R.string.standalone_seed_vegetables), 100.0, 35.0, 1.5, .3, 6.0))),
            ).mapIndexed { index, (type, time, components) ->
                val name = text(if (type == MealType.Breakfast) R.string.standalone_seed_breakfast else R.string.standalone_seed_lunch)
                Meal(uid = "standalone-seed-$offset-$index", nutrition = calculator.calculate(name, components, 1.0),
                    timestamp = anchor.minusDays(offset).atTime(time).atZone(clock.zone).toInstant().toEpochMilli(),
                    zoneId = clock.zone.id, status = MealStatus.Confirmed, source = NutritionSource.Demo,
                    details = MealDetails(components), mealType = type)
            }
        }
    }
    private fun seedPlan(date: LocalDate) = MealPlan(id = "standalone-plan-v1", date = date, source = "manual", dirty = false, demo = true,
        meals = listOf(PlannedMeal(id = "standalone-plan-dinner-v1", time = "19:00", dish = text(R.string.standalone_seed_dinner), ingredients = listOf(
            PlanIngredient("standalone-plan-chicken", text(R.string.standalone_seed_chicken), 120.0, "g", 120.0, PlanNutrients(198.0, 37.0, 4.3, 0.0)),
            PlanIngredient("standalone-plan-rice", text(R.string.standalone_seed_rice), 150.0, "g", 150.0, PlanNutrients(195.0, 4.0, .4, 42.0)),
            PlanIngredient("standalone-plan-vegetables", text(R.string.standalone_seed_vegetables), 100.0, "g", 100.0, PlanNutrients(35.0, 1.5, .3, 6.0)),
        ))))

    companion object { const val OWNER = "standalone" }
}
