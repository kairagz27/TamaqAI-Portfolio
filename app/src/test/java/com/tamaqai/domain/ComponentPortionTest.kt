package com.tamaqai.domain

import com.tamaqai.domain.model.*
import com.tamaqai.domain.usecase.NutritionCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ComponentPortionTest {
    private val calculator = NutritionCalculator()
    private val chicken = MealComponent("Курица",180.0,300.0,34.0,12.0,0.0)
    private val rice = MealComponent("Рис",160.0,208.0,4.0,1.0,45.0)
    @Test fun onlyRiceChangesAndAllReturnsToBaseline() {
        val originals = listOf(chicken,rice)
        assertEquals(404, calculator.calculate("Боул", listOf(chicken,rice.copy(consumedPortion=.5)),1.0).calories)
        repeat(20) {
            assertEquals(352,calculator.calculate("Боул",listOf(chicken,rice.copy(consumedPortion=.25)),1.0).calories)
            assertEquals(508,calculator.calculate("Боул",originals,1.0).calories)
        }
        assertEquals(208.0,rice.calories,0.0)
    }
    @Test fun overrideIsIndependentOfWholeMealAndZeroIsAllowed() {
        assertEquals(404, calculator.calculate("Боул",listOf(chicken.copy(consumedPortion=1.0),rice),.5).calories)
        assertEquals(300, calculator.calculate("Боул",listOf(chicken,rice.copy(consumedPortion=0.0)),1.0).calories)
    }
    @Test fun sumIsRoundedOnceAndFractionsAreValidated() {
        val c = chicken.copy(calories=.6,protein=0.0,fat=0.0,carbs=0.0,consumedPortion=.5)
        assertEquals(1,calculator.calculate("Еда",listOf(c,c,c),1.0).calories)
        listOf(-.1,Double.NaN,Double.POSITIVE_INFINITY,20.1).forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) { calculator.calculate("Еда",listOf(chicken.copy(consumedPortion=amount)),1.0) }
        }
    }
    @Test fun oldJsonHasSameMeaningAndNewValuesRoundTrip() {
        val old = """{"components":[{"name":"Рис","massGrams":160.0,"calories":208.0,"protein":4.0,"fat":1.0,"carbs":45.0}],"portion":1.5}"""
        val details = Json.decodeFromString<MealDetails>(old)
        assertNull(details.components.single().consumedPortion)
        assertEquals(312,calculator.calculate("Еда",details.components,details.portion).calories)
        val changed = details.copy(components=listOf(rice.copy(consumedPortion=.5,
            massReference=ComponentMassReference(160.0,208.0,4.0,1.0,45.0))))
        assertEquals(changed,Json.decodeFromString<MealDetails>(Json.encodeToString(changed)))
    }
}
