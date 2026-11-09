package com.tamaqai.ui

import androidx.lifecycle.viewModelScope
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.ui.screens.HomeViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeReadRecoveryTest {
    private val repo = FailingMeals()
    private lateinit var vm: HomeViewModel
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()); vm = HomeViewModel(repo) }
    @After fun teardown() { vm.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun readFailureRetainsMealsDraftsAndTotalsThenRetryRefreshes() = runTest {
        val meal = fixture("saved", MealStatus.Confirmed)
        val draft = fixture("draft", MealStatus.PendingUpload)
        repo.snapshot = listOf(meal, draft)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        repo.failure.emit(Unit); runCurrent()
        assertEquals(listOf(meal), vm.uiState.value.meals)
        assertEquals(listOf(draft), vm.uiState.value.drafts)
        assertEquals(120, vm.uiState.value.calories)
        assertEquals("ui_error_001", vm.uiState.value.error)
        vm.shiftDate(1); runCurrent()
        assertTrue(vm.uiState.value.meals.isEmpty())
        assertEquals(0, vm.uiState.value.calories)
        vm.today(); runCurrent()
        assertEquals(120, vm.uiState.value.calories)
        repo.snapshot = emptyList()
        vm.retry(); runCurrent()
        assertTrue(vm.uiState.value.meals.isEmpty())
        assertTrue(vm.uiState.value.drafts.isEmpty())
        assertEquals(0, vm.uiState.value.calories)
        assertNull(vm.uiState.value.error)
        repo.failure.emit(Unit); runCurrent()
        assertTrue(vm.uiState.value.meals.isEmpty())
        assertEquals("ui_error_001", vm.uiState.value.error)
    }

    @Test fun initialReadFailureDoesNotInventMealsAndCanRetry() = runTest {
        repo.failInitially = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.meals.isEmpty())
        assertEquals("ui_error_001", vm.uiState.value.error)
        repo.failInitially = false
        repo.snapshot = listOf(fixture("saved", MealStatus.Confirmed))
        vm.retry(); runCurrent()
        assertEquals(120, vm.uiState.value.calories)
        assertNull(vm.uiState.value.error)
    }

    private fun fixture(uid: String, status: MealStatus) = Meal(uid = uid,
        nutrition = Nutrition("Fixture", 120, 4, 3, 20), status = status,
        details = MealDetails(listOf(MealComponent("Fixture", null, 120.0, 4.0, 3.0, 20.0))))
    private class FailingMeals : MealRepository {
        var snapshot = emptyList<Meal>()
        var failInitially = false
        val failure = MutableSharedFlow<Unit>()
        override fun getAllMeals(): Flow<List<Meal>> = flow {
            if (failInitially) throw IOException("Test read failure")
            emit(snapshot)
            failure.first()
            throw IOException("Test read failure")
        }
        override suspend fun getMeal(uid: String) = snapshot.find { it.uid == uid }
        override suspend fun saveDraft(meal: Meal) = error("Unexpected save")
        override suspend fun prepareEdit(uid: String) = uid
        override suspend fun confirm(meal: Meal) = error("Unexpected confirm")
        override suspend fun delete(uid: String) = Unit
        override suspend fun recoverInterruptedAnalyses() = Unit
    }
}
