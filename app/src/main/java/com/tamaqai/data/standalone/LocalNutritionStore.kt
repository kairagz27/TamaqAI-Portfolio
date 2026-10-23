package com.tamaqai.data.standalone

import android.content.Context
import com.tamaqai.data.contracts.NutritionStore
import com.tamaqai.data.local.UserSettings
import com.tamaqai.data.remote.FitnessState
import com.tamaqai.data.remote.json
import com.tamaqai.data.remote.questionnaire
import com.tamaqai.domain.model.DailyGoals
import com.tamaqai.domain.model.Questionnaire
import com.tamaqai.domain.model.TargetCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Clock
import java.time.LocalDate

/** Same questionnaire/goal calculations as the real app; persistence is strictly on this device. */
@android.annotation.SuppressLint("UseKtx")
class LocalNutritionStore(context: Context, owner: String, private val settings: UserSettings,
    private val clock: Clock = Clock.systemDefaultZone()) : NutritionStore {
    private val prefs = context.getSharedPreferences("nutrition_$owner", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private fun read() = prefs.getString("fitness", null)?.let {
        val value = JSONObject(it)
        FitnessState(value.getInt("version"), questionnaire(value.getJSONObject("document")), loaded = true)
    } ?: FitnessState(document = Questionnaire(completed = true), loaded = true)
    private val changes = MutableStateFlow(read())
    override val state = changes.asStateFlow()
    private fun persist(document: Questionnaire) {
        val next = FitnessState(changes.value.version + 1, document, loaded = true)
        check(prefs.edit().putString("fitness", JSONObject().put("version", next.version)
            .put("document", document.json()).toString()).commit())
        changes.value = next
    }
    override suspend fun load() = Unit
    override suspend fun draft(q: Questionnaire) = withContext(Dispatchers.IO) { lock.withLock { persist(q) } }
    override suspend fun sync() = Unit
    override suspend fun useServer(): Unit = unavailable()
    override suspend fun finish(q: Questionnaire, apply: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val done = q.copy(step = 3, completed = true)
            val goals = if (apply) TargetCalculator.calculate(done) else null
            if (goals != null) settings.save(goals, LocalDate.now(clock), "calculated")
            persist(done)
        }
    }
    override suspend fun saveGoals(value: DailyGoals, date: LocalDate, origin: String) = withContext(Dispatchers.IO) {
        lock.withLock { value.validate(); require(!date.isBefore(LocalDate.now(clock))); settings.save(value, date, origin) }
    }
    internal suspend fun seed() = lock.withLock {
        if (!prefs.contains("fitness")) persist(Questionnaire(step = 3, completed = true, goal = "diary", height = 172.0, weight = 72.0))
    }
    internal suspend fun clear() = lock.withLock {
        check(prefs.edit().clear().commit())
        changes.value = FitnessState(document = Questionnaire(completed = true), loaded = true)
    }
}
