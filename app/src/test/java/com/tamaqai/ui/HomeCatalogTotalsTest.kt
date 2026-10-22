package com.tamaqai.ui

import androidx.lifecycle.viewModelScope
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.ui.screens.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCatalogTotalsTest {
    private val repository = MemoryMeals()
    private lateinit var vm: HomeViewModel
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        vm = HomeViewModel(repository)
    }
    @After fun teardown() { vm.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun homeCountsKnownConfirmedValuesAndExposesUnresolvedCatalogueWarning() = runTest {
        val known = fixture("known", 120, MealStatus.Confirmed)
        // Stale zero/numeric placeholders must not enter totals, even with a legacy Confirmed status.
        val unknown = fixture("unknown", 900, MealStatus.Confirmed, unknown = true)
        repository.rows.value = listOf(known, unknown)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        assertEquals(120, vm.uiState.value.calories)
        assertEquals(listOf(known), vm.uiState.value.meals)
        assertEquals(listOf(unknown), vm.uiState.value.drafts)
        assertEquals(1, vm.uiState.value.incompleteNutritionCount)

        repository.rows.value = listOf(known, unknown.copy(nutrition = unknown.nutrition.copy(calories = 80),
            details = unknown.details.copy(catalogPendingManual = false)))
        runCurrent()
        assertEquals(200, vm.uiState.value.calories)
        assertEquals(0, vm.uiState.value.incompleteNutritionCount)
        assertTrue(vm.uiState.value.drafts.isEmpty())
    }

    @Test fun unknownOnlyDayShowsIncompleteKnownSubtotalAndChangingDayDoesNotLeakWarning() = runTest {
        val unknown = fixture("unknown", 0, MealStatus.NeedsReview, unknown = true)
        repository.rows.value = listOf(unknown)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        assertEquals(0, vm.uiState.value.calories)
        assertEquals(1, vm.uiState.value.incompleteNutritionCount)
        vm.shiftDate(1); runCurrent()
        assertEquals(0, vm.uiState.value.incompleteNutritionCount)
        vm.today(); runCurrent()
        assertEquals(1, vm.uiState.value.incompleteNutritionCount)
    }

    private fun fixture(uid: String, calories: Int, status: MealStatus, unknown: Boolean = false) = Meal(
        uid = uid, nutrition = Nutrition("Fixture", calories, 4, 3, 20), status = status,
        details = MealDetails(listOf(MealComponent("Fixture", null, calories.toDouble(), 4.0, 3.0, 20.0)),
            catalogPendingManual = unknown))

    private class MemoryMeals : MealRepository {
        val rows = MutableStateFlow<List<Meal>>(emptyList())
        override fun getAllMeals() = rows
        override suspend fun getMeal(uid: String) = rows.value.find { it.uid == uid }
        override suspend fun saveDraft(meal: Meal) { rows.value = rows.value.filterNot { it.uid == meal.uid } + meal }
        override suspend fun prepareEdit(uid: String) = uid
        override suspend fun confirm(meal: Meal) = error("No persistence or provider calls in this regression")
        override suspend fun delete(uid: String) { rows.value = rows.value.filterNot { it.uid == uid } }
        override suspend fun recoverInterruptedAnalyses() = Unit
    }
}
