package com.tamaqai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamaqai.R
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.data.local.UserSettings
import com.tamaqai.data.repository.DailyRepository
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.DiaryTotals
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

data class DailyState(val meals: List<Meal> = emptyList(), val favorites: List<FavoriteMeal> = emptyList(),
    val weights: List<WeightEntry> = emptyList(), val goals: DailyGoals = DailyGoals(),
    val loading: Boolean = true, val error: Int? = null, val notice: Int? = null, val busy: Boolean = false,
    val incompleteCatalogMeals: List<Meal> = emptyList())

class DailyViewModel(private val meals: MealRepository, private val daily: DailyRepository,
    private val settings: UserSettings, private val photos: PhotoStore, private val ready: Deferred<Unit>) : ViewModel() {
    private val action = MutableStateFlow(DailyState())
    private val retry = MutableStateFlow(0)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val entries = retry.flatMapLatest {
        combine(meals.getAllMeals(), daily.favorites, daily.weights, settings.dailyGoals) { m, f, w, g ->
            DailyState(m, f, w, g, loading = false, incompleteCatalogMeals = DiaryTotals.incompleteCatalogEntries(m))
        }.onStart { ready.await() }.catch { emit(DailyState(loading = false, error = R.string.diary_read_error)) }
    }
    val state = combine(entries, action) { values, status -> values.copy(error = status.error ?: values.error,
        notice = status.notice, busy = status.busy) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyState())
    fun dismissNotice() { action.value = DailyState(loading = false) }
    fun retry() { dismissNotice(); retry.value++ }
    private fun perform(notice: Int? = null, work: suspend () -> Unit) {
        if (action.value.busy) return
        action.value = DailyState(loading = false, busy = true)
        viewModelScope.launch {
            try { ready.await(); work(); action.value = DailyState(loading = false, notice = notice) }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) { action.value = DailyState(loading = false,
                error = if (e.message == "weight_date_exists") R.string.weight_date_exists else R.string.local_save_error) }
        }
    }
    fun saveGoals(goals: DailyGoals) = perform(R.string.goals_saved) { withContext(Dispatchers.IO) { settings.save(goals) } }
    fun favorite(uid: String) = perform(R.string.favorite_saved) { daily.favorite(requireNotNull(meals.getMeal(uid))) }
    fun removeFavorite(id: String) = perform { daily.removeFavorite(id) }
    fun repeat(uid: String, date: LocalDate, open: (String) -> Unit) = perform {
        val draft = MealCopies.onDate(requireNotNull(meals.getMeal(uid)), date)
        meals.saveDraft(draft); open(draft.uid)
    }
    fun useFavorite(favorite: FavoriteMeal, date: LocalDate, open: (String) -> Unit) = perform {
        val draft = MealCopies.fromFavorite(favorite, date)
        meals.saveDraft(draft); open(draft.uid)
    }
    fun saveWeight(entry: WeightEntry) = perform(R.string.weight_saved) { daily.saveWeight(entry) }
    fun deleteWeight(id: String) = perform { daily.deleteWeight(id) }
    fun deleteMeal(uid: String) = perform {
        val meal = meals.getMeal(uid)
        meals.delete(uid)
        withContext(Dispatchers.IO) { photos.delete(meal?.details?.photoName) }
    }
    fun clearData() = perform(R.string.local_data_deleted) {
        val ownedPhotos = meals.getAllMeals().first().mapNotNull { it.details.photoName }
        daily.clearLocalRecords()
        withContext(Dispatchers.IO) { ownedPhotos.forEach(photos::delete); settings.save(DailyGoals()) }
    }
}
