package com.tamaqai.data.local

import android.content.Context
import com.tamaqai.domain.model.DailyGoals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import com.tamaqai.data.remote.json
import com.tamaqai.data.remote.targets

data class GoalSnapshot(val date:LocalDate,val origin:String,val targets:DailyGoals)

class UserSettings(context: Context, owner: String = "guest") {
    private val prefs = context.getSharedPreferences(if (owner == "guest") "daily_settings" else "daily_settings_$owner", Context.MODE_PRIVATE)
    private fun goals() = DailyGoals(prefs.getInt("calories", 0).takeIf { it > 0 },
        prefs.getInt("protein", 0).takeIf { it > 0 }, prefs.getInt("fat", 0).takeIf { it > 0 },
        prefs.getInt("carbs", 0).takeIf { it > 0 })
    private fun readHistory():List<GoalSnapshot> = runCatching {
        val a=JSONArray(prefs.getString("goal_history",null) ?: error("empty"))
        (0 until a.length()).map {i->val j=a.getJSONObject(i);GoalSnapshot(LocalDate.parse(j.getString("effective_date")),j.getString("origin"),targets(j.getJSONObject("targets")))}.sortedBy {it.date}
    }.getOrElse {listOf(GoalSnapshot(LocalDate.MIN,"manual",goals()))}
    private val historyState=MutableStateFlow(readHistory())
    val history=historyState.asStateFlow()
    fun onDate(date:LocalDate)=historyState.value.lastOrNull {it.date<=date}?.targets ?: DailyGoals()
    fun originOn(date:LocalDate)=historyState.value.lastOrNull {it.date<=date}?.origin ?: "manual"
    private val state = MutableStateFlow(onDate(LocalDate.now()))
    val dailyGoals = state.asStateFlow()
    @android.annotation.SuppressLint("UseKtx") // Check the synchronous commit result before publishing settings.
    @Synchronized fun save(value: DailyGoals,date:LocalDate=LocalDate.now(),origin:String="manual") {
        value.validate()
        persist((historyState.value.filterNot {it.date==date}+GoalSnapshot(date,origin,value)).sortedBy {it.date})
    }
    @Synchronized fun mergeHistory(a:JSONArray) {
        var entries=historyState.value
        for(i in 0 until a.length()) {val j=a.getJSONObject(i);val d=LocalDate.parse(j.getString("effective_date"));entries=entries.filterNot {it.date==d}+GoalSnapshot(d,j.getString("origin"),targets(j.getJSONObject("targets")))}
        persist(entries.sortedBy {it.date})
    }
    @android.annotation.SuppressLint("UseKtx") // Check commit before publishing the history.
    private fun persist(entries:List<GoalSnapshot>) {
        val current=entries.lastOrNull {it.date<=LocalDate.now()}?.targets ?: DailyGoals()
        val array=JSONArray(entries.map {JSONObject().put("effective_date",it.date.toString()).put("origin",it.origin).put("targets",it.targets.json())})
        check(prefs.edit().putString("goal_history",array.toString()).putInt("calories",current.calories ?: 0).putInt("protein",current.protein ?: 0).putInt("fat",current.fat ?: 0).putInt("carbs",current.carbs ?: 0).commit())
        historyState.value=entries;state.value=current
    }
}
