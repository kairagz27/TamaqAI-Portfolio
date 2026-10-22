package com.tamaqai.domain

import com.tamaqai.domain.model.Meal
import com.tamaqai.domain.model.MealComponent
import com.tamaqai.domain.model.MealDetails
import com.tamaqai.domain.model.MealStatus
import com.tamaqai.domain.model.Nutrition
import com.tamaqai.domain.usecase.DiaryTotals
import com.tamaqai.domain.usecase.ProgressSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CatalogCompletenessTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun meal(id: String, date: LocalDate = day, calories: Int = 120,
        status: MealStatus = MealStatus.Confirmed, unknown: Boolean = false) = Meal(
        uid = id, timestamp = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        zoneId = "UTC", nutrition = Nutrition("Catalogue fixture", calories, 5, 4, 16), status = status,
        details = MealDetails(listOf(MealComponent("Fixture", null, calories.toDouble(), 5.0, 4.0, 16.0)),
            catalogPendingManual = unknown, catalogSnapshot = "{\"version_id\":\"frozen-v1\"}"))

    @Test fun unpublishedValuesAreExcludedAndMarkOnlyTheirStoredDateIncomplete() {
        val known = meal("known")
        val unknown = meal("unknown", status = MealStatus.NeedsReview, unknown = true, calories = 0)
        val tomorrow = meal("tomorrow", day.plusDays(1), status = MealStatus.NeedsReview, unknown = true)
        val rows = listOf(known, unknown, tomorrow)
        assertEquals(listOf(known), DiaryTotals.forDate(rows, day, ZoneOffset.UTC))
        assertEquals(1, DiaryTotals.incompleteCountForDate(rows, day, ZoneOffset.UTC))
        assertEquals(1, DiaryTotals.incompleteCountForDate(rows, day.plusDays(1), ZoneOffset.UTC))
        assertEquals(0, DiaryTotals.incompleteCountForDate(rows, day.minusDays(1), ZoneOffset.UTC))
    }

    @Test fun defensiveTotalsRejectLegacyConfirmedUnknownWithoutDiscardingExplicitKnownZero() {
        val unknown = meal("legacy-unknown", unknown = true, calories = 900)
        val zero = meal("known-zero", calories = 0)
        assertFalse(DiaryTotals.isCounted(unknown))
        assertTrue(DiaryTotals.isCounted(zero))
        assertEquals(listOf(zero), DiaryTotals.forDate(listOf(unknown, zero), day, ZoneOffset.UTC))
        assertEquals(1, DiaryTotals.incompleteCountForDate(listOf(unknown, zero), day, ZoneOffset.UTC))
    }

    @Test fun editingDraftKeepsPriorConfirmedSnapshotUntilReplacementIsConfirmed() {
        val original = meal("confirmed")
        val pendingEdit = meal("edit", status = MealStatus.NeedsReview, unknown = true)
            .copy(originalUid = original.uid)
        assertEquals(0, DiaryTotals.incompleteCountForDate(listOf(original, pendingEdit), day, ZoneOffset.UTC))
        assertEquals(listOf(original), DiaryTotals.forDate(listOf(original, pendingEdit), day, ZoneOffset.UTC))
        assertEquals(1, DiaryTotals.incompleteCountForDate(listOf(pendingEdit), day, ZoneOffset.UTC))
        assertEquals("{\"version_id\":\"frozen-v1\"}", original.details.catalogSnapshot)
        val legacyUnknown = original.copy(details = original.details.copy(catalogPendingManual = true))
        assertEquals(1, DiaryTotals.incompleteCountForDate(listOf(legacyUnknown, pendingEdit), day, ZoneOffset.UTC))
    }

    @Test fun resolvingManualValuesClearsWarningAndOrdinaryDraftsDoNotPretendToBeMissingCatalogueValues() {
        val pending = meal("pending", status = MealStatus.NeedsReview, unknown = true)
        val ordinary = meal("manual-draft", status = MealStatus.Draft)
        assertEquals(1, DiaryTotals.incompleteCountForDate(listOf(pending, ordinary), day, ZoneOffset.UTC))
        val resolved = pending.copy(status = MealStatus.Confirmed,
            details = pending.details.copy(catalogPendingManual = false))
        assertEquals(0, DiaryTotals.incompleteCountForDate(listOf(resolved, ordinary), day, ZoneOffset.UTC))
        assertEquals(listOf(resolved), DiaryTotals.forDate(listOf(resolved, ordinary), day, ZoneOffset.UTC))
        assertEquals(pending.details.catalogSnapshot, resolved.details.catalogSnapshot)
    }

    @Test fun unknownOnlyDaysAreNotZeroObservationsAndMixedDaysRemainExplicitlyPartial() {
        val rows = listOf(meal("known"), meal("pending", status = MealStatus.NeedsReview, unknown = true),
            meal("unknown-only-day", day.plusDays(1), calories = 0, unknown = true),
            meal("known-zero", day.plusDays(2), calories = 0))
        val observed = ProgressSummary.observedDays(rows, day, day.plusDays(2))
        assertEquals(listOf(day, day.plusDays(2)), observed.map { it.date })
        assertEquals(listOf(120, 0), observed.map { it.calories })
        assertEquals(listOf(1, 0), observed.map { it.incompleteNutritionCount })
    }

    @Test fun warningUsesStoredTimezoneAlongsideTheTotal() {
        val pending = meal("timezone", status = MealStatus.NeedsReview, unknown = true).copy(
            timestamp = java.time.Instant.parse("2026-10-19T20:00:00Z").toEpochMilli(), zoneId = "Asia/Qyzylorda")
        assertEquals(1, DiaryTotals.incompleteCountForDate(listOf(pending), day, ZoneOffset.UTC))
        assertEquals(0, DiaryTotals.incompleteCountForDate(listOf(pending), day.minusDays(1), ZoneOffset.UTC))
    }
}
