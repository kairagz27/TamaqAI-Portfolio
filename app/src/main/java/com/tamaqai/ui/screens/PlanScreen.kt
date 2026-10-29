package com.tamaqai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.TamaqApplication
import com.tamaqai.domain.model.*
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

@Composable private fun PlanTotals(n:PlanNutrients?) {
    if(n==null) Text(stringResource(R.string.nutrition_unknown),color=WarningColor)
    else Text(stringResource(R.string.plan_totals,n.calories,n.protein,n.fat,n.carbs),style=MaterialTheme.typography.bodyMedium)
}

@Composable fun PlanScreen(onBack:()->Unit,onGoals:()->Unit) {
    val container=(LocalContext.current.applicationContext as TamaqApplication).container
    val standalone=container.standalone!=null
    val repository=container.plans
    val plans by repository.plans.collectAsStateWithLifecycle()
    val history by container.settings.history.collectAsStateWithLifecycle()
    val fitness by container.nutrition.state.collectAsStateWithLifecycle()
    var selected by remember {mutableStateOf(plans.firstOrNull()?.id)}
    var date by remember {mutableStateOf(LocalDate.now())};var calendar by remember {mutableStateOf(false)}
    var edit by remember {mutableStateOf<PlannedMeal?>(null)}
    var eat by remember {mutableStateOf<PlannedMeal?>(null)};var portion by remember {mutableStateOf("100")}
    var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<Int?>(null)};var notice by remember {mutableStateOf<Int?>(null)}
    var pending by remember {mutableStateOf(repository.hasPendingGeneration)}
    val scope=rememberCoroutineScope()
    val plan=plans.firstOrNull {it.id==selected}
    fun run(work:suspend ()->Unit) {if(busy) return;busy=true;error=null;notice=null;scope.launch {try {work()} catch(c:CancellationException) {throw c} catch(e:Exception) {error=nutritionError(e)} finally {pending=repository.hasPendingGeneration;busy=false}}}
    LaunchedEffect(repository) {try {repository.load()} catch(c:CancellationException) {throw c} catch(e:Exception) {error=nutritionError(e)};if(selected==null) selected=repository.plans.value.firstOrNull()?.id}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        BackButton(onBack);Text(stringResource(R.string.meal_plan),style=MaterialTheme.typography.headlineLarge)
        Text(stringResource(if(standalone) R.string.standalone_plan_local_hint else R.string.plan_warning),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        WellnessOutlinedButton({calendar=true},enabled=!busy) {Text(dateLabel(date))}
        PrimaryButton(stringResource(R.string.plan_manual),{run {val p=MealPlan(date=date);repository.local(p);selected=p.id;notice=R.string.plan_local_saved}},enabled=!busy)
        val goals=container.settings.onDate(date)
        val goalDate=history.lastOrNull {it.date<=date}?.date
        if(!standalone) PrimaryButton(stringResource(if(pending) R.string.plan_retry else R.string.plan_generate),{run {
            if(goalDate==null || goalDate==LocalDate.MIN || goals.calories==null || !fitness.document.completed) throw IllegalStateException("plan_targets_required")
            selected=repository.generate(date,fitness.version,goalDate,com.tamaqai.ui.appLocale().language.takeIf {it in listOf("ru","kk","en")} ?: "en")
        }},enabled=!busy && (pending || container.owner!="guest" && fitness.document.completed && goals.calories!=null && goalDate!=LocalDate.MIN && !fitness.dirty))
        if(!standalone && pending) WellnessTextButton({repository.abandonGeneration();pending=false},enabled=!busy) {Text(stringResource(R.string.plan_abandon))}
        if(standalone) Text(stringResource(R.string.standalone_infrastructure),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        WellnessTextButton(onGoals) {Text(stringResource(R.string.setup_goals))}
        if(container.owner=="guest") Text(stringResource(R.string.account_required),color=TextSecondary)
        if(busy) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {WellnessLoadingIndicator(Modifier.size(24.dp));Text(stringResource(R.string.account_working))}
        FeedbackPanel(error?.let {WellnessFeedback(stringResource(it),error=true)}
            ?: notice?.let {WellnessFeedback(stringResource(it))})
        plans.forEach {p->WellnessOutlinedButton({selected=p.id;date=p.date},Modifier.fillMaxWidth(),enabled=!busy) {Text(dateLabel(p.date)+" · "+stringResource(if(standalone || p.dirty) R.string.plan_local_saved else R.string.plan_synced)+(if(p.demo) " · DEMO" else ""))}}
        if(plan==null && !busy) EmptyState(stringResource(R.string.meal_plan),stringResource(R.string.plan_warning),scene=CeramicScene.Plan)
        if(plan!=null) {
            WellnessCard(Modifier.fillMaxWidth()) {
                Text(dateLabel(plan.date),style=MaterialTheme.typography.titleLarge)
                if(plan.demo) Text("DEMO",color=WarningColor)
                PlanTotals(plan.total)
                val target=container.settings.onDate(plan.date)
                plan.total?.let {n->if(!target.isEmpty) {
                    Text(stringResource(R.string.plan_deviation_heading),style=MaterialTheme.typography.titleMedium)
                    listOf(Triple(R.string.calorie_goal_label,n.calories,target.calories),Triple(R.string.protein_goal_label,n.protein,target.protein),
                        Triple(R.string.fat_goal_label,n.fat,target.fat),Triple(R.string.carbs_goal_label,n.carbs,target.carbs)).forEach {(label,amount,goal)->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(stringResource(label),Modifier.weight(1f))
                            Text(goal?.let {decimal(amount-it,1)} ?: "—")
                        }
                    }
                }}
                Text(stringResource(R.string.plan_offline),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                if(!standalone) WellnessOutlinedButton({run {repository.sync(plan.id);notice=R.string.plan_synced}},enabled=!busy && container.owner!="guest") {Text(stringResource(R.string.plan_sync))}
                if(error==R.string.edit_conflict) WellnessTextButton({run {selected=repository.saveAsCopy(plan.id);notice=R.string.plan_local_saved}},enabled=!busy) {Text(stringResource(R.string.plan_copy))}
            }
            plan.meals.forEach {m->WellnessCard(Modifier.fillMaxWidth()) {
                Text(m.time+" · "+m.dish,style=MaterialTheme.typography.titleLarge)
                m.ingredients.forEach {i->Text(i.name+" · "+decimal(i.quantity,2)+" "+stringResource(when(i.unit) {"g"->R.string.unit_grams;"ml"->R.string.unit_ml;else->R.string.unit_piece}),style=MaterialTheme.typography.bodyMedium)}
                PlanTotals(m.total)
                WellnessOutlinedButton({edit=m},enabled=!busy) {Text(stringResource(R.string.plan_edit_meal))}
                WellnessTextButton({run {repository.local(plan.copy(meals=plan.meals.filterNot {it.id==m.id}));notice=R.string.plan_local_saved}},enabled=!busy) {Text(stringResource(R.string.delete))}
                PrimaryButton(stringResource(R.string.plan_eat),{eat=m;portion="100"},enabled=!busy && m.total!=null)
            }}
            WellnessOutlinedButton({edit=PlannedMeal()},Modifier.fillMaxWidth(),enabled=!busy && plan.meals.size<6) {Text(stringResource(R.string.plan_add_meal))}
        }
        Spacer(Modifier.height(24.dp))
    }
    if(calendar) CalendarDialog(date,{calendar=false}) {date=it}
    edit?.let {m->PlannedMealEditor(m,{edit=null}) {updated->if(plan!=null) run {repository.local(plan.copy(meals=if(plan.meals.any {it.id==updated.id}) plan.meals.map {if(it.id==updated.id) updated else it} else plan.meals+updated));edit=null;notice=R.string.plan_local_saved}}}
    eat?.let {m->WellnessDialog(onDismissRequest={if(!busy) eat=null},title={Text(stringResource(R.string.plan_eat))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(m.dish);WellnessTextField(portion,{portion=it},label={Text(stringResource(R.string.actual_portion))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),colors=wellnessFieldColors())
        error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
    }},confirmButton={WellnessTextButton({run {repository.eat(requireNotNull(plan).id,m.id,portion.replace(',','.').toDouble()/100);eat=null;notice=R.string.plan_eaten_done}},enabled=!busy && portion.replace(',','.').toDoubleOrNull()?.let {it.isFinite() && it in 5.0..2000.0}==true) {Text(stringResource(R.string.confirm))}},dismissButton={WellnessTextButton({eat=null},enabled=!busy) {Text(stringResource(R.string.cancel))}})}
}

