package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TargetCalculatorTest {
    private val adult=Questionnaire(goal="maintain",age=30,height=175.0,weight=70.0,sex="male")
    @Test fun matchesServerGoldenAndNoGoalsForDiary() {
        assertEquals(DailyGoals(2638,132,88,330),TargetCalculator.calculate(adult))
        assertEquals(DailyGoals(),TargetCalculator.calculate(Questionnaire()))
        assertEquals(DailyGoals(2374,119,79,297),TargetCalculator.calculate(adult.copy(goal="lose")))
    }
    @Test fun applicabilityNeverClampsIntoARecommendation() {
        listOf(adult.copy(age=17),adult.copy(age=79),adult.copy(weight=40.0),adult.copy(special=true),adult.copy(sex="unspecified"),adult.copy(height=Double.NaN)).forEach {assertTrue(runCatching {TargetCalculator.calculate(it)}.isFailure)}
    }
    @Test fun registrationAndExistingLoginHaveDifferentRules() {
        assertFalse(AccountValidation.registrationPassword("old-password"))
        assertTrue(AccountValidation.registrationPassword("x".repeat(15)))
        assertEquals(15,AccountValidation.passwordLength("😀".repeat(15)))
        assertTrue(AccountValidation.registrationPassword("😀".repeat(15)))
        assertFalse(AccountValidation.email("only@"));assertFalse(AccountValidation.email("a@b"));assertTrue(AccountValidation.email("fixture@example.com"))
        assertFalse(AccountValidation.name("   "))
    }
    @Test fun pastedInvisibleEmailAndMalformedDotAtomsCannotSubmit() {
        listOf("fixture@example.com\u200B","fi\u200Bxture@example.com","fixture@example.com\uFEFF",
            "first..last@example.com",".first@example.com","first.@example.com","first,last@example.com",
            "first@-example.com","first@example..com").forEach {assertFalse(AccountValidation.email(it))}
        listOf(" fixture+tag@example.com ","first.last@example.co.uk","почта@пример.рф").forEach {assertTrue(AccountValidation.email(it))}
    }
    @Test fun quantityAlwaysUsesBaselineAndUnknownStaysUnknown() {
        val ingredient=PlanIngredient(name="Test",quantity=100.0,nutrition=PlanNutrients(120.0,5.0,4.0,16.0))
        assertEquals(60.0,ingredient.copy(quantity=50.0).total!!.calories,0.0)
        assertEquals(120.0,ingredient.copy(quantity=25.0).copy(quantity=100.0).total!!.calories,0.0)
        assertNull(ingredient.copy(nutrition=null).total)
        assertTrue(runCatching {ingredient.copy(quantity=Double.POSITIVE_INFINITY).validate()}.isFailure)
        assertNull(PlannedMeal(dish="Test",ingredients=listOf(ingredient,PlanIngredient(name="Unknown"))).total)
    }
}
