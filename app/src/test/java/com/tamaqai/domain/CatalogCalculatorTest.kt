package com.tamaqai.domain

import com.tamaqai.domain.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Synthetic arithmetic fixtures, never seeded into the shared catalogue or user diary. */
class CatalogCalculatorTest {
    private val source = CatalogSource("https://example.org/test-only", "2026-10-20", "fixture", "test-only", "estimate", "test")
    private val base = CatalogEntry("fixture", "version-1", 1, "product", "Test food", basis = "per_100g",
        nutrients = CatalogNutrients(120.0, 5.0, 4.0, 16.0), source = source)

    @Test fun per100gAndServingAreCalculatedFromFrozenValues() {
        val selected = base.copy(portionGrams = 150.0)
        assertEquals(180.0, CachedCatalogCalculator.calculate(selected, 150.0, "g").nutrients.calories!!, 0.0)
        assertEquals(180.0, CachedCatalogCalculator.calculate(selected, 1.0, "portion").nutrients.calories!!, 0.0)
        assertEquals(45.0, CachedCatalogCalculator.calculate(selected, .25, "portion").nutrients.calories!!, 0.0)
        repeat(20) { assertEquals(180.0, CachedCatalogCalculator.calculate(selected, 150.0, "g").nutrients.calories!!, 0.0) }
        assertEquals(120.0, selected.nutrients.calories!!, 0.0)
    }

    @Test fun per100mlNeverCreatesMassOrAssumesDensity() {
        val drink = base.copy(basis = "per_100ml", portionMl = 250.0)
        val result = CachedCatalogCalculator.calculate(drink, 250.0, "ml")
        assertNull(result.massGrams)
        assertEquals(250.0, result.volumeMl!!, 0.0)
        assertEquals(300.0, result.nutrients.calories!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) { CachedCatalogCalculator.calculate(drink, 250.0, "g") }
        val measuredDensity = drink.copy(portionGrams = 260.0)
        assertEquals(130.0, CachedCatalogCalculator.calculate(measuredDensity, 125.0, "ml").massGrams!!, 0.0)
    }

    @Test fun perServingWithoutPublishedWeightCannotBeConvertedToGrams() {
        val dish = base.copy(kind = "dish", basis = "per_portion")
        assertEquals(60.0, CachedCatalogCalculator.calculate(dish, .5, "portion").nutrients.calories!!, 0.0)
        assertNull(CachedCatalogCalculator.calculate(dish, 1.0, "portion").massGrams)
        assertThrows(IllegalArgumentException::class.java) { CachedCatalogCalculator.calculate(dish, 100.0, "g") }
    }

    @Test fun variantsAndAddonsStayExplicitAndUnknownMassPropagates() {
        val extra = base.copy(id = "extra", versionId = "extra-v1", basis = "per_portion", nutrients = CatalogNutrients(20.0, 1.0, 0.0, 4.0))
        val small = base.copy(kind = "dish", basis = "per_portion", portionGrams = 200.0, addonIds = listOf(extra.id), variant = "Small")
        val large = small.copy(versionId = "large-v1", portionGrams = 350.0, nutrients = CatalogNutrients(210.0, 8.0, 7.0, 28.0), variant = "Large")
        assertEquals(120.0, CachedCatalogCalculator.calculate(small, 1.0, "portion").nutrients.calories!!, 0.0)
        assertEquals(210.0, CachedCatalogCalculator.calculate(large, 1.0, "portion").nutrients.calories!!, 0.0)
        val withExtra = CachedCatalogCalculator.calculate(small, 1.0, "portion", listOf(Triple(extra, 1.0, "portion")))
        assertEquals(140.0, withExtra.nutrients.calories!!, 0.0)
        assertNull(withExtra.massGrams)
        assertThrows(IllegalArgumentException::class.java) {
            CachedCatalogCalculator.calculate(base, 100.0, "g", listOf(Triple(extra, 1.0, "portion")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CachedCatalogCalculator.calculate(small, 1.0, "portion", listOf(Triple(extra, 1.0, "portion"), Triple(extra, 1.0, "portion")))
        }
    }

    @Test fun missingNutritionRemainsUnknownAndRejectsCalculation() {
        val missing = base.copy(nutrients = CatalogNutrients(calories = 120.0))
        assertFalse(missing.canCalculate)
        assertNull(Json.decodeFromString<CatalogEntry>(Json.encodeToString(missing)).nutrients.protein)
        assertThrows(IllegalArgumentException::class.java) { CachedCatalogCalculator.calculate(missing, 100.0, "g") }
        assertThrows(IllegalArgumentException::class.java) { CachedCatalogCalculator.calculate(base.copy(basis = "unknown"), 1.0, "portion") }
    }

    @Test fun historicalSnapshotDoesNotFollowUpdatedCatalogueAndRoundTrips() {
        val saved = CachedCatalogCalculator.calculate(base, 150.0, "g")
        val serialized = Json.encodeToString(saved)
        val updated = base.copy(versionId = "version-2", revision = 2, nutrients = CatalogNutrients(240.0, 10.0, 8.0, 32.0))
        assertEquals(360.0, CachedCatalogCalculator.calculate(updated, 150.0, "g").nutrients.calories!!, 0.0)
        val restored = Json.decodeFromString<CatalogCalculation>(serialized)
        assertEquals(180.0, restored.nutrients.calories!!, 0.0)
        assertTrue(restored.snapshot.toString().contains("version-1"))
        assertFalse(restored.snapshot.toString().contains("version-2"))
    }
}
