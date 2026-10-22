package com.tamaqai.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.*
import com.tamaqai.domain.model.MealStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run only after the demo journey and an explicit host force-stop. */
class PortfolioRestartTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun syntheticSessionAndSingleConfirmedMealSurviveColdStart()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("portfolioRestartProbe")=="true")
        check(BuildConfig.APPLICATION_ID=="com.tamaqai.demo" && BuildConfig.PORTFOLIO_DEMO)
        val container=(rule.activity.application as TamaqApplication).container
        container.ready.await()
        assertNotEquals("guest",container.owner)
        assertTrue(container.account.load().verified)
        val meals=container.mealRepository.getAllMeals().first()
        assertEquals(1,meals.count {it.uid=="portfolio-photo" && it.status==MealStatus.Confirmed})
        assertEquals(650,meals.filter {it.status==MealStatus.Confirmed}.sumOf {it.nutrition.calories})
        rule.waitUntil(20_000) {rule.onAllNodesWithTag("root-screen-home").fetchSemanticsNodes().isNotEmpty()}
    }
}
