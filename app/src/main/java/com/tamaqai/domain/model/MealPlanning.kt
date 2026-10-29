package com.tamaqai.domain.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

@Serializable enum class RecipeUnit { Gram, Kilogram, Milliliter, Liter, Piece, Unknown }
@Serializable enum class FoodPreparation { Raw, Cooked, Ready, Unknown }
@Serializable enum class ShoppingCategory { Vegetables, Fruit, Grains, Protein, Dairy, Bakery, Drinks, Spices, Other }
@Serializable enum class ShoppingStatus { Needed, Bought, AtHome }
@Serializable enum class MealSlot { Breakfast, Lunch, Dinner, Snack }

/** A null field is unknown, including when the other nutrients are available. */
@Serializable
data class RecipeNutrients(
    val calories: Double? = null,
    val protein: Double? = null,
    val fat: Double? = null,
    val carbs: Double? = null,
) {
    val complete get() = listOf(calories, protein, fat, carbs).all { it != null }
    fun scale(factor: Double): RecipeNutrients {
        require(factor.isFinite() && factor >= 0)
        return RecipeNutrients(calories?.times(factor), protein?.times(factor), fat?.times(factor), carbs?.times(factor))
    }
    fun validate() = require(listOf(calories, protein, fat, carbs).all { it == null || it.isFinite() && it in 0.0..10_000_000.0 })

    companion object {
        val Zero = RecipeNutrients(0.0, 0.0, 0.0, 0.0)
        fun sum(values: List<RecipeNutrients>): RecipeNutrients {
            fun field(select: (RecipeNutrients) -> Double?): Double? =
                if (values.any { select(it) == null }) null else values.sumOf { requireNotNull(select(it)) }
            return RecipeNutrients(field { it.calories }, field { it.protein }, field { it.fat }, field { it.carbs })
        }
    }
}

/** Nutrition describes this quantity in the entire recipe, never an implicit 100 g basis. */
@Serializable
data class RecipeIngredient(
    val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val name: String,
    val quantity: Double? = null,
    val unit: RecipeUnit = RecipeUnit.Unknown,
    val nutrients: RecipeNutrients = RecipeNutrients(),
    val localizedNames: Map<String, String> = emptyMap(),
    val category: ShoppingCategory = ShoppingCategory.Other,
    val preparation: FoodPreparation = FoodPreparation.Unknown,
    val densityGramsPerMl: Double? = null,
    val gramsPerPiece: Double? = null,
    val amountNote: String? = null,
) {
    fun displayName(language: String) = localizedNames[language.substringBefore('-')] ?: name
    val effectiveNutrients get() = if (quantity == null) RecipeNutrients() else nutrients
    fun scaled(factor: Double) = copy(quantity = quantity?.times(factor), nutrients = nutrients.scale(factor))
    fun validate() {
        require(id.isNotBlank() && productId.isNotBlank() && productId.length <= 160)
        require(name.isNotBlank() && name.length <= 160)
        require(quantity == null || quantity.isFinite() && quantity > 0 && quantity <= 1_000_000)
        require(densityGramsPerMl == null || densityGramsPerMl.isFinite() && densityGramsPerMl > 0 && densityGramsPerMl <= 100)
        require(gramsPerPiece == null || gramsPerPiece.isFinite() && gramsPerPiece > 0 && gramsPerPiece <= 1_000_000)
        require(amountNote == null || amountNote.length <= 240)
        nutrients.validate()
    }
}

@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val yieldServings: Double,
    val ingredients: List<RecipeIngredient>,
    val localizedNames: Map<String, String> = emptyMap(),
    val source: String = "Original TamaqAI demonstration recipe; illustrative nutrition",
    val license: String = "MIT",
    val demo: Boolean = true,
) {
    fun displayName(language: String) = localizedNames[language.substringBefore('-')] ?: name
    val nutrients get() = RecipeNutrients.sum(ingredients.map { it.effectiveNutrients })
    fun validate() {
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 160)
        require(yieldServings.isFinite() && yieldServings in .05..100.0)
        require(ingredients.size in 1..40 && ingredients.map { it.id }.distinct().size == ingredients.size)
        require(source.isNotBlank() && source.length <= 600 && license.length <= 160)
        ingredients.forEach(RecipeIngredient::validate)
    }
}

