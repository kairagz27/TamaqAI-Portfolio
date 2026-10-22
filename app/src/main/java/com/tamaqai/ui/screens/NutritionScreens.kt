package com.tamaqai.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.TamaqApplication
import com.tamaqai.domain.model.*
import com.tamaqai.data.remote.ApiFailure
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

@Composable private fun Choice(value:String,options:List<Pair<String,Int>>,enabled:Boolean=true,choose:(String)->Unit) {
    options.forEach {(id,label)->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=enabled,role=androidx.compose.ui.semantics.Role.RadioButton) {choose(id)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        WellnessRadioButton(value==id,{choose(id)},enabled=enabled);Text(stringResource(label),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    }}
}
@Composable private fun NumberInput(value:String,update:(String)->Unit,label:Int,minimum:Double,maximum:Double,enabled:Boolean=true,integer:Boolean=false) {
    val n=value.replace(',','.').toDoubleOrNull()
    val valid=n!=null && n.isFinite() && n in minimum..maximum && (!integer || n%1.0==0.0)
    WellnessTextField(value,update,label={Text(stringResource(label))},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=enabled,
        keyboardOptions=KeyboardOptions(keyboardType=if(integer) KeyboardType.Number else KeyboardType.Decimal),isError=value.isNotEmpty() && !valid,
        supportingText={Text(stringResource(R.string.number_range,com.tamaqai.ui.displayNumber(minimum.toString()),com.tamaqai.ui.displayNumber(maximum.toString())))},colors=wellnessFieldColors())
}

