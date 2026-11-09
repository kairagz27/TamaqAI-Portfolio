package com.tamaqai.ui

import com.tamaqai.R
import com.tamaqai.domain.model.*
import com.tamaqai.ui.screens.DailyState
import com.tamaqai.ui.screens.dailyReadStates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DailyReadRecoveryTest {
    @Test fun errorKeepsLastGoodSnapshotAndSuccessfulEmptyRetryClearsIt() = runTest {
        val retry = MutableStateFlow(0)
        val failure = MutableSharedFlow<Unit>()
        val meal = Meal(nutrition = Nutrition("Fixture", 120, 4, 3, 20), status = MealStatus.Confirmed, details = MealDetails(emptyList()))
        var snapshot = DailyState(meals = listOf(meal), goals = DailyGoals(2100, 100, 70, 250), loading = false)
        var current = DailyState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            dailyReadStates(retry, CompletableDeferred(Unit)) {
                flow { emit(snapshot); failure.first(); throw IOException("Read failure") }
            }.collect { current = it }
        }
        runCurrent()
        failure.emit(Unit); runCurrent()
        assertEquals(snapshot.copy(error = R.string.diary_read_error), current)
        snapshot = DailyState(loading = false)
        retry.value++; runCurrent()
        assertEquals(snapshot, current)
        failure.emit(Unit); runCurrent()
        assertEquals(snapshot.copy(error = R.string.diary_read_error), current)
    }

    @Test fun initialFailureIsEmptyAndRetryRecoversWithoutOldOwnerSnapshot() = runTest {
        val retry = MutableStateFlow(0)
        var fail = true
        var current = DailyState()
        val snapshot = DailyState(goals = DailyGoals(1800, 90, 60, 200), loading = false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            dailyReadStates(retry, CompletableDeferred(Unit)) {
                flow { if (fail) throw IOException("Read failure"); emit(snapshot) }
            }.collect { current = it }
        }
        runCurrent()
        assertEquals(DailyState(loading = false, error = R.string.diary_read_error), current)
        fail = false; retry.value++; runCurrent()
        assertEquals(snapshot, current)
    }
}
