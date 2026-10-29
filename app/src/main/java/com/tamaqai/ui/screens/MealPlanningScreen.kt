package com.tamaqai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.domain.model.*
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import java.time.LocalDate

private data class RecipeRequest(val date: String, val replaceEntry: String? = null)

/** The existing plan destination now contains local multi-day planning and its shopping snapshot. */
@Composable
fun MealPlanningScreen(vm: PlanningViewModel, onBack: () -> Unit, onLegacyPlans: () -> Unit, onGoals: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var shoppingTab by rememberSaveable { mutableStateOf(false) }
    var calendar by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<RecipeRequest?>(null) }
    var servings by remember { mutableStateOf<PlannedRecipeEntry?>(null) }
    var eating by remember { mutableStateOf<PlannedRecipeEntry?>(null) }
    var deleting by remember { mutableStateOf<PlannedRecipeEntry?>(null) }
    var copying by remember { mutableStateOf<String?>(null) }
    val language = LocalConfiguration.current.locales[0].language
    val workspace = state.workspace
    LaunchedEffect(workspace == null) {
        if (workspace == null) {
            calendar = false; picker = null; servings = null; eating = null; deleting = null; copying = null
        }
    }

    Column(Modifier.fillMaxSize().testTag("meal-planning-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(stringResource(R.string.mp_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!shoppingTab, { shoppingTab = false }, label = { Text(stringResource(R.string.mp_plan_tab)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("planner-plan-tab"), colors = wellnessChipColors())
            FilterChip(shoppingTab, { shoppingTab = true }, label = { Text(stringResource(R.string.mp_shopping_tab)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("planner-shopping-tab"), colors = wellnessChipColors())
        }
        state.error?.let { error ->
            Text(stringResource(error), Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
        state.notice?.let { notice ->
            Text(stringResource(notice), Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, color = SuccessColor,
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.loading) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WellnessLoadingIndicator(); Text(stringResource(R.string.data_loading))
            }
        } else if (workspace == null) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mp_error_storage))
                PrimaryButton(stringResource(R.string.retry), vm::initialize)
            }
        } else if (shoppingTab) {
            ShoppingListScreen(state, vm, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).imePadding().testTag("planner-days-list"),
                contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("period") {
                    WellnessCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.mp_period), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.mp_local_hint), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        WellnessOutlinedButton({ calendar = true }, Modifier.fillMaxWidth().testTag("planner-start-date"), enabled = !state.busy) {
                            Text(stringResource(R.string.mp_start_date, dateLabel(LocalDate.parse(workspace.startDate))))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.mp_days), Modifier.weight(1f))
                            IconButton({ vm.setPeriod(workspace.startDate, workspace.dayCount - 1) },
                                enabled = !state.busy && workspace.dayCount > 1, modifier = Modifier.testTag("planner-days-minus")) {
                                Icon(Icons.Outlined.Remove, stringResource(R.string.mp_days_less))
                            }
                            Text(workspace.dayCount.toString(), Modifier.testTag("planner-day-count"),
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = BodyFont))
                            IconButton({ vm.setPeriod(workspace.startDate, workspace.dayCount + 1) },
                                enabled = !state.busy && workspace.dayCount < 7, modifier = Modifier.testTag("planner-days-plus")) {
                                Icon(Icons.Outlined.Add, stringResource(R.string.mp_days_more))
                            }
                        }
                        WellnessTextButton(onGoals, enabled = !state.busy) { Text(stringResource(R.string.setup_goals)) }
                    }
                }
                workspace.dates.forEach { date ->
                    val entries = workspace.entries.filter { it.date == date }
                    item("day-$date") {
                        Column(Modifier.fillMaxWidth().testTag("planner-day-$date"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(dateLabel(LocalDate.parse(date)), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                            WellnessCard(Modifier.fillMaxWidth()) {
                                PlanningNutrients(workspace.totalsForDate(date), state.goals[date], title = true)
                                if (entries.isEmpty()) Text(stringResource(R.string.mp_empty_day), color = TextSecondary)
                                PrimaryButton(stringResource(R.string.mp_add_recipe), { picker = RecipeRequest(date) },
                                    Modifier.testTag("planner-add-$date"), enabled = !state.busy)
                                if (workspace.dayCount > 1) WellnessTextButton({ copying = date },
                                    Modifier.testTag("planner-copy-$date"), enabled = !state.busy && entries.isNotEmpty()) {
                                    Text(stringResource(R.string.mp_copy_day))
                                }
                            }
                        }
                    }
                    entries.sortedBy { it.mealType.ordinal }.forEach { entry ->
                        item("entry-${entry.id}") {
                            PlannedRecipeCard(entry, language, state.busy,
                                onServings = { servings = entry }, onReplace = { picker = RecipeRequest(date, entry.id) },
                                onDelete = { deleting = entry }, onEat = { eating = entry })
                        }
                    }
                }
                item("legacy") {
                    WellnessCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.mp_legacy_plans), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.mp_import_hint), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        WellnessOutlinedButton(vm::importSavedDailyPlans, Modifier.fillMaxWidth().testTag("planner-import"),
                            enabled = !state.busy && state.legacyPlanCount > 0) { Text(stringResource(R.string.mp_import)) }
                        if (state.legacyPlanCount == 0) Text(stringResource(R.string.mp_import_empty), color = TextSecondary)
                        WellnessTextButton(onLegacyPlans, Modifier.fillMaxWidth(), enabled = !state.busy) {
                            Text(stringResource(R.string.mp_legacy_plans))
                        }
                    }
                }
            }
        }
    }
    if (calendar && workspace != null) CalendarDialog(LocalDate.parse(workspace.startDate), { calendar = false }) {
        vm.setPeriod(it.toString(), workspace.dayCount)
    }
    picker?.let { request ->
        RecipePicker(state.recipes, request.date, language, request.replaceEntry != null, state.busy, { picker = null }) { recipe, slot, amount ->
            if (request.replaceEntry == null) vm.addRecipe(request.date, slot, recipe.id, amount)
            else vm.replaceRecipe(request.replaceEntry, recipe.id)
            picker = null
        }
    }
    servings?.let { entry ->
        ServingsDialog(entry, language, state.busy, false, { servings = null }) {
            vm.changeServings(entry.id, it); servings = null
        }
    }
    eating?.let { entry ->
        ServingsDialog(entry, language, state.busy, true, { eating = null }) {
            vm.confirmEaten(entry.id, it); eating = null
        }
    }
    deleting?.let { entry ->
        ConfirmDialog(R.string.mp_remove_entry, R.string.mp_remove_hint, { deleting = null }) {
            vm.deleteEntry(entry.id); deleting = null
        }
    }
    copying?.let { source -> workspace?.let { current ->
        CopyDayDialog(source, current.dates, state.busy, { copying = null }) { target ->
            vm.copyDay(source, target); copying = null
        }
    } }
}