@Composable fun QuestionnaireScreen(onDone:()->Unit,onBack:()->Unit={},required:Boolean=false) {
    val container=(LocalContext.current.applicationContext as TamaqApplication).container
    val repository=container.nutrition
    val state by repository.state.collectAsStateWithLifecycle()
    var q by remember(repository) {mutableStateOf(state.document)}
    var age by remember(repository) {mutableStateOf(q.age?.toString() ?: "")}
    var height by remember(repository) {mutableStateOf(q.height?.toString() ?: "")}
    var weight by remember(repository) {mutableStateOf(q.weight?.toString() ?: "")}
    var meals by remember(repository) {mutableStateOf(q.meals.toString())}
    var times by remember(repository) {mutableStateOf(q.times.joinToString(", "))}
    var cooking by remember(repository) {mutableStateOf(q.cooking.toString())}
    var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<Int?>(null)}
    val scope=rememberCoroutineScope()
    fun update(value:Questionnaire) {q=value;scope.launch {repository.draft(value)}}
    fun run(work:suspend ()->Unit) {if(busy) return;busy=true;error=null;scope.launch {try {work()} catch(c:CancellationException) {throw c} catch(e:Exception) {error=nutritionError(e)} finally {busy=false}}}
    fun move(step:Int) {val next=q.copy(step=step,completed=false);update(next);run {repository.draft(next);repository.sync()}}
    fun finish(apply:Boolean) {run {
        repository.finish(q,apply)
        onDone()
    }}
    androidx.activity.compose.BackHandler {if(!busy) {if(q.step>0) move(if(q.step==2 && q.goal=="diary") 0 else q.step-1) else if(!required) onBack()}}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(!required) BackButton(onBack)
        Text(stringResource(R.string.questionnaire),style=MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.step_progress,q.step+1),color=TextSecondary)
        LinearProgressIndicator(progress={(q.step+1)/4f},modifier=Modifier.fillMaxWidth(),color=AccentBrown,trackColor=ChartTrack)
        Text(stringResource(R.string.local_draft_hint),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        WellnessCard(Modifier.fillMaxWidth()) {
            when(q.step) {
                0->{Text(stringResource(R.string.choose_goal),style=MaterialTheme.typography.titleLarge)
                    Choice(q.goal,listOf("diary" to R.string.goal_diary,"maintain" to R.string.goal_maintain,"lose" to R.string.goal_lose,"gain" to R.string.goal_gain),!busy) {update(q.copy(goal=it))}}
                1->{
                    NumberInput(age,{age=it;update(q.copy(age=it.toIntOrNull()))},R.string.age_years,1.0,120.0,!busy,true)
                    NumberInput(height,{height=it;update(q.copy(height=it.replace(',','.').toDoubleOrNull()))},R.string.profile_height,50.0,250.0,!busy)
                    NumberInput(weight,{weight=it;update(q.copy(weight=it.replace(',','.').toDoubleOrNull()))},R.string.profile_weight,20.0,500.0,!busy)
                    Text(stringResource(R.string.formula_sex),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.formula_sex_hint),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                    Choice(q.sex,listOf("male" to R.string.sex_male,"female" to R.string.sex_female,"unspecified" to R.string.sex_unspecified),!busy) {update(q.copy(sex=it))}
                    Text(stringResource(R.string.activity_title),style=MaterialTheme.typography.titleMedium)
                    Choice(q.activity,listOf("sedentary" to R.string.activity_sedentary,"light" to R.string.activity_light,"moderate" to R.string.activity_moderate,"active" to R.string.activity_active),!busy) {update(q.copy(activity=it))}
                    Row(verticalAlignment=Alignment.CenterVertically) {WellnessCheckbox(q.special,{update(q.copy(special=it))},enabled=!busy);Text(stringResource(R.string.special_condition),Modifier.weight(1f))}
                }
                2->{Text(stringResource(R.string.plan_preferences),style=MaterialTheme.typography.titleLarge)
                    NumberInput(meals,{meals=it;update(q.copy(meals=it.toIntOrNull() ?: 0))},R.string.meal_number,1.0,6.0,!busy,true)
                    val validTimes=q.times.size==q.meals && q.times.all {it.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))}
                    WellnessTextField(times,{times=it;update(q.copy(times=it.split(',').map(String::trim)))},label={Text(stringResource(R.string.meal_times))},isError=!validTimes,
                        supportingText=if(!validTimes) ({Text(stringResource(R.string.time_count_error))}) else null,enabled=!busy,modifier=Modifier.fillMaxWidth(),colors=wellnessFieldColors())
                    listOf(Triple(q.preferences,R.string.food_preferences,{s:String->update(q.copy(preferences=s))}),
                        Triple(q.exclusions.joinToString(", "),R.string.excluded_foods,{s:String->update(q.copy(exclusions=s.split(',').map(String::trim).filter(String::isNotBlank)))}),
                        Triple(q.allergies.joinToString(", "),R.string.allergies_label,{s:String->update(q.copy(allergies=s.split(',').map(String::trim).filter(String::isNotBlank)))}),
                        Triple(q.budget,R.string.budget_label,{s:String->update(q.copy(budget=s))})).forEach {(v,label,f)->WellnessTextField(v,f,label={Text(stringResource(label))},modifier=Modifier.fillMaxWidth(),enabled=!busy,colors=wellnessFieldColors())}
                    NumberInput(cooking,{cooking=it;update(q.copy(cooking=it.toIntOrNull() ?: 0))},R.string.cooking_minutes,5.0,240.0,!busy,true)
                }
                else->{
                    if(q.goal!="diary") {
                        val calculated=runCatching {TargetCalculator.calculate(q)}.getOrNull()
                        if(calculated==null) Text(stringResource(R.string.automatic_unsuitable),color=WarningColor)
                        else {Text(stringResource(R.string.targets_preview),style=MaterialTheme.typography.titleLarge);TargetSummary(calculated)
                            Text(stringResource(R.string.calculation_explanation),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                            PrimaryButton(stringResource(R.string.apply_targets),{finish(true)},enabled=!busy && !state.dirty)}
                    } else Text(stringResource(R.string.goal_diary),style=MaterialTheme.typography.titleMedium)
                    PrimaryButton(stringResource(R.string.continue_diary),{finish(false)},enabled=!busy)
                }
            }
            if(q.step<3) {
                val valid=when(q.step) {1->q.age in 1..120 && q.height?.let {it.isFinite() && it in 50.0..250.0}==true && q.weight?.let {it.isFinite() && it in 20.0..500.0}==true
                    2->q.meals in 1..6 && q.cooking in 5..240 && q.times.size==q.meals && q.times.all {it.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))} && q.preferences.length<=400 && q.budget.length<=100 && (q.exclusions+q.allergies).all {it.length<=80} && q.exclusions.size<=20 && q.allergies.size<=20
                    else->true}
                PrimaryButton(stringResource(R.string.continue_action),{move(if(q.step==0 && q.goal=="diary") 2 else q.step+1)},enabled=!busy && valid)
            }
            if(q.step>0) WellnessTextButton({move(if(q.step==2 && q.goal=="diary") 0 else q.step-1)},enabled=!busy) {Text(stringResource(R.string.back))}
        }
        if(busy) WellnessLoadingIndicator()
        error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error);Text(stringResource(R.string.draft_saved),style=MaterialTheme.typography.bodySmall)
            WellnessTextButton({run {repository.sync()}},enabled=!busy) {Text(stringResource(R.string.retry))}
            if(it==R.string.edit_conflict) WellnessTextButton({run {repository.useServer();q=repository.state.value.document;age=q.age?.toString() ?: "";height=q.height?.toString() ?: "";weight=q.weight?.toString() ?: "";times=q.times.joinToString(", ");meals=q.meals.toString();cooking=q.cooking.toString()}},enabled=!busy) {Text(stringResource(R.string.use_server_copy))}}
        Spacer(Modifier.height(32.dp))
    }
}

