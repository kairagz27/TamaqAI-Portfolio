package com.tamaqai.data.contracts
import com.tamaqai.data.remote.AccountProfile
import com.tamaqai.data.remote.FitnessState
import com.tamaqai.domain.model.*
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.time.LocalDate
/** Local and server implementations share contracts, never simulated authentication. */
interface AccountStore {
 val profileState:StateFlow<AccountProfile>
 val deliveryFailed:Boolean
 fun localProfile():AccountProfile
 suspend fun load():AccountProfile
 suspend fun save(profile:AccountProfile):AccountProfile
 suspend fun login(email:String,password:String)
 suspend fun register(email:String,password:String,name:String)
 suspend fun methods():JSONObject
 suspend fun requestPhone(phone:String,region:String):JSONObject
 suspend fun verifyPhone(challenge:String,code:String,name:String)
 suspend fun uploadAvatar(bytes:ByteArray):AccountProfile
 suspend fun removeAvatar():AccountProfile
 suspend fun verify(token:String):JSONObject
 suspend fun resend():JSONObject
 suspend fun forgot(email:String):JSONObject
 suspend fun reset(token:String,password:String):JSONObject
 suspend fun logout():Boolean
 suspend fun delete(password:String)
 suspend fun linkGuest(invite:String):AccountProfile
}
interface NutritionStore {
 val state:StateFlow<FitnessState>
 suspend fun load()
 suspend fun draft(q:Questionnaire)
 suspend fun sync()
 suspend fun useServer()
 suspend fun finish(q:Questionnaire,apply:Boolean)
 suspend fun saveGoals(value:DailyGoals,date:LocalDate,origin:String)
}
interface PlanStore {
 val plans:StateFlow<List<MealPlan>>
 val hasPendingGeneration:Boolean
 suspend fun local(plan:MealPlan)
 suspend fun load()
 suspend fun sync(id:String)
 suspend fun saveAsCopy(id:String):String
 suspend fun generate(date:LocalDate,fitnessVersion:Int,goalDate:LocalDate,language:String):String
 fun abandonGeneration()
 suspend fun eat(planId:String,mealId:String,portion:Double):String
}
interface CatalogStore {
 val isBundled:Boolean get()=false
 suspend fun recent():List<CatalogEntry>
 suspend fun search(text:String,kind:String,city:String?=null,chain:String?=null,barcode:String?=null,branchScope:String?=null):CatalogResults
 suspend fun cities():List<String>
 suspend fun restaurants(city:String):List<CatalogRestaurant>
 suspend fun get(idOrVersion:String,rememberSelection:Boolean=false):CatalogEntry
 suspend fun entry(versionId:String):CatalogEntry
 suspend fun match(names:List<String>,barcode:String?=null,city:String?=null,chain:String?=null):List<CatalogEntry>
 suspend fun matchComponents(names:List<String>):List<Pair<Int,CatalogEntry>>
 suspend fun remember(entry:CatalogEntry)
 suspend fun isFavorite(version:String):Boolean
 suspend fun favorite(entry:CatalogEntry,favorite:Boolean)
 suspend fun calculate(entry:CatalogEntry,quantity:Double,unit:String,addons:List<CatalogAddon> = emptyList()):CatalogCalculation
}
