package com.tamaqai.domain

import com.tamaqai.domain.model.*
import com.tamaqai.domain.usecase.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DailyProductTest {
    private val day = LocalDate.of(2026, 10, 4)
    private val original = Meal(id = 13, nutrition = Nutrition("Еда", 240, 10, 8, 32), status = MealStatus.Confirmed,
        details = MealDetails(listOf(MealComponent("Еда", 100.0, 120.0, 5.0, 4.0, 16.0)), portion = 2.0,
            photoName = "original.jpg", analysisId = "original-request"), mealType = MealType.Lunch)
    @Test fun copyKeepsSnapshotButDetachesIdsPhotosAndAnalysis() {
        val copy = MealCopies.onDate(original, day)
        assertNotEquals(original.uid, copy.uid)
        assertEquals(0L, copy.id)
        assertNull(copy.originalUid)
        assertNull(copy.details.photoName)
        assertNull(copy.details.analysisId)
        assertEquals(original.nutrition, copy.nutrition)
        assertEquals(original.details.components, copy.details.components)
        assertEquals(2.0, copy.details.portion, 0.0)
        assertEquals(MealStatus.NeedsReview, copy.status)
        assertEquals(MealType.Lunch, copy.mealType)
        assertEquals("original-request", original.details.analysisId)
    }
    @Test fun templateCopiesAreIndependentAndRequireConfirmation() {
        val favorite = FavoriteMeal(nutrition = original.nutrition, details = original.details, type = original.mealType, source = original.source)
        val a = MealCopies.fromFavorite(favorite, day)
        val b = MealCopies.fromFavorite(favorite, day)
        assertNotEquals(a.uid, b.uid)
        assertTrue(DiaryTotals.forDate(listOf(a, b), day).isEmpty())
        val edited = a.copy(details = a.details.copy(portion = 3.0))
        assertEquals(360, NutritionCalculator().calculate(edited.nutrition.name, edited.details.components, edited.details.portion).calories)
        assertEquals(2.0, favorite.details.portion, 0.0)
    }
    @Test fun averagesExcludeMissingDaysAndDraftsButIncludeExplicitZeroMeals() {
        fun dated(offset: Long, calories: Int) = original.copy(uid = "day-$offset", timestamp = day.plusDays(offset).atTime(12,0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), nutrition = original.nutrition.copy(calories = calories))
        val entries = listOf(dated(0, 240), dated(2, 0), dated(1, 1000).copy(status = MealStatus.Draft))
        val points = ProgressSummary.observedDays(entries, day, day.plusDays(2))
        assertEquals(listOf(day, day.plusDays(2)), points.map { it.date })
        assertEquals(120.0, points.map { it.calories }.average(), 0.0)
    }
    @Test fun goalsHaveNoImplicitNormAndRejectInvalidNumbers() {
        assertTrue(DailyGoals().isEmpty)
        DailyGoals(calories = 2300, protein = 100).validate()
        assertThrows(IllegalArgumentException::class.java) { DailyGoals(calories = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { DailyGoals(fat = 1001).validate() }
    }
    @Test fun weightRejectsInvalidNumbers() {
        WeightEntry(date = day, kilograms = 70.5).validate(day)
        assertThrows(IllegalArgumentException::class.java) { WeightEntry(date = day.plusDays(1), kilograms = 70.5).validate(day) }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 501.0).forEach { kg ->
            assertThrows(IllegalArgumentException::class.java) { WeightEntry(date = day, kilograms = kg).validate(day) }
        }
    }
}
