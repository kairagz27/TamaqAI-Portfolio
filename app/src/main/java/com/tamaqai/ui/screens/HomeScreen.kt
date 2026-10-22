package com.tamaqai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Menu
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.domain.model.Meal
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
fun HomeScreen(homeViewModel: HomeViewModel, dailyViewModel: DailyViewModel, photos: PhotoStore,
    onEdit: (String) -> Unit, onProfile: () -> Unit, modifier: Modifier = Modifier, history: Boolean = false, onMenu:()->Unit={},onAdd: () -> Unit = {}, bottomSpace: androidx.compose.ui.unit.Dp = 20.dp,onPlan:()->Unit={},
    onScan: () -> Unit = onAdd, onManual: () -> Unit = onAdd, menuFocusRequester: FocusRequester? = null) {
    val state by homeViewModel.uiState.collectAsStateWithLifecycle()
    var delete by remember { mutableStateOf<Meal?>(null) }
    var repeat by remember { mutableStateOf<Meal?>(null) }
    val container=(LocalContext.current.applicationContext as com.tamaqai.TamaqApplication).container
    val settings=container.settings
    val profile by container.account.profileState.collectAsStateWithLifecycle()
    val plans by container.plans.plans.collectAsStateWithLifecycle()
    val hour by produceState(LocalTime.now().hour) {
        while(true) { value=LocalTime.now().hour; delay(60_000) }
    }
    val historyGoals by settings.history.collectAsStateWithLifecycle()
    val goals=historyGoals.lastOrNull {it.date<=state.date}?.targets ?: com.tamaqai.domain.model.DailyGoals()
    var nutrient by remember {mutableStateOf<Int?>(null)}
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start=20.dp, top=12.dp, end=20.dp, bottom=bottomSpace),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key="greeting") {
            HomeGreeting(profile.name.takeIf {container.owner!="guest"}, hour, onMenu, history, menuFocusRequester)
        }
        item(key="date") { DateSelector(state.date, homeViewModel::selectDate) }
        if (state.loading) item { WellnessLoadingIndicator(); Text(stringResource(R.string.data_loading)) }
        state.error?.let { error -> item {
            Text(messageText(error), color = MaterialTheme.colorScheme.error)
            WellnessTextButton(onClick = homeViewModel::retry) { Text(stringResource(R.string.retry)) }
        } }
        if (state.incompleteNutritionCount > 0) item(key="incomplete-total") {
            IncompleteNutritionNotice(state.incompleteNutritionCount)
        }
        item(key="calories") { CaloriesCard(state.calories, goals.calories, onProfile,
            label = if (state.incompleteNutritionCount > 0) stringResource(R.string.catalog_totals_known_only) else null,
            onDetails={nutrient=0}) }
        item(key="macros") { MacroCards(state.protein, state.fat, state.carbs, goals) {nutrient=it} }
        item(key="food-actions") { HomeFoodActions(onScan,onManual,onAdd) }
        item(key="plan-preview") { HomePlanCard(plans.firstOrNull {it.date==state.date},container.plans.hasPendingGeneration,onPlan) }
        item(key="meals-title") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.meals_heading), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                Text(pluralStringResource(R.plurals.meal_count, state.meals.size,state.meals.size), color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!state.loading && state.meals.isEmpty()) item {
            HomeEmptyDiary(onAdd)
        }
        items(state.meals, key = Meal::uid) { meal ->
            androidx.compose.foundation.layout.Box(Modifier.animateItem(
                fadeInSpec=androidx.compose.animation.core.tween(com.tamaqai.ui.theme.WellnessMotion.enter),
                placementSpec=androidx.compose.animation.core.tween(com.tamaqai.ui.theme.WellnessMotion.enter),
                fadeOutSpec=androidx.compose.animation.core.tween(com.tamaqai.ui.theme.WellnessMotion.exit))) {
            MealCard(meal, photos, { homeViewModel.open(meal.uid, onEdit) },
                { dailyViewModel.favorite(meal.uid) }, { repeat = meal }, { delete = meal })
            }
        }
        if (state.drafts.isNotEmpty()) {
            item {
                Text(stringResource(R.string.drafts_heading), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.drafts_hint), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            items(state.drafts, key = Meal::uid) { meal ->
                MealCard(meal, photos, { homeViewModel.open(meal.uid, onEdit) }, onDelete = { delete = meal })
            }
        }
    }
    delete?.let { meal ->
        ConfirmDialog(R.string.meal_delete_title, R.string.meal_delete_body, { delete = null },
            { dailyViewModel.deleteMeal(meal.uid); delete = null })
    }
    repeat?.let { meal -> CalendarDialog(state.date, { repeat = null }) { target ->
        homeViewModel.selectDate(target)
        dailyViewModel.repeat(meal.uid, target, onEdit)
        repeat = null
    } }
    nutrient?.let {index->NutrientSheet(index,state.date,listOf(state.calories,state.protein,state.fat,state.carbs)[index],listOf(goals.calories,goals.protein,goals.fat,goals.carbs)[index],{nutrient=null},{nutrient=null;onProfile()},state.incompleteNutritionCount)}
}
