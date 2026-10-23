package com.tamaqai.data.remote

import android.content.Context
import com.tamaqai.data.local.UserSettings
import com.tamaqai.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

fun Questionnaire.json()=JSONObject().put("step",step).put("completed",completed).put("goal",goal)
    .put("age",age ?: JSONObject.NULL).put("height_cm",height ?: JSONObject.NULL).put("weight_kg",weight ?: JSONObject.NULL)
    .put("formula_sex",sex).put("activity",activity).put("special_condition",special).put("meals",meals)
    .put("times",JSONArray(times)).put("preferences",preferences).put("exclusions",JSONArray(exclusions)).put("allergies",JSONArray(allergies))
    .put("budget",budget).put("cooking_minutes",cooking)
fun questionnaire(j:JSONObject):Questionnaire {
    fun strings(key:String)=j.optJSONArray(key)?.let {a->(0 until a.length()).map(a::getString)} ?: emptyList()
    return Questionnaire(j.optInt("step"),j.optBoolean("completed"),j.optString("goal","diary"),if(j.isNull("age")) null else j.getInt("age"),
        if(j.isNull("height_cm")) null else j.getDouble("height_cm"),if(j.isNull("weight_kg")) null else j.getDouble("weight_kg"),j.optString("formula_sex","unspecified"),
        j.optString("activity","light"),j.optBoolean("special_condition"),j.optInt("meals",3),strings("times"),j.optString("preferences"),strings("exclusions"),strings("allergies"),j.optString("budget"),j.optInt("cooking_minutes",30))
}
fun DailyGoals.json()=JSONObject().put("calories",calories ?: JSONObject.NULL).put("protein",protein ?: JSONObject.NULL).put("fat",fat ?: JSONObject.NULL).put("carbs",carbs ?: JSONObject.NULL)
fun targets(j:JSONObject)=DailyGoals(if(j.isNull("calories")) null else j.getInt("calories"),if(j.isNull("protein")) null else j.getInt("protein"),if(j.isNull("fat")) null else j.getInt("fat"),if(j.isNull("carbs")) null else j.getInt("carbs"))
data class FitnessState(val version:Int=0,val document:Questionnaire=Questionnaire(completed=true),val dirty:Boolean=false,val loaded:Boolean=false)

@android.annotation.SuppressLint("UseKtx")
class NutritionRepository(context:Context,private val client:SessionClient,private val settings:UserSettings) : com.tamaqai.data.contracts.NutritionStore {
    private val owner=client.sessions.owner
    private val prefs=context.getSharedPreferences("nutrition_$owner",Context.MODE_PRIVATE)
    private val lock=Mutex()
    private fun read():FitnessState=runCatching {val j=JSONObject(prefs.getString("fitness",null) ?: error("empty"));FitnessState(j.getInt("version"),questionnaire(j.getJSONObject("document")),j.optBoolean("dirty"),true)}.getOrDefault(FitnessState())
    private val changes=MutableStateFlow(read());override val state=changes.asStateFlow()
    private fun persist(s:FitnessState) {
        client.requireOwner(owner)
        check(prefs.edit().putString("fitness",JSONObject().put("version",s.version).put("document",s.document.json()).put("dirty",s.dirty).toString()).commit());changes.value=s
    }
    override suspend fun load()=withContext(Dispatchers.IO) {lock.withLock {
        if(owner=="guest") return@withLock
        val remote=client.authorized("/v1/nutrition/questionnaire",owner)
        if(!changes.value.dirty) persist(FitnessState(remote.getInt("version"),questionnaire(remote.getJSONObject("document")),loaded=true))
        val history=client.authorized("/v1/nutrition/goals",owner).getJSONArray("history")
        settings.mergeHistory(history)
    }}
    override suspend fun draft(q:Questionnaire)=withContext(Dispatchers.IO) {lock.withLock {persist(changes.value.copy(document=q,dirty=true,loaded=true))}}
    override suspend fun sync()=withContext(Dispatchers.IO) {lock.withLock {
        if(owner=="guest") {persist(changes.value.copy(dirty=false));return@withLock}
        if(!changes.value.dirty) return@withLock
        val s=changes.value
        val response=client.authorized("/v1/nutrition/questionnaire",owner,"PUT",JSONObject().put("version",s.version).put("document",s.document.json()))
        persist(FitnessState(response.getInt("version"),questionnaire(response.getJSONObject("document")),loaded=true))
    }}
    /** Explicitly discard a stale draft only after the user chooses the server copy. */
    override suspend fun useServer()=withContext(Dispatchers.IO) {lock.withLock {
        val r=client.authorized("/v1/nutrition/questionnaire",owner)
        persist(FitnessState(r.getInt("version"),questionnaire(r.getJSONObject("document")),loaded=true))
    }}
    override suspend fun finish(q:Questionnaire,apply:Boolean)=withContext(Dispatchers.IO) {lock.withLock {
        val done=q.copy(step=3,completed=true)
        val goals=if(apply) TargetCalculator.calculate(done) else null
        var version=changes.value.version
        if(owner!="guest") {
            val r=client.authorized("/v1/nutrition/questionnaire",owner,"PUT",JSONObject().put("version",version).put("document",done.json()))
            version=r.getInt("version")
            // Keep the gate visible until the explicit target write completes.
            persist(FitnessState(version,done.copy(completed=false),dirty=true,loaded=true))
            if(goals!=null) client.authorized("/v1/nutrition/goals",owner,"PUT",JSONObject().put("effective_date",LocalDate.now().toString()).put("timezone",ZoneId.systemDefault().id)
                .put("origin","calculated").put("targets",goals.json()).put("fitness_version",version))
        }
        if(goals!=null) settings.save(goals,LocalDate.now(),"calculated")
        persist(FitnessState(version,done,loaded=true))
    }}
    override suspend fun saveGoals(value:DailyGoals,date:LocalDate,origin:String)=withContext(Dispatchers.IO) {lock.withLock {
        value.validate();require(!date.isBefore(LocalDate.now()))
        if(owner!="guest") client.authorized("/v1/nutrition/goals",owner,"PUT",JSONObject().put("effective_date",date.toString()).put("timezone",ZoneId.systemDefault().id)
            .put("origin",origin).put("targets",value.json()).put("fitness_version",changes.value.version))
        settings.save(value,date,origin)
    }}
}
