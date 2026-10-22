package com.tamaqai.data.remote

import android.content.Context
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.NutritionCalculator
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
import java.util.UUID

fun PlanNutrients.json()=JSONObject().put("calories",calories).put("protein",protein).put("fat",fat).put("carbs",carbs)
fun planNutrients(j:JSONObject)=PlanNutrients(j.getDouble("calories"),j.getDouble("protein"),j.getDouble("fat"),j.getDouble("carbs"))
fun MealPlan.document()=JSONObject().put("date",date.toString()).put("source",source).put("is_demo",demo).put("content",JSONObject()
    .put("meals",JSONArray(meals.map {m->JSONObject().put("id",m.id).put("time",m.time).put("dish",m.dish).put("total",m.total?.json() ?: JSONObject.NULL)
        .put("ingredients",JSONArray(m.ingredients.map {i->JSONObject().put("id",i.id).put("name",i.name).put("quantity",i.quantity).put("unit",i.unit).put("basis_quantity",i.basis).put("nutrition",i.nutrition?.json() ?: JSONObject.NULL)}))}))
    .put("total",total?.json() ?: JSONObject.NULL))
fun planRecord(j:JSONObject):MealPlan {
    val d=j.getJSONObject("document");val a=d.getJSONObject("content").getJSONArray("meals")
    return MealPlan(j.getString("id"),j.getInt("version"),LocalDate.parse(d.getString("date")),d.getString("source"),(0 until a.length()).map {n->val m=a.getJSONObject(n);val ingredients=m.getJSONArray("ingredients")
        PlannedMeal(m.getString("id"),m.getString("time"),m.getString("dish"),(0 until ingredients.length()).map {k->val i=ingredients.getJSONObject(k);PlanIngredient(i.getString("id"),i.getString("name"),i.getDouble("quantity"),i.getString("unit"),i.getDouble("basis_quantity"),if(i.isNull("nutrition")) null else planNutrients(i.getJSONObject("nutrition")))})},j.optBoolean("dirty"),j.optBoolean("is_demo") || d.optBoolean("is_demo"))
}

