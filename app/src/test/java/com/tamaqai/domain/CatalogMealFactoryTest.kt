package com.tamaqai.domain

import com.tamaqai.domain.model.*
import com.tamaqai.domain.usecase.CatalogMealFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CatalogMealFactoryTest {
    private fun entry(nutrients:CatalogNutrients=CatalogNutrients(60.0,3.0,3.0,5.0))=CatalogEntry(
        "fixture:milk","fixture-v1",1,"product","Fixture milk",basis="per_100ml",nutrients=nutrients,
        source=CatalogSource("https://example.org/fixture","2026-10-20","fixture","test-only","user","test"))
    @Test fun volumeSnapshotHasNoInventedMassAndHistoryDoesNotFollowNewVersion() {
        val old=entry()
        val calculated=CachedCatalogCalculator.calculate(old,250.0,"ml")
        val meal=CatalogMealFactory.create(CatalogSelection(old,250.0,"ml",calculation=calculated))
        assertNull(meal.details.components.single().massGrams)
        assertEquals(150,meal.nutrition.calories)
        assertEquals(NutritionSource.Catalog,meal.source)
        val saved=Json.encodeToString(meal.details)
        val changed=old.copy(versionId="fixture-v2",nutrients=CatalogNutrients(100.0,4.0,5.0,10.0))
        assertEquals(250.0,CachedCatalogCalculator.calculate(changed,250.0,"ml").nutrients.calories!!,0.0)
        val restored=Json.decodeFromString<MealDetails>(saved)
        assertEquals(150.0,restored.components.single().calories,0.0)
        assertTrue(restored.catalogSnapshot!!.contains("fixture-v1"))
        assertFalse(restored.catalogSnapshot!!.contains("fixture-v2"))
    }
    @Test fun unknownNutrientsRemainMissingAndRequireManualConfirmation() {
        val unknown=entry(CatalogNutrients()).copy(kind="dish",basis="unknown")
        val meal=CatalogMealFactory.create(CatalogSelection(unknown,1.0,"portion"))
        assertTrue(meal.details.components.isEmpty())
        assertTrue(meal.details.catalogPendingManual)
        assertEquals(NutritionSource.Manual,meal.source)
        assertTrue(meal.details.catalogSnapshot!!.contains("manual_values_required"))
    }
    @Test fun oldDiaryJsonRemainsReadableWithoutSnapshot() {
        val old="""{"components":[{"name":"Existing","calories":100.0,"protein":5.0,"fat":3.0,"carbs":10.0}]}"""
        val details=Json.decodeFromString<MealDetails>(old)
        assertNull(details.catalogSnapshot)
        assertFalse(details.catalogPendingManual)
        assertEquals(100.0,details.components.single().calories,0.0)
    }
    @Test fun selectingOneRecognizedComponentKeepsOtherComponentsAndTheirPortions() {
        val original=Meal(nutrition=Nutrition("Lunch",500,20,10,60),source=NutritionSource.AiEstimate,
            details=MealDetails(listOf(MealComponent("Chicken",150.0,250.0,30.0,12.0,0.0),
                MealComponent("Drink",null,250.0,0.0,0.0,60.0)),portion=.5))
        val entry=entry()
        val selected=CatalogSelection(entry,100.0,"ml",calculation=CachedCatalogCalculator.calculate(entry,100.0,"ml"))
        val changed=CatalogMealFactory.replaceComponent(original,1,selected)
        assertEquals(original.details.components[0],changed.details.components[0])
        assertEquals(2,changed.details.components.size)
        assertEquals(185,changed.nutrition.calories) // half chicken + explicitly selected 100ml
        assertEquals(1.0,changed.details.components[1].consumedPortion!!,0.0)
        assertEquals(NutritionSource.AiEstimate,changed.source)
        assertTrue(changed.details.catalogSnapshot!!.contains("component_index"))
    }
    @Test fun selectingAnotherKnownComponentCannotErasePendingUnknownValues() {
        val original=Meal(nutrition=Nutrition("Lunch",300,10,10,40),source=NutritionSource.AiEstimate,
            details=MealDetails(listOf(MealComponent("Unknown dish",null,200.0,6.0,6.0,30.0),MealComponent("Drink",null,100.0,4.0,4.0,10.0))))
        val unknown=entry(CatalogNutrients()).copy(basis="unknown")
        val pending=CatalogMealFactory.replaceComponent(original,0,CatalogSelection(unknown,1.0,"portion"))
        val known=entry()
        val replaced=CatalogMealFactory.replaceComponent(pending,1,CatalogSelection(known,100.0,"ml",calculation=CachedCatalogCalculator.calculate(known,100.0,"ml")))
        assertTrue(replaced.details.catalogPendingManual)
        assertEquals(0,replaced.details.catalogPendingIndex)
        assertEquals(original.details.components[0],replaced.details.components[0])
    }
}
