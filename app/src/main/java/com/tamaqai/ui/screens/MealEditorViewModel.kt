package com.tamaqai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamaqai.data.DemoMeals
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.AnalyzeMealPhotoUseCase
import com.tamaqai.domain.usecase.NutritionCalculator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ComponentFields(val name: String = "", val mass: String = "", val calories: String = "0",
    val protein: String = "0", val fat: String = "0", val carbs: String = "0",
    val consumedPortion: String? = null, val massReference: ComponentMassReference? = null) {
    fun component() = MealComponent(name.trim(), mass.takeIf { it.isNotBlank() }?.number(),
        calories.number(), protein.number(), fat.number(), carbs.number(), consumedPortion?.number(), massReference)
    companion object {
        fun from(c: MealComponent) = ComponentFields(c.name, c.massGrams?.toString() ?: "", c.calories.toString(), c.protein.toString(), c.fat.toString(), c.carbs.toString(), c.consumedPortion?.toString(), c.massReference)
    }
}
private fun String.number() = replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("ui_error_004")
data class EditorState(val meal: Meal? = null, val name: String = "", val portion: String = "1",
    val components: List<ComponentFields> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val saving: Boolean = false, val error: String? = null,
    val snapshot: Nutrition? = null, val mealType: MealType = MealType.Other, val time: String = "")

