package com.tamaqai.domain

import com.tamaqai.domain.model.*
import com.tamaqai.domain.usecase.DemoRecipes
import com.tamaqai.domain.usecase.ShoppingListCalculator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ShoppingListCalculatorTest {
    private val calculator = ShoppingListCalculator()
    private var sequence = 0
    private val today = "2026-10-29"
    private fun food(product: String = "rice", quantity: Double? = 100.0, unit: RecipeUnit = RecipeUnit.Gram,
        preparation: FoodPreparation = FoodPreparation.Raw, density: Double? = null, pieceWeight: Double? = null,
        nutrients: RecipeNutrients = RecipeNutrients(100.0, 10.0, 5.0, 20.0)) = RecipeIngredient(
        id = "ingredient-${sequence++}", productId = product, name = product, quantity = quantity, unit = unit,
        preparation = preparation, densityGramsPerMl = density, gramsPerPiece = pieceWeight, nutrients = nutrients,
    )
    private fun recipe(ingredients: List<RecipeIngredient>, yield: Double = 1.0) =
        Recipe("recipe-${sequence++}", "Fixture dish", yield, ingredients)
    private fun entry(ingredient: RecipeIngredient, date: String = today, servings: Double = 1.0) =
        PlannedRecipeEntry(id = "entry-${sequence++}", date = date, recipe = recipe(listOf(ingredient)), servings = servings)
    private fun workspace(vararg entries: PlannedRecipeEntry) = PlanningWorkspace(startDate = today, entries = entries.toList())
    private fun rows(vararg ingredients: RecipeIngredient): List<ShoppingRow> =
        calculator.calculate(workspace(*ingredients.map { entry(it) }.toTypedArray())).rows
    private fun marked(document: ShoppingDocument, status: ShoppingStatus = ShoppingStatus.Bought) =
        document.copy(rows = document.rows.map { it.withStatus(status) })

    @Test fun recipeYieldAndPlannedServingsScaleTheOriginalAmounts() {
        val dish = recipe(listOf(food(quantity = 240.0)), yield = 4.0)
        val plan = workspace(PlannedRecipeEntry(date = today, recipe = dish, servings = 3.0))
        val row = calculator.calculate(plan).rows.single()
        assertEquals(180.0, row.quantity!!, .000001)
        assertEquals(75.0, plan.selectedEntries.single().nutrients.calories!!, .000001)
    }

    @Test fun repeatedPortionEditsNeverAccumulateScalingError() {
        val original = PlannedRecipeEntry(date = today, recipe = recipe(listOf(food(quantity = 125.0)), 2.0), servings = 1.0)
        val edited = original.copy(servings = 3.0).copy(servings = .5).copy(servings = 1.0)
        assertEquals(original.scaledIngredients, edited.scaledIngredients)
        assertEquals(62.5, calculator.calculate(workspace(edited)).rows.single().quantity!!, .000001)
    }

    @Test fun kilogramsAndGramsUseTheSameMassRow() {
        val merged = rows(food(quantity = .5, unit = RecipeUnit.Kilogram), food(quantity = 250.0)).single()
        assertEquals(RecipeUnit.Gram, merged.unit)
        assertEquals(750.0, merged.quantity!!, .000001)
        assertEquals(2, merged.uses.size)
    }

    @Test fun litersAndMillilitersUseTheSameVolumeRow() {
        val merged = rows(food(quantity = .5, unit = RecipeUnit.Liter), food(quantity = 250.0, unit = RecipeUnit.Milliliter)).single()
        assertEquals(RecipeUnit.Milliliter, merged.unit)
        assertEquals(750.0, merged.quantity!!, .000001)
    }

    @Test fun piecesAreSummedWithoutInventingMass() {
        val merged = rows(food(quantity = 2.0, unit = RecipeUnit.Piece), food(quantity = 3.0, unit = RecipeUnit.Piece)).single()
        assertEquals(RecipeUnit.Piece, merged.unit)
        assertEquals(5.0, merged.quantity!!, 0.0)
    }

    @Test fun gramsAndMillilitersRemainSeparateWithoutDensity() {
        val result = rows(food(), food(unit = RecipeUnit.Milliliter))
        assertEquals(setOf(RecipeUnit.Gram, RecipeUnit.Milliliter), result.map { it.unit }.toSet())
    }

    @Test fun piecesAndGramsRemainSeparateWithoutPieceWeight() {
        assertEquals(2, rows(food(), food(quantity = 2.0, unit = RecipeUnit.Piece)).size)
    }

    @Test fun explicitDensityPermitsVolumeToMassConversion() {
        val merged = rows(food(quantity = 500.0, unit = RecipeUnit.Milliliter, density = 1.02), food(quantity = 100.0)).single()
        assertEquals(RecipeUnit.Gram, merged.unit)
        assertEquals(610.0, merged.quantity!!, .000001)
    }

    @Test fun explicitPieceWeightPermitsCountToMassConversion() {
        val merged = rows(food(quantity = 2.0, unit = RecipeUnit.Piece, pieceWeight = 60.0), food(quantity = 100.0)).single()
        assertEquals(220.0, merged.quantity!!, .000001)
        assertEquals(RecipeUnit.Gram, merged.unit)
    }

    @Test fun volumeAndPiecesRequireBothNecessaryFacts() {
        assertEquals(2, rows(food(quantity = 2.0, unit = RecipeUnit.Piece, pieceWeight = 60.0),
            food(quantity = 100.0, unit = RecipeUnit.Milliliter)).size)
        val merged = rows(food(quantity = 2.0, unit = RecipeUnit.Piece, pieceWeight = 60.0),
            food(quantity = 100.0, unit = RecipeUnit.Milliliter, density = 1.1)).single()
        assertEquals(230.0, merged.quantity!!, .000001)
    }

    @Test fun conflictingDensitiesDisableCrossDimensionConversion() {
        val result = rows(food(quantity = 100.0, unit = RecipeUnit.Milliliter, density = 1.0),
            food(quantity = 200.0, unit = RecipeUnit.Milliliter, density = 1.2), food(quantity = 300.0))
        assertEquals(2, result.size)
        assertEquals(300.0, result.single { it.unit == RecipeUnit.Milliliter }.quantity!!, 0.0)
    }

    @Test fun conflictingPieceWeightsKeepCountsSeparateFromMass() {
        val result = rows(food(quantity = 1.0, unit = RecipeUnit.Piece, pieceWeight = 20.0),
            food(quantity = 2.0, unit = RecipeUnit.Piece, pieceWeight = 40.0), food())
        assertEquals(2, result.size)
        assertEquals(3.0, result.single { it.unit == RecipeUnit.Piece }.quantity!!, 0.0)
    }

    @Test fun unknownUnitsAreNeverInterpretedAsGramsOrPieces() {
        val result = rows(food(unit = RecipeUnit.Unknown, density = 1.0, pieceWeight = 50.0), food())
        assertEquals(2, result.size)
        assertTrue(result.single { it.unit == RecipeUnit.Unknown }.unknownQuantity)
    }

    @Test fun rawCookedReadyAndUnspecifiedProductsNeverMerge() {
        val result = rows(*FoodPreparation.entries.map { food(preparation = it) }.toTypedArray())
        assertEquals(4, result.size)
        assertEquals(4, result.map { it.key }.distinct().size)
    }

    @Test fun localizedNamesDoNotChangeCanonicalProductIdentity() {
        val first = food().copy(name = "Rice", localizedNames = mapOf("ru" to "Рис"))
        val second = food().copy(name = "Рис")
        assertEquals(200.0, rows(first, second).single().quantity!!, 0.0)
    }

    @Test fun similarNamesWithDifferentProductIdsRemainSeparate() {
        val first = food("whole-milk").copy(name = "Milk")
        val second = food("skimmed-milk").copy(name = "Milk")
        assertEquals(2, rows(first, second).size)
    }

    @Test fun entirelyUnknownQuantityStaysNullInsteadOfZero() {
        val row = rows(food(quantity = null)).single()
        assertNull(row.quantity)
        assertTrue(row.unknownQuantity)
        assertEquals(1, row.unknownContributionCount)
    }

    @Test fun knownSubtotalPlusUnknownAmountRemainsExplicitlyIncomplete() {
        val row = rows(food(quantity = null), food(quantity = 150.0)).single()
        assertEquals(150.0, row.quantity!!, 0.0)
        assertTrue(row.unknownQuantity)
    }

    @Test fun anUnknownNutrientDoesNotBecomeAZeroInDailyTotals() {
        val plan = workspace(entry(food(nutrients = RecipeNutrients(100.0, null, 5.0, 20.0))), entry(food()))
        val total = plan.totalsForDate(today)
        assertEquals(200.0, total.calories!!, 0.0)
        assertNull(total.protein)
        assertFalse(total.complete)
    }

    @Test fun missingIngredientAmountMakesItsNutritionUnknown() {
        val nutrients = recipe(listOf(food(quantity = null))).nutrients
        assertFalse(nutrients.complete)
        assertNull(nutrients.calories)
    }

    @Test fun anEmptyDayHasKnownZeroRatherThanMissingFoodData() {
        assertEquals(RecipeNutrients.Zero, workspace().totalsForDate(today))
    }

    @Test fun onlyTheSelectedOneToSevenDayPeriodContributes() {
        val plan = workspace(entry(food(), "2026-10-28"), entry(food(), today), entry(food(), "2026-10-31"), entry(food(), "2026-11-01"))
        assertEquals(200.0, calculator.calculate(plan).rows.single().quantity!!, 0.0)
        assertEquals(100.0, calculator.calculate(plan.copy(dayCount = 1)).rows.single().quantity!!, 0.0)
    }

    @Test fun eachRowRetainsTheDishesDatesAndPortionsThatNeedIt() {
        val a = entry(food(), servings = 2.0)
        val b = entry(food(), "2026-10-30")
        val row = calculator.calculate(workspace(a, b)).rows.single()
        assertEquals(setOf(a.id, b.id), row.uses.map { it.entryId }.toSet())
        assertEquals(2.0, row.uses.first { it.entryId == a.id }.servings, 0.0)
        assertEquals("2026-10-30", row.uses.first { it.entryId == b.id }.date)
    }

    @Test fun repeatedIngredientsInOneDishDoNotRepeatTheDishLabel() {
        val dish = recipe(listOf(food(), food(quantity = 20.0)))
        val row = calculator.calculate(workspace(PlannedRecipeEntry(date = today, recipe = dish))).rows.single()
        assertEquals(120.0, row.quantity!!, 0.0)
        assertEquals(1, row.uses.size)
    }

    @Test fun refreshPreservesManualPositionsAndNotesExactly() {
        val plan = workspace(entry(food()))
        val original = calculator.calculate(plan)
        val manual = ShoppingRow("manual-1", "Reusable bags", quantity = 2.0, unit = RecipeUnit.Piece,
            manual = true, note = "Keep the larger size", status = ShoppingStatus.AtHome)
        val old = original.copy(rows = listOf(original.rows.single().copy(note = "Check the label"), manual))
        val refreshed = calculator.calculate(plan.copy(revision = 1), old)
        assertEquals(manual, refreshed.rows.single { it.manual })
        assertEquals("Check the label", refreshed.rows.single { !it.manual }.note)
    }

    @Test fun increasingABoughtProductPreservesMarkAndRequestsReview() {
        val entry = entry(food())
        val initial = workspace(entry)
        val old = marked(calculator.calculate(initial))
        val changed = initial.copy(revision = 1, entries = listOf(entry.copy(servings = 2.0)))
        val row = calculator.calculate(changed, old).rows.single()
        assertEquals(ShoppingStatus.Bought, row.status)
        assertEquals(200.0, row.quantity!!, 0.0)
        assertTrue(row.needsReview)
        assertEquals(100.0, row.checkedQuantity!!, 0.0)
    }

    @Test fun increasingAnAtHomeProductAlsoRequestsReview() {
        val entry = entry(food())
        val initial = workspace(entry)
        val row = calculator.calculate(initial.copy(entries = listOf(entry.copy(servings = 1.5)), revision = 1),
            marked(calculator.calculate(initial), ShoppingStatus.AtHome)).rows.single()
        assertEquals(ShoppingStatus.AtHome, row.status)
        assertTrue(row.needsReview)
    }

    @Test fun repeatedRefreshDoesNotClearAnUnacknowledgedReview() {
        val item = entry(food())
        val initial = workspace(item)
        val changed = initial.copy(entries = listOf(item.copy(servings = 2.0)), revision = 1)
        val once = calculator.calculate(changed, marked(calculator.calculate(initial)))
        assertTrue(calculator.calculate(changed, once).rows.single().needsReview)
    }

    @Test fun acknowledgingReviewRecordsTheNewAvailableAmount() {
        val item = entry(food())
        val initial = workspace(item)
        val changed = initial.copy(entries = listOf(item.copy(servings = 2.0)), revision = 1)
        val refreshed = calculator.calculate(changed, marked(calculator.calculate(initial)))
        val accepted = refreshed.copy(rows = refreshed.rows.map { it.acknowledgeReview() })
        assertFalse(calculator.calculate(changed, accepted).rows.single().needsReview)
        assertEquals(200.0, accepted.rows.single().checkedQuantity!!, 0.0)
        val changedAgain = changed.copy(entries = listOf(item.copy(servings = 2.1)), revision = 2)
        assertTrue(calculator.calculate(changedAgain, accepted).rows.single().needsReview)
    }

    @Test fun reducingAProductDoesNotRequestAnUnnecessaryNewCheck() {
        val item = entry(food())
        val plan = workspace(item)
        val row = calculator.calculate(plan.copy(entries = listOf(item.copy(servings = .5)), revision = 1),
            marked(calculator.calculate(plan))).rows.single()
        assertEquals(ShoppingStatus.Bought, row.status)
        assertFalse(row.needsReview)
    }

    @Test fun unknownAmountBecomingKnownRequiresReviewIfAlreadyMarked() {
        val item = entry(food(quantity = null))
        val plan = workspace(item)
        val fixed = item.copy(recipe = item.recipe.copy(ingredients = listOf(item.recipe.ingredients.single().copy(quantity = 50.0))))
        val row = calculator.calculate(plan.copy(entries = listOf(fixed), revision = 1), marked(calculator.calculate(plan))).rows.single()
        assertTrue(row.needsReview)
    }

    @Test fun unchangedAcknowledgedUnknownAmountsDoNotLoopTheWarning() {
        val plan = workspace(entry(food(quantity = null)))
        val old = marked(calculator.calculate(plan))
        assertFalse(calculator.calculate(plan, old).rows.single().needsReview)
    }

    @Test fun moreServingsWithUnknownAmountRequiresAReview() {
        val item = entry(food(quantity = null))
        val plan = workspace(item)
        val row = calculator.calculate(plan.copy(entries = listOf(item.copy(servings = 2.0)), revision = 1),
            marked(calculator.calculate(plan))).rows.single()
        assertTrue(row.needsReview)
    }

    @Test fun removedProductsWithNotesRemainAsInactiveUserInformation() {
        val original = calculator.calculate(workspace(entry(food())))
        val saved = original.copy(rows = original.rows.map { it.copy(note = "Keep this brand") })
        val row = calculator.calculate(workspace().copy(revision = 1), saved).rows.single()
        assertFalse(row.inPlan)
        assertEquals("Keep this brand", row.note)
    }

    @Test fun removedUntouchedAutomaticProductsDisappear() {
        val old = calculator.calculate(workspace(entry(food())))
        assertTrue(calculator.calculate(workspace().copy(revision = 1), old).rows.isEmpty())
    }

    @Test fun removedMarkedProductsAreNotSilentlyLost() {
        val old = marked(calculator.calculate(workspace(entry(food()))))
        val row = calculator.calculate(workspace().copy(revision = 1), old).rows.single()
        assertFalse(row.inPlan)
        assertEquals(ShoppingStatus.Bought, row.status)
    }

    @Test fun generationIsDeterministicForUnchangedPlanContent() {
        val plan = workspace(entry(food()), entry(food("milk", unit = RecipeUnit.Milliliter)))
        assertEquals(calculator.calculate(plan), calculator.calculate(plan))
        assertEquals(calculator.calculate(plan), calculator.calculate(plan, calculator.calculate(plan)))
    }

    @Test fun revisionChangeMarksSnapshotStaleUntilRefresh() {
        val plan = workspace(entry(food()))
        val old = calculator.calculate(plan)
        assertFalse(old.isStale(plan))
        assertTrue(old.isStale(plan.copy(revision = 1)))
        assertFalse(calculator.calculate(plan.copy(revision = 1), old).isStale(plan.copy(revision = 1)))
    }

    @Test fun aDifferentWorkspaceCannotAccidentallyReuseUserMarks() {
        val old = calculator.calculate(workspace(entry(food())))
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(workspace().copy(id = "other"), old) }
    }

    @Test fun categoriesRemainAvailableForGrouping() {
        val plan = workspace(entry(food("carrot").copy(category = ShoppingCategory.Vegetables)),
            entry(food("milk").copy(category = ShoppingCategory.Dairy)))
        assertEquals(listOf(ShoppingCategory.Vegetables, ShoppingCategory.Dairy), calculator.calculate(plan).rows.map { it.category })
    }

    @Test fun copyingADayCreatesIndependentMealIdsAndReplacesTargetOnly() {
        val a = entry(food(), today)
        val b = entry(food("milk"), "2026-10-30")
        val c = entry(food("apple"), "2026-10-31")
        val result = workspace(a, b, c).copyDay(today, "2026-10-30") { "copied-id" }
        assertEquals(3, result.entries.size)
        assertEquals(a, result.entries.single { it.date == today })
        assertEquals(c, result.entries.single { it.date == "2026-10-31" })
        val copied = result.entries.single { it.date == "2026-10-30" }
        assertEquals("copied-id", copied.id)
        assertEquals(a.recipe, copied.recipe)
        assertNotEquals(a.id, copied.id)
        assertEquals(1L, result.revision)
    }

    @Test fun invalidPeriodsQuantitiesAndConversionFactsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { workspace().copy(dayCount = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { workspace().copy(dayCount = 8).validate() }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(workspace(entry(food(quantity = Double.NaN)))) }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(workspace(entry(food(quantity = 0.0)))) }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(workspace(entry(food(density = -1.0)))) }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(workspace(entry(food(pieceWeight = Double.POSITIVE_INFINITY)))) }
    }

    @Test fun serializedUnknownValuesAndUserFlagsRoundTrip() {
        val plan = workspace(entry(food(quantity = null)))
        val document = marked(calculator.calculate(plan)).copy(rows = marked(calculator.calculate(plan)).rows.map { it.copy(note = "Remember") })
        val json = Json { encodeDefaults = true }
        assertEquals(plan, json.decodeFromString<PlanningWorkspace>(json.encodeToString(plan)))
        assertEquals(document, json.decodeFromString<ShoppingDocument>(json.encodeToString(document)))
        assertTrue(json.encodeToString(document).contains("null"))
    }

    @Test fun originalDemoRecipesAreLicensedLocalizedAndHonestAboutMissingData() {
        assertEquals(6, DemoRecipes.all.size)
        assertTrue(DemoRecipes.all.all { it.demo && it.license == "MIT" && it.source.contains("illustrative") })
        assertTrue(DemoRecipes.all.all { it.localizedNames.keys.containsAll(listOf("ru", "kk", "en")) })
        assertEquals(1, DemoRecipes.all.count { !it.nutrients.complete })
        DemoRecipes.all.forEach { it.validate() }
    }
}