@Composable
private fun PlannedRecipeCard(entry: PlannedRecipeEntry, language: String, busy: Boolean,
                              onServings: () -> Unit, onReplace: () -> Unit, onDelete: () -> Unit, onEat: () -> Unit) {
    var ingredients by rememberSaveable(entry.id) { mutableStateOf(false) }
    WellnessCard(Modifier.fillMaxWidth().testTag("planner-entry-${entry.id}")) {
        Text(stringResource(mealSlotResource(entry.mealType)), style = MaterialTheme.typography.labelLarge, color = AccentBrown)
        Text(entry.recipe.displayName(language), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.mp_servings_value, decimal(entry.servings, 2)), color = TextSecondary)
        PlanningNutrients(entry.nutrients)
        WellnessOutlinedButton(onServings, Modifier.fillMaxWidth().testTag("planner-servings-${entry.id}"), enabled = !busy) {
            Text(stringResource(R.string.mp_change_servings))
        }
        WellnessTextButton({ ingredients = !ingredients }, enabled = !busy) { Text(stringResource(R.string.mp_ingredients)) }
        if (ingredients) entry.scaledIngredients.forEach { ingredient ->
            Text(ingredient.displayName(language) + " · " + planningAmount(ingredient.quantity, ingredient.unit) + " · " +
                stringResource(preparationResource(ingredient.preparation)), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            ingredient.amountNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
        }
        if (entry.recipe.demo) Text(stringResource(R.string.mp_recipe_demo_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        else Text(entry.recipe.source, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        WellnessOutlinedButton(onReplace, Modifier.fillMaxWidth().testTag("planner-replace-${entry.id}"), enabled = !busy) {
            Text(stringResource(R.string.mp_replace))
        }
        PrimaryButton(stringResource(R.string.mp_eaten_action), onEat, Modifier.testTag("planner-eat-${entry.id}"),
            enabled = !busy && entry.nutrients.complete)
        if (!entry.nutrients.complete) Text(stringResource(R.string.mp_unknown_nutrients), style = MaterialTheme.typography.bodySmall, color = WarningColor)
        WellnessTextButton(onDelete, Modifier.testTag("planner-delete-${entry.id}"), enabled = !busy) { Text(stringResource(R.string.delete)) }
    }
}

@Composable
internal fun PlanningNutrients(nutrients: RecipeNutrients, goals: DailyGoals? = null, title: Boolean = false) {
    if (title) Text(stringResource(R.string.mp_day_totals), style = MaterialTheme.typography.titleMedium)
    val rows = listOf(Triple(R.string.calorie_goal_label, nutrients.calories, goals?.calories),
        Triple(R.string.protein_goal_label, nutrients.protein, goals?.protein), Triple(R.string.fat_goal_label, nutrients.fat, goals?.fat),
        Triple(R.string.carbs_goal_label, nutrients.carbs, goals?.carbs))
    rows.forEach { (label, amount, target) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text(stringResource(label), Modifier.weight(1f), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text((amount?.let { decimal(it, 1) } ?: "—") + if (goals != null) " / " + (target?.let { decimal(it.toDouble(), 0) } ?: "—") else "",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BodyFont))
        }
    }
    if (!nutrients.complete) Text(stringResource(R.string.mp_incomplete_total), color = WarningColor, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipePicker(recipes: List<Recipe>, date: String, language: String, replacing: Boolean, busy: Boolean,
                         onDismiss: () -> Unit, onSelect: (Recipe, MealSlot, Double) -> Unit) {
    var slot by rememberSaveable { mutableStateOf(MealSlot.Lunch) }
    var servings by rememberSaveable { mutableStateOf("1") }
    val amount = planningNumber(servings)
    WellnessFullScreenDialog(onDismiss) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("planner-recipe-picker"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BackButton(onDismiss)
            Text(stringResource(if (replacing) R.string.mp_replace else R.string.mp_recipe_library), style = MaterialTheme.typography.headlineLarge)
            Text(dateLabel(LocalDate.parse(date)), color = TextSecondary)
            Text(stringResource(R.string.mp_recipe_demo_hint), color = TextSecondary)
            if (!replacing) {
                Text(stringResource(R.string.meal_type), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSlot.entries.forEach { value -> FilterChip(value == slot, { slot = value },
                        label = { Text(stringResource(mealSlotResource(value))) }, colors = wellnessChipColors(), enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("planner-meal-slot-${value.name}")) }
                }
                PlanningNumberField(servings, { servings = it }, R.string.mp_servings, Modifier.testTag("planner-new-servings"), !busy)
            }
            recipes.forEach { recipe ->
                WellnessCard(Modifier.fillMaxWidth()) {
                    Text(recipe.displayName(language), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.mp_recipe_yield, decimal(recipe.yieldServings, 2)), color = TextSecondary)
                    Text(stringResource(R.string.mp_recipe_nutrition), style = MaterialTheme.typography.titleMedium)
                    PlanningNutrients(recipe.nutrients.scale(1.0 / recipe.yieldServings))
                    Text(stringResource(R.string.mp_recipe_ingredients_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    recipe.ingredients.forEach { ingredient ->
                        Text(ingredient.displayName(language) + " · " + planningAmount(ingredient.quantity, ingredient.unit) + " · " +
                            stringResource(preparationResource(ingredient.preparation)), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!recipe.demo) Text(recipe.source, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    PrimaryButton(stringResource(R.string.mp_select_recipe), { onSelect(recipe, slot, amount ?: 1.0) },
                        Modifier.testTag("planner-recipe-${recipe.id}"), enabled = !busy && (replacing || amount != null && amount in .05..100.0))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ServingsDialog(entry: PlannedRecipeEntry, language: String, busy: Boolean, eaten: Boolean,
                           onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var input by rememberSaveable(entry.id, eaten) { mutableStateOf(entry.servings.toString()) }
    val value = planningNumber(input)
    WellnessDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (eaten) R.string.mp_eaten_action else R.string.mp_change_servings)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.recipe.displayName(language), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.mp_record_date, dateLabel(LocalDate.parse(entry.date))), color = TextSecondary)
            if (eaten) Text(stringResource(R.string.mp_actual_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            PlanningNumberField(input, { input = it }, if (eaten) R.string.mp_actual_servings else R.string.mp_servings,
                Modifier.testTag(if (eaten) "planner-actual-servings" else "planner-edit-servings"), !busy)
        } },
        confirmButton = { WellnessTextButton({ value?.let(onConfirm) }, Modifier.testTag("planner-servings-confirm"),
            enabled = !busy && value != null && value in .05..100.0) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { WellnessTextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CopyDayDialog(source: String, dates: List<String>, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var target by rememberSaveable(source) { mutableStateOf(dates.first { it != source }) }
    WellnessDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.mp_copy_day)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mp_copy_day_hint), color = TextSecondary)
            dates.filter { it != source }.forEach { date ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy, role = Role.RadioButton) { target = date }
                    .testTag("planner-copy-target-$date"), verticalAlignment = Alignment.CenterVertically) {
                    WellnessRadioButton(target == date, { target = date }, enabled = !busy)
                    Text(dateLabel(LocalDate.parse(date)), Modifier.weight(1f))
                }
            }
            Text(stringResource(R.string.mp_copy_confirm, dateLabel(LocalDate.parse(target)), dateLabel(LocalDate.parse(source))), color = WarningColor)
        } },
        confirmButton = { WellnessTextButton({ onConfirm(target) }, Modifier.testTag("planner-copy-confirm"), enabled = !busy) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { WellnessTextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun PlanningNumberField(value: String, onChange: (String) -> Unit, label: Int,
                                 modifier: Modifier = Modifier, enabled: Boolean = true) {
    val valid = planningNumber(value)?.let { it in .05..100.0 } == true
    WellnessTextField(value, onChange, modifier = modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = !valid, supportingText = { Text(stringResource(R.string.mp_servings_hint)) }, colors = wellnessFieldColors())
}

internal fun planningNumber(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

@Composable internal fun planningAmount(amount: Double?, unit: RecipeUnit): String =
    if (amount == null) stringResource(R.string.mp_unknown_quantity) else decimal(amount, 2) + " " + stringResource(recipeUnitResource(unit))

internal fun recipeUnitResource(unit: RecipeUnit): Int = when (unit) {
    RecipeUnit.Gram -> R.string.unit_grams
    RecipeUnit.Kilogram -> R.string.mp_unit_kg
    RecipeUnit.Milliliter -> R.string.unit_ml
    RecipeUnit.Liter -> R.string.mp_unit_l
    RecipeUnit.Piece -> R.string.unit_piece
    RecipeUnit.Unknown -> R.string.mp_unit_unknown
}
internal fun mealSlotResource(slot: MealSlot): Int = when (slot) {
    MealSlot.Breakfast -> R.string.breakfast; MealSlot.Lunch -> R.string.lunch
    MealSlot.Dinner -> R.string.dinner; MealSlot.Snack -> R.string.snack
}
internal fun preparationResource(value: FoodPreparation): Int = when (value) {
    FoodPreparation.Raw -> R.string.mp_preparation_raw; FoodPreparation.Cooked -> R.string.mp_preparation_cooked
    FoodPreparation.Ready -> R.string.mp_preparation_ready; FoodPreparation.Unknown -> R.string.mp_preparation_unknown
}
internal fun categoryResource(value: ShoppingCategory): Int = when (value) {
    ShoppingCategory.Vegetables -> R.string.mp_category_vegetables; ShoppingCategory.Fruit -> R.string.mp_category_fruit
    ShoppingCategory.Grains -> R.string.mp_category_grains; ShoppingCategory.Protein -> R.string.mp_category_protein
    ShoppingCategory.Dairy -> R.string.mp_category_dairy; ShoppingCategory.Bakery -> R.string.mp_category_bakery
    ShoppingCategory.Drinks -> R.string.mp_category_drinks; ShoppingCategory.Spices -> R.string.mp_category_spices
    ShoppingCategory.Other -> R.string.mp_category_other
}
internal fun shoppingStatusResource(value: ShoppingStatus): Int = when (value) {
    ShoppingStatus.Needed -> R.string.mp_status_needed; ShoppingStatus.Bought -> R.string.mp_status_bought
    ShoppingStatus.AtHome -> R.string.mp_status_home
}