@Serializable
data class PlannedRecipeEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: String,
    val mealType: MealSlot = MealSlot.Lunch,
    val recipe: Recipe,
    val servings: Double = 1.0,
) {
    val scaledIngredients get() = recipe.ingredients.map { it.scaled(servings / recipe.yieldServings) }
    val nutrients get() = recipe.nutrients.scale(servings / recipe.yieldServings)
    fun validate() {
        require(id.isNotBlank())
        LocalDate.parse(date)
        require(servings.isFinite() && servings in .05..100.0)
        recipe.validate()
        nutrients.validate()
    }
}

@Serializable
data class PlanningWorkspace(
    val id: String = "default",
    val startDate: String,
    val dayCount: Int = 3,
    val revision: Long = 0,
    val entries: List<PlannedRecipeEntry> = emptyList(),
    val importedPlanIds: Map<String, Int> = emptyMap(),
) {
    val dates get() = (0 until dayCount).map { LocalDate.parse(startDate).plusDays(it.toLong()).toString() }
    val selectedEntries get() = entries.filter { it.date in dates }
    fun totalsForDate(date: String) = RecipeNutrients.sum(entries.filter { it.date == date }.map { it.nutrients })
    fun validate() {
        require(id.isNotBlank() && dayCount in 1..7 && revision >= 0)
        LocalDate.parse(startDate)
        require(entries.size <= 1000 && entries.map { it.id }.distinct().size == entries.size)
        entries.forEach(PlannedRecipeEntry::validate)
    }
    fun copyDay(sourceDate: String, targetDate: String, idFactory: () -> String = { UUID.randomUUID().toString() }): PlanningWorkspace {
        require(sourceDate != targetDate && sourceDate in dates && targetDate in dates)
        val copied = entries.filter { it.date == sourceDate }.map { it.copy(id = idFactory(), date = targetDate) }
        return copy(entries = entries.filterNot { it.date == targetDate } + copied, revision = revision + 1).also { it.validate() }
    }
}

@Serializable
data class ShoppingUse(
    val entryId: String,
    val dish: String,
    val date: String,
    val servings: Double,
    val localizedNames: Map<String, String> = emptyMap(),
) {
    fun displayName(language: String) = localizedNames[language.substringBefore('-')] ?: dish
}

@Serializable
data class ShoppingRow(
    val key: String,
    val name: String,
    val productId: String = "",
    val unit: RecipeUnit = RecipeUnit.Unknown,
    val quantity: Double? = null,
    val unknownQuantity: Boolean = false,
    val unknownContributionCount: Int = 0,
    val uses: List<ShoppingUse> = emptyList(),
    val category: ShoppingCategory = ShoppingCategory.Other,
    val preparation: FoodPreparation = FoodPreparation.Unknown,
    val localizedNames: Map<String, String> = emptyMap(),
    val status: ShoppingStatus = ShoppingStatus.Needed,
    val note: String = "",
    val manual: Boolean = false,
    val inPlan: Boolean = true,
    val needsReview: Boolean = false,
    val checkedQuantity: Double? = null,
    val checkedUnknownQuantity: Boolean = false,
) {
    fun displayName(language: String) = localizedNames[language.substringBefore('-')] ?: name
    fun withStatus(value: ShoppingStatus): ShoppingRow = copy(
        status = value, needsReview = false,
        checkedQuantity = if (value == ShoppingStatus.Needed) null else quantity,
        checkedUnknownQuantity = value != ShoppingStatus.Needed && unknownQuantity,
    )
    fun acknowledgeReview() = withStatus(status)
    fun validate() {
        require(key.isNotBlank() && name.isNotBlank() && name.length <= 160 && note.length <= 1000)
        require(manual || productId.isNotBlank())
        require(unknownContributionCount >= 0)
        require(quantity == null || quantity.isFinite() && quantity > 0 && quantity <= 1_000_000_000)
        require(checkedQuantity == null || checkedQuantity.isFinite() && checkedQuantity > 0 && checkedQuantity <= 1_000_000_000)
    }
}

@Serializable
data class ShoppingDocument(
    val workspaceId: String,
    val sourceRevision: Long,
    val rows: List<ShoppingRow> = emptyList(),
) {
    fun isStale(workspace: PlanningWorkspace) = workspaceId != workspace.id || sourceRevision != workspace.revision
    fun validate() {
        require(workspaceId.isNotBlank() && sourceRevision >= 0 && rows.size <= 5000)
        require(rows.map { it.key }.distinct().size == rows.size)
        rows.forEach(ShoppingRow::validate)
    }
}
