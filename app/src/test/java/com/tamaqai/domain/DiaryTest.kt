package com.tamaqai.domain

import com.tamaqai.domain.model.*
import com.tamaqai.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DiaryTest {
    private val calculator = NutritionCalculator()
    private val component = MealComponent("Порция", 100.0, 120.0, 5.0, 4.0, 16.0)
    @Test fun portionScalesSnapshotWithoutChangingReference() {
        val total = calculator.calculate("Еда", listOf(component), 1.5)
        assertEquals(Nutrition("Еда", 180, 8, 6, 24), total)
        assertEquals(100.0, component.massGrams!!, 0.0)
    }
    @Test fun aggregatesBeforeRounding() {
        val c = component.copy(calories = 0.4, protein = 0.4, fat = 0.4, carbs = 0.4)
        assertEquals(1, calculator.calculate("Еда", listOf(c, c), 1.0).calories)
    }
    @Test fun rejectsNonFiniteNegativeAndExcessiveValues() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { calculator.calculate("Еда", listOf(component.copy(protein = value)), 1.0) }
        }
        listOf(0.0, Double.NaN, 21.0).forEach { portion ->
            assertThrows(IllegalArgumentException::class.java) { calculator.calculate("Еда", listOf(component), portion) }
        }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate("Еда", listOf(component.copy(massGrams = -2.0)), 1.0) }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate("Еда", listOf(component.copy(calories = 10000.0)), 2.0) }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate("Еда", emptyList(), 1.0) }
    }
    @Test fun energyMismatchIsWarningWithTolerance() {
        assertFalse(calculator.hasEnergyWarning(Nutrition("Еда", 120, 5, 4, 16)))
        assertTrue(calculator.hasEnergyWarning(Nutrition("Еда", 500, 5, 4, 16)))
    }
    @Test fun draftsExcludedAndSavedTimezoneRespected() {
        val timestamp = Instant.parse("2026-09-30T20:00:00Z").toEpochMilli()
        val meal = Meal(nutrition = Nutrition("Еда", 120, 5, 4, 16), timestamp = timestamp,
            zoneId = "Asia/Qyzylorda", status = MealStatus.Confirmed, details = MealDetails(listOf(component)))
        val list = DiaryTotals.forDate(listOf(meal, meal.copy(uid = "draft", status = MealStatus.NeedsReview)), LocalDate.of(2026,10,1), ZoneOffset.UTC)
        assertEquals(listOf(meal), list)
        assertTrue(DiaryTotals.forDate(listOf(meal), LocalDate.of(2026,9,30), ZoneOffset.UTC).isEmpty())
    }
    @Test fun analysisContractRejectsWrongKeyNoFoodAndInvalidNumbers() {
        val result = AnalysisResult("a", "1", "meal-photo-v1", "gpt-6-luna", false,
            FoodResult("food", "Еда", listOf(AnalysisComponent("Еда", null, 100.0, 5.0, 2.0, 15.0)), null, null))
        result.validate("a")
        assertThrows(IllegalArgumentException::class.java) { result.validate("b") }
        assertThrows(IllegalArgumentException::class.java) { result.copy(result = result.result.copy(status = "no_food")).validate("a") }
        assertThrows(IllegalArgumentException::class.java) { result.copy(result = result.result.copy(status = "ambiguous")).validate("a") }
        assertThrows(IllegalArgumentException::class.java) { result.copy(result = result.result.copy(components = listOf(result.result.components[0].copy(fat = Double.NaN)))).validate("a") }
    }
}
