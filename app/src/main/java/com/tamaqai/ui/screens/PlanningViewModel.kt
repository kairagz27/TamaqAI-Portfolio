package com.tamaqai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamaqai.R
import com.tamaqai.data.contracts.PlanStore
import com.tamaqai.data.local.UserSettings
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.PlanningRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

data class PlanningUiState(
    val workspace: PlanningWorkspace? = null,
    val shopping: ShoppingDocument? = null,
    val recipes: List<Recipe> = emptyList(),
    val goals: Map<String, DailyGoals> = emptyMap(),
    val legacyPlanCount: Int = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: Int? = null,
    val notice: Int? = null,
) {
    val shoppingStale get() = workspace != null && (shopping == null || shopping.isStale(workspace))
}

/** All plan and shopping mutations are local. Server refresh is owned by the explicit legacy screen. */
class PlanningViewModel(
    private val repository: PlanningRepository,
    private val settings: UserSettings,
    private val legacyPlans: PlanStore,
    private val ready: Deferred<Unit>? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private data class Action(val loading: Boolean = true, val busy: Boolean = false,
                              val error: Int? = null, val notice: Int? = null,
                              val ownerInvalid: Boolean = false)
    private val action = MutableStateFlow(Action())
    private val retry = MutableStateFlow(0)
    private val recovery = PlanningWorkspaceRecovery()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val values = retry.flatMapLatest { combine(
        repository.workspace, repository.shopping, settings.history, legacyPlans.plans, action,
    ) { workspace, shopping, _, legacy, status ->
        if (status.ownerInvalid) PlanningUiState(loading = false, error = R.string.account_login_again)
        else PlanningUiState(workspace, shopping, repository.recipes,
            workspace?.dates?.associateWith { settings.onDate(LocalDate.parse(it)) } ?: emptyMap(),
            legacy.size, status.loading, status.busy, status.error, status.notice)
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        // Clear the previous owner snapshot rather than keeping it on a failed/stale subscription.
        emit(PlanningUiState(loading = false, error = if (failure.message in OWNER_FAILURES)
            R.string.account_login_again else R.string.mp_error_storage))
    } }
    val state: StateFlow<PlanningUiState> = values.onEach { value ->
        // Local reset may delete these rows while this activity-scoped model survives.
        // Recover once for this missing snapshot, never retry an error or old identity.
        if (recovery.shouldRecover(value.workspace != null, value.loading, value.busy,
                value.error != null, !action.value.ownerInvalid)) initialize()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningUiState())

    init { initialize() }

    fun initialize() {
        if (action.value.busy) return
        action.value = Action(loading = true, busy = true)
        viewModelScope.launch {
            try {
                ready?.await()
                repository.initialize()
                action.value = Action(loading = false)
                retry.value += 1
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                val ownerInvalid = failure.message in OWNER_FAILURES
                action.value = Action(loading = false,
                    error = if (ownerInvalid) R.string.account_login_again else R.string.mp_error_storage,
                    ownerInvalid = ownerInvalid)
            }
        }
    }

    private fun perform(notice: Int? = R.string.mp_saved, work: suspend () -> Unit) {
        // Set synchronously before launching: two taps cannot enqueue two writes/confirmations.
        if (action.value.busy || action.value.loading) return
        action.value = Action(loading = false, busy = true)
        viewModelScope.launch {
            try {
                work()
                action.value = Action(loading = false, notice = notice)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                val error = when {
                    failure.message in setOf("unknown_nutrition", "planning_unknown_nutrition") -> R.string.mp_unknown_nutrients
                    failure.message in OWNER_FAILURES -> R.string.account_login_again
                    failure is IllegalArgumentException -> R.string.mp_invalid
                    else -> R.string.mp_error_storage
                }
                action.value = Action(loading = false, error = error,
                    ownerInvalid = failure.message in OWNER_FAILURES)
            }
        }
    }

    fun setPeriod(start: String, days: Int) = perform { repository.setPeriod(start, days) }
    fun today(days: Int) = setPeriod(LocalDate.now(clock).toString(), days)
    fun addRecipe(date: String, meal: MealSlot, recipeId: String, servings: Double) =
        perform { repository.addRecipe(date, meal, recipeId, servings) }
    fun changeServings(entryId: String, servings: Double) = perform { repository.changeServings(entryId, servings) }
    fun replaceRecipe(entryId: String, recipeId: String) = perform { repository.replaceRecipe(entryId, recipeId) }
    fun deleteEntry(entryId: String) = perform { repository.deleteEntry(entryId) }
    fun copyDay(source: String, target: String) = perform { repository.copyDay(source, target) }
    fun confirmEaten(entryId: String, servings: Double) = perform(R.string.mp_eaten_saved) {
        repository.confirmEaten(entryId, servings)
    }
    fun importSavedDailyPlans() = perform(R.string.mp_imported) {
        // Read only cached plans. This never calls PlanStore.load or an AI provider.
        repository.importDailyPlans(legacyPlans.plans.value)
    }
    fun refreshShopping() = perform(R.string.mp_list_updated) { repository.refreshShopping() }
    fun markRow(key: String, status: ShoppingStatus) = perform(null) { repository.markRow(key, status) }
    fun saveNote(key: String, note: String) = perform { repository.noteRow(key, note) }
    fun addManual(name: String, quantity: Double?, unit: RecipeUnit, category: ShoppingCategory, note: String) =
        perform { repository.addManual(name, quantity, unit, category, note) }
    fun removeManual(key: String) = perform { repository.removeManual(key) }
    fun dismissFeedback() { action.update { it.copy(error = null, notice = null) } }

    private companion object {
        val OWNER_FAILURES = setOf("account_changed", "session_changed", "session_expired", "owner_changed")
    }
}

/** Bounds automatic recovery independently of Flow re-emissions and subscriber recreation. */
internal class PlanningWorkspaceRecovery {
    private var attemptedForMissingWorkspace = false

    fun shouldRecover(hasWorkspace: Boolean, loading: Boolean, busy: Boolean,
                      hasError: Boolean, ownerValid: Boolean): Boolean {
        if (hasWorkspace) {
            attemptedForMissingWorkspace = false
            return false
        }
        if (loading || busy || hasError || !ownerValid || attemptedForMissingWorkspace) return false
        attemptedForMissingWorkspace = true
        return true
    }
}