@android.annotation.SuppressLint("UseKtx")
class PlanRepository(context:Context,private val client:SessionClient,private val diary:MealRepository) {
    private val owner=client.sessions.owner
    private val prefs=context.getSharedPreferences("plans_$owner",Context.MODE_PRIVATE)
    private val lock=Mutex();private val consumeLock=Mutex()
    private fun read()=runCatching {val a=JSONArray(prefs.getString("plans","[]"));(0 until a.length()).map {planRecord(a.getJSONObject(it))}}.getOrDefault(emptyList())
    private val changes=MutableStateFlow(read());val plans=changes.asStateFlow()
    val hasPendingGeneration get()=prefs.contains("generation")
    private fun persist(items:List<MealPlan>) {
        client.requireOwner(owner)
        val a=JSONArray(items.map {JSONObject().put("id",it.id).put("version",it.version).put("document",it.document()).put("dirty",it.dirty).put("is_demo",it.demo)})
        check(prefs.edit().putString("plans",a.toString()).commit());changes.value=items
    }
    suspend fun local(plan:MealPlan)=withContext(Dispatchers.IO) {lock.withLock {plan.validate();persist(listOf(plan.copy(dirty=true))+changes.value.filterNot {it.id==plan.id})}}
    suspend fun load()=withContext(Dispatchers.IO) {lock.withLock {
        if(owner=="guest") return@withLock
        val a=client.authorized("/v1/plans",owner).getJSONArray("plans");val remote=(0 until a.length()).map {planRecord(a.getJSONObject(it))}
        val local=changes.value
        persist(local.filter {it.dirty || remote.none {r->r.id==it.id}}+remote.filter {r->local.none {it.id==r.id && it.dirty}}.map {r->r.copy(demo=r.demo || local.firstOrNull {it.id==r.id}?.demo==true)})
    }}
    suspend fun sync(id:String)=withContext(Dispatchers.IO) {lock.withLock {
        require(owner!="guest") {"account_required"}
        var p=changes.value.first {it.id==id}
        val pendingKey="pending_$id"
        // Replay the exact mutation after an unknown network outcome before
        // sending later offline edits. Never discard the user's current draft.
        val pending=prefs.getString(pendingKey,null)
        if(pending!=null) {
            val request=JSONObject(pending);val r=client.authorized("/v1/plans/$id",owner,"PUT",request)
            val same=p.document().toString()==request.getJSONObject("document").toString()
            p=p.copy(version=r.getInt("version"),dirty=!same)
            persist(changes.value.map {if(it.id==id) p else it});check(prefs.edit().remove(pendingKey).commit())
        }
        if(!p.dirty) return@withLock
        val request=JSONObject().put("version",p.version).put("operation",UUID.randomUUID().toString()).put("document",p.document())
        check(prefs.edit().putString(pendingKey,request.toString()).commit())
        val r=client.authorized("/v1/plans/$id",owner,"PUT",request)
        persist(changes.value.map {if(it.id==id) p.copy(version=r.getInt("version"),dirty=false) else it})
        check(prefs.edit().remove(pendingKey).commit())
    }}
    suspend fun saveAsCopy(id:String):String=withContext(Dispatchers.IO) {lock.withLock {
        val p=changes.value.first {it.id==id}.copy(id=UUID.randomUUID().toString(),version=0,dirty=true,source="manual")
        persist(listOf(p)+changes.value);p.id
    }}
    suspend fun generate(date:LocalDate,fitnessVersion:Int,goalDate:LocalDate,language:String)=withContext(Dispatchers.IO) {lock.withLock {
        require(owner!="guest") {"account_required"}
        val request=prefs.getString("generation",null)?.let(::JSONObject) ?: JSONObject().put("key",UUID.randomUUID().toString()).put("body",JSONObject().put("date",date.toString()).put("fitness_version",fitnessVersion).put("goal_date",goalDate.toString()).put("language",language))
            .also {check(prefs.edit().putString("generation",it.toString()).commit())}
        val result=client.authorized("/v1/plans/generate",owner,"POST",request.getJSONObject("body"),mapOf("Idempotency-Key" to request.getString("key")))
        val p=planRecord(result)
        if(changes.value.none {it.id==p.id}) persist(listOf(p)+changes.value)
        check(prefs.edit().remove("generation").commit());p.id
    }}
    fun abandonGeneration() {check(prefs.edit().remove("generation").commit())}
    suspend fun eat(planId:String,mealId:String,portion:Double):String=consumeLock.withLock {
        client.requireOwner(owner);require(portion.isFinite() && portion in .05..20.0)
        val p=changes.value.first {it.id==planId};val m=p.meals.first {it.id==mealId}
        // A guest linkage changes owner but must retain the same meal identity.
        // Room still checks owner on every read and write.
        val uid=UUID.nameUUIDFromBytes("tamaq-plan:$planId:$mealId".toByteArray()).toString()
        if(diary.getMeal(uid)?.status==MealStatus.Confirmed) return@withLock uid
        require(m.total!=null) {"unknown_nutrition"}
        val components=m.ingredients.map {val n=it.total!!;MealComponent(it.name,if(it.unit=="g") it.quantity else null,n.calories,n.protein,n.fat,n.carbs)}
        val nutrition=NutritionCalculator().calculate(m.dish,components,portion)
        val zone=ZoneId.systemDefault()
        diary.confirm(Meal(uid=uid,nutrition=nutrition,timestamp=p.date.atTime(java.time.LocalTime.parse(m.time)).atZone(zone).toInstant().toEpochMilli(),zoneId=zone.id,
            source=if(p.demo) NutritionSource.Demo else if(p.source=="ai") NutritionSource.AiEstimate else NutritionSource.Manual,details=MealDetails(components,portion=portion,userEdited=true)))
        uid
    }
}
