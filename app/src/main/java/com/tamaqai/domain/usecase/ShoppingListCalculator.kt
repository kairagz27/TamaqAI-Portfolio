package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.*
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/** Pure calculation: no database, Android context, network, catalogue lookup or invented density. */
class ShoppingListCalculator {
    private data class Contribution(val ingredient: RecipeIngredient, val use: ShoppingUse)
    private data class ProductState(val productId: String, val preparation: FoodPreparation)
    private data class Converted(val unit: RecipeUnit, val quantity: Double?)

    fun calculate(workspace: PlanningWorkspace, previous: ShoppingDocument? = null): ShoppingDocument {
        workspace.validate()
        previous?.validate()
        require(previous == null || previous.workspaceId == workspace.id)
        val contributions = workspace.selectedEntries.flatMap { entry ->
            val use = ShoppingUse(entry.id, entry.recipe.name, entry.date, entry.servings, entry.recipe.localizedNames)
            entry.scaledIngredients.map { Contribution(it, use) }
        }
        val generated = contributions.groupBy { ProductState(it.ingredient.productId, it.ingredient.preparation) }
            .flatMap { (product, items) ->
                // Conflicting product facts disable the relevant cross-dimension conversion.
                val density = consistent(items.mapNotNull { it.ingredient.densityGramsPerMl })
                val gramsPerPiece = consistent(items.mapNotNull { it.ingredient.gramsPerPiece })
                items.groupBy { convert(it.ingredient, density, gramsPerPiece).unit }.map { (unit, group) ->
                    val amounts = group.map { convert(it.ingredient, density, gramsPerPiece).quantity }
                    val first = group.first().ingredient
                    ShoppingRow(
                        key = key(product.productId, product.preparation, unit), productId = product.productId,
                        name = first.name, localizedNames = first.localizedNames,
                        unit = unit, quantity = amounts.filterNotNull().takeIf { it.isNotEmpty() }?.sum(),
                        unknownQuantity = amounts.any { it == null } || unit == RecipeUnit.Unknown,
                        unknownContributionCount = if (unit == RecipeUnit.Unknown) group.size else amounts.count { it == null },
                        uses = group.map { it.use }.distinctBy { it.entryId }.sortedWith(compareBy(ShoppingUse::date, ShoppingUse::entryId)),
                        category = group.firstOrNull { it.ingredient.category != ShoppingCategory.Other }?.ingredient?.category ?: ShoppingCategory.Other,
                        preparation = product.preparation,
                    )
                }
            }.sortedWith(compareBy<ShoppingRow> { it.category.ordinal }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.key })
        val old = previous?.rows.orEmpty().associateBy { it.key }
        val merged = generated.map { current ->
            val saved = old[current.key]?.takeUnless { it.manual } ?: return@map current
            val marked = saved.status != ShoppingStatus.Needed
            val baseline = saved.checkedQuantity ?: saved.quantity
            val amountIncreased = current.quantity != null && (baseline == null || current.quantity > baseline + EPSILON)
            val unknownChanged = current.unknownQuantity && (
                !saved.unknownQuantity || current.unknownContributionCount > saved.unknownContributionCount || current.uses != saved.uses)
            current.copy(
                status = saved.status, note = saved.note,
                checkedQuantity = saved.checkedQuantity, checkedUnknownQuantity = saved.checkedUnknownQuantity,
                needsReview = saved.needsReview || marked && (amountIncreased || unknownChanged),
            )
        }
        val activeKeys = generated.map { it.key }.toSet()
        val retained = previous?.rows.orEmpty().filter { row ->
            row.manual || row.key !in activeKeys && (row.note.isNotBlank() || row.status != ShoppingStatus.Needed)
        }.map { if (it.manual) it else it.copy(inPlan = false) }
        return ShoppingDocument(workspace.id, workspace.revision, merged + retained).also { it.validate() }
    }

    fun refresh(workspace: PlanningWorkspace, previous: ShoppingDocument?): ShoppingDocument = calculate(workspace, previous)

    private fun consistent(values: List<Double>): Double? {
        val first = values.firstOrNull() ?: return null
        return first.takeIf { value -> values.all { abs(it - value) <= EPSILON } }
    }

    private fun convert(ingredient: RecipeIngredient, density: Double?, gramsPerPiece: Double?): Converted {
        val (unit, factor) = when (ingredient.unit) {
            RecipeUnit.Gram -> RecipeUnit.Gram to 1.0
            RecipeUnit.Kilogram -> RecipeUnit.Gram to 1000.0
            RecipeUnit.Milliliter -> if (density != null) RecipeUnit.Gram to density else RecipeUnit.Milliliter to 1.0
            RecipeUnit.Liter -> if (density != null) RecipeUnit.Gram to density * 1000.0 else RecipeUnit.Milliliter to 1000.0
            RecipeUnit.Piece -> if (gramsPerPiece != null) RecipeUnit.Gram to gramsPerPiece else RecipeUnit.Piece to 1.0
            RecipeUnit.Unknown -> RecipeUnit.Unknown to 1.0
        }
        return Converted(unit, ingredient.quantity?.times(factor))
    }

    private fun key(product: String, preparation: FoodPreparation, unit: RecipeUnit): String {
        val bytes = "$product\u0000${preparation.name}\u0000${unit.name}".toByteArray(Charsets.UTF_8)
        return "auto-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private companion object { const val EPSILON = 0.000001 }
}
