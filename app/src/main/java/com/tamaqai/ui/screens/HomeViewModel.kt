package com.tamaqai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.DiaryTotals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeUiState(
    val meals: List<Meal> = emptyList(), val drafts: List<Meal> = emptyList(),
    val calories: Int = 0, val protein: Int = 0, val fat: Int = 0, val carbs: Int = 0,
    val date: LocalDate = LocalDate.now(), val loading: Boolean = true, val error: String? = null,
    val incompleteNutritionCount: Int = 0,
) {
}

class HomeViewModel(private val mealRepository: MealRepository, private val ready: Deferred<Unit>? = null,
    private val text: (Int) -> String = { it.toString() }) : ViewModel() {
    private val date = MutableStateFlow<LocalDate?>(null)
    private val clockDate = flow { while (true) { emit(LocalDate.now()); delay(60_000) } }
    private val retry = MutableStateFlow(0)
    private val actionError = MutableStateFlow<String?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val entries = retry.flatMapLatest {
        mealRepository.getAllMeals().map { Result.success(it) }
            .onStart { ready?.await() }
            .catch { emit(Result.failure(it)) }
    }
    val uiState = combine(entries, date, actionError, clockDate) { entries, selectedDay, error, today ->
        val day = selectedDay ?: today
        val all = entries.getOrDefault(emptyList())
        val confirmed = DiaryTotals.forDate(all, day)
        HomeUiState(confirmed, all.filter { it.status != MealStatus.Confirmed || it.details.catalogPendingManual },
            confirmed.sumOf { it.nutrition.calories }, confirmed.sumOf { it.nutrition.protein },
            confirmed.sumOf { it.nutrition.fat }, confirmed.sumOf { it.nutrition.carbs }, day, false,
            error ?: entries.exceptionOrNull()?.let { "ui_error_001" },
            incompleteNutritionCount = DiaryTotals.incompleteCountForDate(all, day))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
    fun shiftDate(days: Long) { date.value = (date.value ?: LocalDate.now()).plusDays(days) }
    fun today() { date.value = null }
    fun selectDate(day: LocalDate) { date.value = day }
    fun retry() { actionError.value = null; retry.value++ }
    fun createManual(onReady: (String) -> Unit) = viewModelScope.launch {
        try {
            ready?.await()
            val timestamp = (date.value ?: LocalDate.now()).atTime(java.time.LocalTime.now()).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val meal = Meal(nutrition = Nutrition(text(com.tamaqai.R.string.new_meal), 0, 0, 0, 0), timestamp = timestamp, details = MealDetails(emptyList()))
            mealRepository.saveDraft(meal)
            onReady(meal.uid)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { actionError.value = "ui_error_002" }
    }
    fun open(uid: String, onReady: (String) -> Unit) = viewModelScope.launch {
        try { onReady(mealRepository.prepareEdit(uid)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { actionError.value = "ui_error_003" }
    }
}
