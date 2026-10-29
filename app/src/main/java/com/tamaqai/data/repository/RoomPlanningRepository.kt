package com.tamaqai.data.repository

import androidx.room.withTransaction
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.entity.PlanningWorkspaceEntity
import com.tamaqai.data.local.entity.ShoppingDocumentEntity
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.repository.PlanningRepository
import com.tamaqai.domain.usecase.DemoRecipes
import com.tamaqai.domain.usecase.NutritionCalculator
import com.tamaqai.domain.usecase.ShoppingListCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID

/**
 * Offline planning documents and shopping snapshots. Every read/write captures an owner;
 * SQLite transactions coordinate separate repository instances as well as coroutines.
 * No server plan, identity, quota or ledger is modified by this repository.
 */
class RoomPlanningRepository(
    private val database: AppDatabase,
    private val owner: String,
    private val diary: MealRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ownerCheck: () -> Unit = {},
    override val recipes: List<Recipe> = DemoRecipes.all,
    private val language: () -> String = { Locale.getDefault().language },
) : PlanningRepository {
    init {
        require(owner.isNotBlank())
        require(recipes.map { it.id }.distinct().size == recipes.size)
        recipes.forEach(Recipe::validate)
    }

    private val dao = database.planningDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val calculator = ShoppingListCalculator()

    override val workspace = dao.observeWorkspace(owner, WORKSPACE_ID).map { row ->
        ownerCheck()
        row?.let { decodeWorkspace(it.documentJson).also { value -> require(value.id == row.id) } }
    }
    override val shopping = dao.observeShopping(owner, WORKSPACE_ID).map { row ->
        ownerCheck()
        row?.let { decodeShopping(it.documentJson).also { value -> require(value.workspaceId == row.workspaceId) } }
    }

    private fun decodeWorkspace(raw: String) = json.decodeFromString<PlanningWorkspace>(raw).also { it.validate() }
    private fun decodeShopping(raw: String) = json.decodeFromString<ShoppingDocument>(raw).also { it.validate() }
    private suspend fun readWorkspace(): PlanningWorkspace {
        ownerCheck()
        val row = dao.workspace(owner, WORKSPACE_ID)
        return if (row != null) decodeWorkspace(row.documentJson).also { require(it.id == WORKSPACE_ID) }
        else PlanningWorkspace(startDate = LocalDate.now(clock).toString()).also { saveWorkspace(it) }
    }
    private suspend fun readShopping() = dao.shopping(owner, WORKSPACE_ID)?.let { decodeShopping(it.documentJson) }
    private suspend fun saveWorkspace(value: PlanningWorkspace) {
        ownerCheck(); value.validate(); require(value.id == WORKSPACE_ID)
        dao.saveWorkspace(PlanningWorkspaceEntity(owner, value.id, json.encodeToString(value)))
    }
    private suspend fun saveShopping(value: ShoppingDocument) {
        ownerCheck(); value.validate(); require(value.workspaceId == WORKSPACE_ID)
        dao.saveShopping(ShoppingDocumentEntity(owner, value.workspaceId, json.encodeToString(value)))
    }
    private suspend fun <T> transaction(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            database.withTransaction {
                ownerCheck()
                action().also { ownerCheck() }
            }
        }
    }
    private suspend fun mutateWorkspace(transform: (PlanningWorkspace) -> PlanningWorkspace) = transaction {
        val before = readWorkspace()
        val after = transform(before)
        if (after != before) saveWorkspace(after.copy(revision = before.revision + 1))
    }
    private suspend fun mutateShopping(transform: (ShoppingDocument) -> ShoppingDocument) = transaction {
        val workspace = readWorkspace()
        val before = readShopping() ?: calculator.calculate(workspace)
        saveShopping(transform(before))
    }

    override suspend fun initialize() = transaction { readWorkspace(); Unit }

    override suspend fun setPeriod(startDate: String, dayCount: Int) {
        LocalDate.parse(startDate); require(dayCount in 1..7)
        mutateWorkspace { it.copy(startDate = startDate, dayCount = dayCount) }
    }

    override suspend fun addRecipe(date: String, mealType: MealSlot, recipeId: String, servings: Double): String {
        val recipe = recipes.single { it.id == recipeId }
        val entry = PlannedRecipeEntry(date = date, mealType = mealType, recipe = recipe, servings = servings)
        entry.validate()
        mutateWorkspace { before ->
            require(date in before.dates) { "planning_date_outside_period" }
            before.copy(entries = before.entries + entry)
        }
        return entry.id
    }

    override suspend fun changeServings(entryId: String, servings: Double) = mutateWorkspace { before ->
        require(before.entries.any { it.id == entryId }) { "planning_entry_missing" }
        before.copy(entries = before.entries.map { if (it.id == entryId) it.copy(servings = servings) else it })
    }

    override suspend fun replaceRecipe(entryId: String, recipeId: String) {
        val recipe = recipes.single { it.id == recipeId }
        mutateWorkspace { before ->
            require(before.entries.any { it.id == entryId }) { "planning_entry_missing" }
            before.copy(entries = before.entries.map { if (it.id == entryId) it.copy(recipe = recipe) else it })
        }
    }

    override suspend fun deleteEntry(entryId: String) = mutateWorkspace { before ->
        before.copy(entries = before.entries.filterNot { it.id == entryId })
    }

    override suspend fun copyDay(sourceDate: String, targetDate: String) = mutateWorkspace {
        it.copyDay(sourceDate, targetDate)
    }

    override suspend fun refreshShopping() = transaction {
        val workspace = readWorkspace()
        saveShopping(calculator.refresh(workspace, readShopping()))
    }

    override suspend fun markRow(key: String, status: ShoppingStatus) = mutateShopping { before ->
        require(before.rows.any { it.key == key }) { "planning_shopping_missing" }
        before.copy(rows = before.rows.map { if (it.key == key) it.withStatus(status) else it })
    }

    override suspend fun noteRow(key: String, note: String) = mutateShopping { before ->
        require(note.length <= 1000)
        require(before.rows.any { it.key == key }) { "planning_shopping_missing" }
        before.copy(rows = before.rows.map { if (it.key == key) it.copy(note = note) else it })
    }

    override suspend fun addManual(name: String, quantity: Double?, unit: RecipeUnit, category: ShoppingCategory, note: String): String {
        val row = ShoppingRow(key = "manual-" + UUID.randomUUID(), name = name.trim(), unit = unit,
            quantity = quantity, unknownQuantity = quantity == null, category = category, note = note, manual = true)
        row.validate()
        mutateShopping { it.copy(rows = it.rows + row) }
        return row.key
    }

    override suspend fun removeManual(key: String) = mutateShopping { before ->
        require(before.rows.single { it.key == key }.manual) { "planning_not_manual" }
        before.copy(rows = before.rows.filterNot { it.key == key })
    }

    override suspend fun confirmEaten(entryId: String, actualServings: Double): String = transaction {
        require(actualServings.isFinite() && actualServings in .05..100.0) { "planning_invalid_servings" }
        val uid = diaryUid(entryId)
        if (diary.getMeal(uid)?.status == MealStatus.Confirmed) return@transaction uid
        val entry = readWorkspace().entries.single { it.id == entryId }
        require(entry.recipe.nutrients.complete) { "planning_unknown_nutrition" }
        val factor = actualServings / entry.recipe.yieldServings
        val savedLanguage = language()
        val components = entry.recipe.ingredients.map { ingredient ->
            val nutrients = ingredient.effectiveNutrients.scale(factor)
            require(nutrients.complete) { "planning_unknown_nutrition" }
            val mass = when (ingredient.unit) {
                RecipeUnit.Gram -> ingredient.quantity?.times(factor)
                RecipeUnit.Kilogram -> ingredient.quantity?.times(1000 * factor)
                else -> null
            }
            MealComponent(ingredient.displayName(savedLanguage).take(120), mass, requireNotNull(nutrients.calories),
                requireNotNull(nutrients.protein), requireNotNull(nutrients.fat), requireNotNull(nutrients.carbs))
        }
        val name = entry.recipe.displayName(savedLanguage).take(120)
        val nutrition = NutritionCalculator().calculate(name, components, 1.0)
        val time = when (entry.mealType) {
            MealSlot.Breakfast -> LocalTime.of(8, 0)
            MealSlot.Lunch -> LocalTime.of(13, 0)
            MealSlot.Dinner -> LocalTime.of(19, 0)
            MealSlot.Snack -> LocalTime.of(16, 0)
        }
        diary.confirm(Meal(uid = uid, nutrition = nutrition,
            timestamp = LocalDate.parse(entry.date).atTime(time).atZone(clock.zone).toInstant().toEpochMilli(),
            zoneId = clock.zone.id, source = if (entry.recipe.demo) NutritionSource.Demo else NutritionSource.Manual,
            details = MealDetails(components, portion = 1.0, userEdited = true),
            mealType = when (entry.mealType) {
                MealSlot.Breakfast -> MealType.Breakfast
                MealSlot.Lunch -> MealType.Lunch
                MealSlot.Dinner -> MealType.Dinner
                MealSlot.Snack -> MealType.Snack
            }))
        uid
    }

    override suspend fun importDailyPlans(plans: List<MealPlan>): Int = transaction {
        val before = readWorkspace()
        val sources = plans.filter { it.date.toString() in before.dates && it.id !in before.importedPlanIds && it.meals.isNotEmpty() }
            .distinctBy { it.id }
        sources.forEach {
            it.validate()
            require(it.id.isNotBlank() && it.version >= 0 && it.meals.all { meal -> meal.id.isNotBlank() }) { "planning_invalid_import" }
        }
        val entries = sources.flatMap { plan -> plan.meals.map { meal ->
            val recipe = Recipe(id = "import-${plan.id}-${meal.id}", name = meal.dish, yieldServings = 1.0,
                ingredients = meal.ingredients.map { ingredient ->
                    val amount = ingredient.total
                    // Legacy plans have neither canonical product identity nor raw/cooked metadata.
                    // A matching display name alone is not evidence of a compatible product.
                    RecipeIngredient(id = ingredient.id, productId = importedProductId(plan.id, meal.id, ingredient.id),
                        name = ingredient.name, quantity = ingredient.quantity,
                        unit = when (ingredient.unit) { "g" -> RecipeUnit.Gram; "ml" -> RecipeUnit.Milliliter; else -> RecipeUnit.Piece },
                        nutrients = if (amount == null) RecipeNutrients() else RecipeNutrients(amount.calories, amount.protein, amount.fat, amount.carbs),
                        preparation = FoodPreparation.Unknown)
                }, source = "Local snapshot of an existing daily plan (${plan.source}); preparation not specified",
                license = "Local account data; not distributed", demo = plan.demo)
            val hour = LocalTime.parse(meal.time).hour
            PlannedRecipeEntry(id = UUID.nameUUIDFromBytes("planning-import:$owner:${plan.id}:${meal.id}".toByteArray(Charsets.UTF_8)).toString(),
                date = plan.date.toString(), mealType = when { hour < 11 -> MealSlot.Breakfast; hour < 16 -> MealSlot.Lunch; hour < 18 -> MealSlot.Snack; else -> MealSlot.Dinner },
                recipe = recipe)
        } }
        if (entries.isNotEmpty()) saveWorkspace(before.copy(entries = before.entries + entries,
            importedPlanIds = before.importedPlanIds + sources.associate { it.id to it.version }, revision = before.revision + 1))
        entries.size
    }

    companion object {
        const val WORKSPACE_ID = "default"
        // Entry IDs are generated locally (or owner-scoped on import) and survive proven guest linkage.
        // Including the mutable owner here would mint a second diary record after linkage.
        fun diaryUid(entryId: String): String =
            UUID.nameUUIDFromBytes("tamaq-planner:$entryId".toByteArray(Charsets.UTF_8)).toString()

        private fun importedProductId(planId: String, mealId: String, ingredientId: String): String {
            val identity = "$planId:$mealId:$ingredientId"
            return "imported-" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