class MealEditorViewModel(private val uid: String, private val repository: MealRepository,
    private val photos: PhotoStore, private val analyze: AnalyzeMealPhotoUseCase, private val ready: Deferred<Unit>? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val text: (Int) -> String = { it.toString() }) : ViewModel() {
    private val calculator = NutritionCalculator()
    private val lock = Mutex()
    private var revision = 0
    private val _state = MutableStateFlow(EditorState())
    val state = _state.asStateFlow()
    private val closeChannel = Channel<Unit>(Channel.BUFFERED)
    val close = closeChannel.receiveAsFlow()
    private var analysisJob: Job? = null
    init { load() }
    fun load() = viewModelScope.launch {
        try {
            ready?.await()
            val meal = requireNotNull(repository.getMeal(uid)) { "ui_error_005" }
            show(meal)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { _state.value = EditorState(loading = false, error = "ui_error_006") }
    }
    fun selectCatalog(selection: CatalogSelection, componentIndex: Int? = null) {
        if(_state.value.busy) return
        val initial=_state.value.meal ?: return
        _state.update {it.copy(busy=true,error=null)}
        viewModelScope.launch {
            try {
                val selected=if(componentIndex==null) com.tamaqai.domain.usecase.CatalogMealFactory.create(selection,original=initial)
                    else com.tamaqai.domain.usecase.CatalogMealFactory.replaceComponent(initial,componentIndex,selection)
                lock.withLock {repository.saveDraft(selected)}
                show(selected)
            } catch(cancel:CancellationException) {throw cancel}
            catch(_:Exception) {_state.update {it.copy(busy=false,error="ui_error_008")}}
        }
    }
    private fun show(meal: Meal) {
        _state.value = EditorState(meal, meal.nutrition.name, meal.details.portion.toString(),
            if(meal.details.catalogPendingManual && meal.details.components.isEmpty())
                listOf(ComponentFields(name=meal.nutrition.name,calories="",protein="",fat="",carbs=""))
            else meal.details.components.mapIndexed {index,c ->
                if(meal.details.catalogPendingManual && meal.details.catalogPendingIndex==index) ComponentFields(name=c.name,calories="",protein="",fat="",carbs="")
                else ComponentFields.from(c)
            }, loading = false,
            snapshot = meal.nutrition.takeIf { meal.details.components.isNotEmpty() && !meal.details.catalogPendingManual }, mealType = meal.mealType,
            time = java.time.Instant.ofEpochMilli(meal.timestamp).atZone(meal.zoneId?.let(java.time.ZoneId::of) ?: java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
    }
    fun name(value: String) = change { copy(name = value) }
    fun portion(value: String) = change { copy(portion = value, components = components.map { it.copy(consumedPortion = null) }) }
    fun componentPortion(index: Int, value: String) = change {
        copy(components = components.toMutableList().also { it[index] = it[index].copy(consumedPortion = value) })
    }
    fun component(index: Int, value: ComponentFields) = change {
        val previous = components[index]
        val changedNutrition = value.calories != previous.calories || value.protein != previous.protein ||
            value.fat != previous.fat || value.carbs != previous.carbs
        val updated = if (changedNutrition) value.copy(massReference = null)
            else if (value.mass != previous.mass) rescaleMass(previous, value.mass) else value
        copy(components = components.toMutableList().also { it[index] = updated })
    }
    private fun rescaleMass(previous: ComponentFields, value: String): ComponentFields {
        val oldMass = previous.mass.replace(',', '.').toDoubleOrNull()
        val newMass = value.replace(',', '.').toDoubleOrNull()
        val reference = previous.massReference ?: runCatching { ComponentMassReference(requireNotNull(oldMass).also { require(it.isFinite() && it > 0) },
            previous.calories.number(), previous.protein.number(), previous.fat.number(), previous.carbs.number()) }.getOrNull()
            ?: return previous.copy(mass = value)
        if (newMass == null || !newMass.isFinite() || newMass <= 0)
            return previous.copy(mass = value, massReference = if (value.isBlank()) null else reference)
        val scale = newMass / reference.grams
        return previous.copy(mass = value, calories = (reference.calories * scale).toString(),
            protein = (reference.protein * scale).toString(), fat = (reference.fat * scale).toString(),
            carbs = (reference.carbs * scale).toString(), massReference = reference)
    }
    fun add() = change { copy(components = components + ComponentFields(name = text(com.tamaqai.R.string.component))) }
    fun type(value: MealType) = change { copy(mealType = value) }
    fun time(value: String) = change { copy(time = value) }
    fun remove(index: Int) = change { copy(components = components.filterIndexed { i, _ -> i != index }) }
    private fun build(s: EditorState, allowEmpty: Boolean = false): Meal {
        val meal = requireNotNull(s.meal)
        require(s.name.isNotBlank() && s.name.length <= 120) { "name_error" }
        val components = s.components.map { it.component() }
        val portion = s.portion.number()
        val nutrition = if (allowEmpty && components.isEmpty()) Nutrition(s.name.trim(), 0, 0, 0, 0)
            else calculator.calculate(s.name, components, portion)
        require(portion.isFinite() && portion in 0.05..20.0) { "ui_error_007" }
        val edited = meal.details.userEdited || s.name.trim() != meal.nutrition.name ||
            components != meal.details.components || portion != meal.details.portion
        val oldTime = java.time.Instant.ofEpochMilli(meal.timestamp).atZone(meal.zoneId?.let(java.time.ZoneId::of) ?: java.time.ZoneId.systemDefault())
        val enteredTime = try { java.time.LocalTime.parse(s.time) } catch (_: Exception) { throw IllegalArgumentException("time_error") }
        val timestamp = if (oldTime.toLocalTime().withSecond(0).withNano(0) == enteredTime) meal.timestamp
            else oldTime.toLocalDate().atTime(enteredTime).atZone(oldTime.zone).toInstant().toEpochMilli()
        return meal.copy(nutrition = nutrition, timestamp = timestamp, mealType = s.mealType,
            details = meal.details.copy(components = components, portion = portion, userEdited = edited,
                catalogPendingManual = meal.details.catalogPendingManual && components.isEmpty(),catalogPendingIndex=null))
    }
    private fun change(transform: EditorState.() -> EditorState) {
        if (_state.value.busy || _state.value.meal == null) return
        val changed = transform(_state.value).copy(error = null)
        val model = try { build(changed, allowEmpty = true) }
        catch (e: IllegalArgumentException) { _state.value = changed.copy(error = e.message, snapshot = null); return }
        _state.value = changed.copy(meal = model, snapshot = model.nutrition.takeIf { model.details.components.isNotEmpty() }, saving = true)
        val version = ++revision
        viewModelScope.launch {
            try {
                lock.withLock { if (version == revision) repository.saveDraft(model) }
                if (version == revision) _state.update { it.copy(saving = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _state.update { it.copy(saving = false, error = "ui_error_008") } }
        }
    }
    fun confirm() {
        if (_state.value.busy || _state.value.meal == null) return
        val model = try { build(_state.value) } catch (e: IllegalArgumentException) { _state.update { it.copy(error = e.message) }; return }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                lock.withLock { repository.confirm(model) }
                withContext(ioDispatcher) { photos.delete(model.details.photoName) }
                closeChannel.send(Unit)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _state.update { it.copy(busy = false, error = "ui_error_009") } }
        }
    }
    fun delete(originalToo: Boolean = false) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                lock.withLock { repository.delete(if (originalToo) _state.value.meal?.originalUid ?: uid else uid) }
                withContext(ioDispatcher) { photos.delete(_state.value.meal?.details?.photoName) }
                closeChannel.send(Unit)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _state.update { it.copy(busy = false, error = "ui_error_010") } }
        }
    }
    fun demo() {
        if (!DemoMeals.available || _state.value.busy) return
        val meal = _state.value.meal ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { val demo = DemoMeals.fill(meal, text); lock.withLock { repository.saveDraft(demo) }; show(demo) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _state.update { it.copy(busy = false, error = "ui_error_011") } }
        }
    }
    fun analyze(invite: String) {
        if (_state.value.busy) return
        val initial = _state.value.meal ?: return
        val photo = initial.details.photoName ?: return
        val key = initial.details.analysisId ?: return
        _state.update { it.copy(busy = true, error = null) }
        analysisJob = viewModelScope.launch {
            try {
                lock.withLock { repository.saveDraft(initial.copy(status = MealStatus.Analyzing)) }
                val jpeg = withContext(ioDispatcher) { photos.read(photo) }
                val response = analyze(jpeg, key, invite)
                val result = response.result
                val details = initial.details.copy(components = result.components.map { it.toDomain() }, portion = 1.0,
                    schemaVersion = response.schemaVersion, promptVersion = response.promptVersion, model = response.model,
                    explanation = result.explanation ?: if (result.status == "no_food") "ui_error_012" else null,
                    question = result.question, userEdited = false)
                val snapshot = if (details.components.isEmpty()) Nutrition(result.name, 0, 0, 0, 0)
                    else calculator.calculate(result.name, details.components, 1.0)
                val reviewed = initial.copy(nutrition = snapshot, status = MealStatus.NeedsReview,
                    source = if (response.isDemo) NutritionSource.Demo else NutritionSource.AiEstimate, details = details)
                lock.withLock { repository.saveDraft(reviewed) }
                show(reviewed)
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) { fail(initial, "ui_error_013") }
                throw cancel
            } catch (e: Exception) { fail(initial, if (e is IllegalStateException || e is IllegalArgumentException) e.message ?: "ui_error_014" else "ui_error_015") }
        }
    }
    private suspend fun fail(meal: Meal, message: String) {
        val failed = meal.copy(status = MealStatus.Failed)
        try { lock.withLock { repository.saveDraft(failed) } }
        catch (_: Exception) { _state.update { it.copy(busy = false, error = "$message|local_save_error") }; return }
        _state.update { it.copy(meal = failed, busy = false, error = message) }
    }
    fun cancelAnalysis() { analysisJob?.cancel() }
    fun leave() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // Flush the latest valid snapshot before navigation destroys this VM.
            try {
                lock.withLock { _state.value.meal?.let { repository.saveDraft(it) } }
                closeChannel.send(Unit)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _state.update { it.copy(busy = false, error = "ui_error_008") } }
        }
    }
    fun energyWarning() = _state.value.snapshot?.let(calculator::hasEnergyWarning) == true
}