@Composable fun TargetSummary(goals:DailyGoals) {
    listOf(R.string.calorie_goal_label to goals.calories,R.string.protein_goal_label to goals.protein,R.string.fat_goal_label to goals.fat,R.string.carbs_goal_label to goals.carbs).forEach {(label,value)->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(stringResource(label),Modifier.weight(1f));Text(value?.let {java.text.NumberFormat.getIntegerInstance().format(it)} ?: "—",style=MaterialTheme.typography.titleMedium.copy(fontFamily=BodyFont))}
    }
}

@Composable fun GoalsScreen(onBack:()->Unit,onQuestionnaire:()->Unit,onPlan:()->Unit) {
    val container=(LocalContext.current.applicationContext as TamaqApplication).container
    val repository=container.nutrition
    val history by container.settings.history.collectAsStateWithLifecycle()
    var date by remember {mutableStateOf(LocalDate.now())};var calendar by remember {mutableStateOf(false)}
    val initial=remember {container.settings.onDate(LocalDate.now())}
    val fields=remember {mutableStateListOf(initial.calories?.toString() ?: "",initial.protein?.toString() ?: "",initial.fat?.toString() ?: "",initial.carbs?.toString() ?: "")}
    var origin by remember {mutableStateOf(container.settings.originOn(date))}
    var preview by remember {mutableStateOf<DailyGoals?>(null)};var error by remember {mutableStateOf<Int?>(null)};var notice by remember {mutableStateOf(false)};var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val labels=listOf(R.string.calorie_goal_label,R.string.protein_goal_label,R.string.fat_goal_label,R.string.carbs_goal_label)
    fun valid(i:Int)=fields[i].isBlank() || fields[i].toIntOrNull()?.let {it in 1..if(i==0) 10000 else 1000}==true
    val values=DailyGoals(fields[0].toIntOrNull(),fields[1].toIntOrNull(),fields[2].toIntOrNull(),fields[3].toIntOrNull())
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        BackButton(onBack);Text(stringResource(R.string.goals_title),style=MaterialTheme.typography.headlineLarge)
        Text(stringResource(if(origin=="calculated") R.string.goals_origin_calculated else R.string.goals_origin_manual),color=TextSecondary)
        WellnessCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.goals_hint),color=TextSecondary)
            fields.forEachIndexed {i,value->WellnessTextField(value,{fields[i]=it;origin="manual";notice=false},label={Text(stringResource(labels[i]))},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=!valid(i),supportingText=if(!valid(i)) ({Text(stringResource(R.string.goals_error))}) else null,colors=wellnessFieldColors())}
            if(listOf(values.protein,values.fat,values.carbs).all {it!=null}) {
                val energy=4*values.protein!!+9*values.fat!!+4*values.carbs!!
                Text(stringResource(R.string.energy_difference,energy,energy-(values.calories ?: energy)),color=if(values.calories!=null && kotlin.math.abs(energy-values.calories)>50) WarningColor else TextSecondary)
            }
            WellnessOutlinedButton({calendar=true},enabled=!busy) {Text(stringResource(R.string.goals_effective,dateLabel(date)))}
            Text(stringResource(R.string.goals_date_hint),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
            PrimaryButton(stringResource(R.string.save),{if(!busy) {busy=true;error=null;scope.launch {try {repository.saveGoals(values,date,origin);notice=true} catch(c:CancellationException) {throw c} catch(e:Exception) {error=nutritionError(e)} finally {busy=false}}}},enabled=!busy && fields.indices.all(::valid) && !date.isBefore(LocalDate.now()))
        }
        WellnessOutlinedButton({error=null;preview=runCatching {TargetCalculator.calculate(repository.state.value.document)}.getOrElse {error=R.string.automatic_unsuitable;null}},Modifier.fillMaxWidth(),enabled=!busy) {Text(stringResource(R.string.recalculate_targets))}
        WellnessTextButton(onQuestionnaire) {Text(stringResource(R.string.questionnaire))}
        WellnessOutlinedButton(onPlan,Modifier.fillMaxWidth()) {Text(stringResource(R.string.meal_plan))}
        if(busy) WellnessLoadingIndicator()
        if(notice) Text(stringResource(R.string.goals_saved),color=SuccessColor)
        error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
        history.filter {it.date!=LocalDate.MIN}.takeLast(5).reversed().forEach {Text(stringResource(R.string.goals_effective,dateLabel(it.date))+" · "+stringResource(if(it.origin=="manual") R.string.goals_origin_manual else R.string.goals_origin_calculated),style=MaterialTheme.typography.bodySmall,color=TextSecondary)}
        Spacer(Modifier.height(24.dp))
    }
    if(calendar) CalendarDialog(date,{calendar=false}) {date=it}
    preview?.let {p->WellnessDialog(onDismissRequest={preview=null},title={Text(stringResource(R.string.targets_preview))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {TargetSummary(p);Text(stringResource(R.string.calculation_explanation))}},
        confirmButton={WellnessTextButton({fields[0]=p.calories?.toString() ?: "";fields[1]=p.protein?.toString() ?: "";fields[2]=p.fat?.toString() ?: "";fields[3]=p.carbs?.toString() ?: "";origin="calculated";preview=null}) {Text(stringResource(R.string.apply_preview))}},dismissButton={WellnessTextButton({preview=null}) {Text(stringResource(R.string.cancel))}})}
}

fun nutritionError(e:Exception):Int=when(e.message) {
    "edit_conflict","idempotency_conflict"->R.string.edit_conflict
    "automatic_calculation_unsuitable","calculation_fields_required","calculated_targets_mismatch"->R.string.automatic_unsuitable
    "account_required"->R.string.account_required
    "unknown_nutrition"->R.string.nutrition_unknown
    "plan_targets_required"->R.string.plan_targets_required
    "budget_exhausted","pilot_budget_exhausted","verification_budget_exhausted","daily_quota","rate_limit","quota_exceeded"->R.string.budget_exhausted
    "invalid_plan_result","invalid_provider_result","incomplete_response","provider_outcome_unknown","analysis_outcome_unknown"->R.string.plan_error
    else->if(e is ApiFailure && e.status==402 || e is ApiFailure && e.status==429) R.string.budget_exhausted else accountError(e)
}