@Composable private fun PlannedMealEditor(original:PlannedMeal,onDismiss:()->Unit,onSave:(PlannedMeal)->Unit) {
    var dish by remember(original.id) {mutableStateOf(original.dish)};var time by remember(original.id) {mutableStateOf(original.time)}
    var ingredients by remember(original.id) {mutableStateOf(original.ingredients)};var ingredient by remember {mutableStateOf<PlanIngredient?>(null)}
    WellnessFullScreenDialog(onDismissRequest=onDismiss) {
        Surface(color=androidx.compose.ui.graphics.Color.Transparent,tonalElevation=0.dp,modifier=Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                BackButton(onDismiss);Text(stringResource(R.string.plan_edit_meal),style=MaterialTheme.typography.headlineSmall)
                WellnessTextField(dish,{dish=it},label={Text(stringResource(R.string.plan_dish))},modifier=Modifier.fillMaxWidth(),isError=dish.length>120,colors=wellnessFieldColors())
                WellnessTextField(time,{time=it},label={Text(stringResource(R.string.plan_time))},modifier=Modifier.fillMaxWidth(),singleLine=true,isError=!time.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")),colors=wellnessFieldColors())
                ingredients.forEach {i->WellnessCard(Modifier.fillMaxWidth()) {
                    Text(i.name+" · "+decimal(i.quantity,2)+" "+stringResource(when(i.unit) {"g"->R.string.unit_grams;"ml"->R.string.unit_ml;else->R.string.unit_piece}))
                    PlanTotals(i.total)
                    WellnessOutlinedButton({ingredient=i}) {Text(stringResource(R.string.plan_edit_ingredient))}
                    WellnessTextButton({ingredients=ingredients.filterNot {it.id==i.id}}) {Text(stringResource(R.string.delete))}
                }}
                WellnessOutlinedButton({ingredient=PlanIngredient()},enabled=ingredients.size<12) {Text(stringResource(R.string.plan_add_ingredient))}
                val result=PlannedMeal(original.id,time,dish,ingredients)
                PlanTotals(result.total)
                PrimaryButton(stringResource(R.string.plan_save_meal),{onSave(result)},enabled=runCatching {result.validate()}.isSuccess)
                WellnessTextButton(onDismiss) {Text(stringResource(R.string.cancel))}
            }
        }
    }
    ingredient?.let {i->PlanIngredientEditor(i,{ingredient=null}) {updated->ingredients=if(ingredients.any {it.id==updated.id}) ingredients.map {if(it.id==updated.id) updated else it} else ingredients+updated;ingredient=null}}
}

@Composable private fun PlanIngredientEditor(original:PlanIngredient,onDismiss:()->Unit,onSave:(PlanIngredient)->Unit) {
    var name by remember {mutableStateOf(original.name)};var quantity by remember {mutableStateOf(original.quantity.toString())};var unit by remember {mutableStateOf(original.unit)}
    var known by remember {mutableStateOf(original.nutrition!=null)}
    val fields=remember {mutableStateListOf(original.nutrition?.calories?.toString() ?: "",original.nutrition?.protein?.toString() ?: "",original.nutrition?.fat?.toString() ?: "",original.nutrition?.carbs?.toString() ?: "")}
    val labels=listOf(R.string.calorie_goal_label,R.string.protein_goal_label,R.string.fat_goal_label,R.string.carbs_goal_label)
    val amount=quantity.replace(',','.').toDoubleOrNull()
    val numbers=fields.map {it.replace(',','.').toDoubleOrNull()}
    val nutrients=if(known && numbers.all {it!=null}) PlanNutrients(numbers[0]!!,numbers[1]!!,numbers[2]!!,numbers[3]!!) else null
    // Existing nutrition keeps its immutable basis. New manually entered values
    // describe the quantity currently shown; subsequent edits scale from it.
    val basis=if(original.nutrition!=null && unit==original.unit) original.basis else amount ?: 100.0
    val result=PlanIngredient(original.id,name,amount ?: 0.0,unit,basis,nutrients)
    WellnessFullScreenDialog(onDismissRequest=onDismiss) {
        Surface(color=androidx.compose.ui.graphics.Color.Transparent,tonalElevation=0.dp,modifier=Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                BackButton(onDismiss);Text(stringResource(R.string.ingredient_name),style=MaterialTheme.typography.headlineSmall)
                WellnessTextField(name,{name=it},label={Text(stringResource(R.string.ingredient_name))},isError=name.length>120,modifier=Modifier.fillMaxWidth(),colors=wellnessFieldColors())
                WellnessTextField(quantity,{quantity=it},label={Text(stringResource(R.string.ingredient_quantity))},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),isError=amount?.let {it.isFinite() && it in .001..10000.0}!=true,colors=wellnessFieldColors())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("g" to R.string.unit_grams,"ml" to R.string.unit_ml,"piece" to R.string.unit_piece).forEach {(u,label)->FilterChip(unit==u,{if(unit!=u) {unit=u;known=false;fields.indices.forEach {fields[it]=""}}},label={Text(stringResource(label))},colors=wellnessChipColors())}}
                Row(verticalAlignment=Alignment.CenterVertically) {WellnessCheckbox(known,{known=it});Text(stringResource(R.string.nutrition_known),Modifier.weight(1f))}
                if(known) {
                    Text(stringResource(R.string.nutrition_baseline,decimal(basis,2),stringResource(when(unit) {"g"->R.string.unit_grams;"ml"->R.string.unit_ml;else->R.string.unit_piece})),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                    fields.forEachIndexed {n,value->WellnessTextField(value,{fields[n]=it},label={Text(stringResource(labels[n]))},modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),isError=numbers[n]?.let {it.isFinite() && it in 0.0..if(n==0) 10000.0 else 1000.0}!=true,colors=wellnessFieldColors())}
                } else Text(stringResource(R.string.nutrition_unknown),color=WarningColor)
                PlanTotals(result.total)
                PrimaryButton(stringResource(R.string.plan_save_ingredient),{onSave(result)},enabled=(!known || nutrients!=null) && runCatching {result.validate()}.isSuccess)
                WellnessTextButton(onDismiss) {Text(stringResource(R.string.cancel))}
            }
        }
    }
}
