package com.tamaqai.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamaqai.BuildConfig
import com.tamaqai.R
import com.tamaqai.TamaqApplication
import com.tamaqai.data.DemoMeals
import com.tamaqai.domain.model.*
import com.tamaqai.ui.TamaqViewModelFactory
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.TextSecondary
import com.tamaqai.ui.theme.*

@Composable fun MealEditorScreen(uid: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as TamaqApplication
    val vm: MealEditorViewModel = viewModel(key = uid, factory = TamaqViewModelFactory(application.container, uid))
    val state by vm.state.collectAsStateWithLifecycle()
    var invite by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var deleteOriginal by remember { mutableStateOf(false) }
    var catalogVersion by rememberSaveable {mutableStateOf<String?>(null)}
    var catalogComponentIndex by rememberSaveable {mutableStateOf<Int?>(null)}
    var catalogCandidates by remember {mutableStateOf<List<Pair<Int,CatalogEntry>>>(emptyList())}
    var matchedNames by remember {mutableStateOf(emptyList<String>())}
    var catalogMatchFailed by remember {mutableStateOf(false)}
    var matchRetry by remember {mutableIntStateOf(0)}
    val recognized=state.meal
    val componentNames=recognized?.details?.components?.map {it.name} ?: emptyList()
    LaunchedEffect(recognized?.source,recognized?.details?.analysisId,componentNames,recognized?.details?.catalogPendingManual,matchRetry) {
        catalogCandidates=emptyList();catalogMatchFailed=false
        matchedNames=emptyList()
        if(recognized?.source==NutritionSource.AiEstimate && recognized.details.components.isNotEmpty() && !recognized.details.catalogPendingManual) {
            try {catalogCandidates=application.container.catalog.matchComponents(componentNames);matchedNames=componentNames}
            catch(cancel:kotlinx.coroutines.CancellationException) {throw cancel}
            catch(_:Exception) {catalogMatchFailed=true}
        }
    }
    LaunchedEffect(vm) { vm.close.collect { onBack() } }
    if(catalogVersion!=null) {
        CatalogScreen(repository=application.container.catalog,kind="product",initialVersionId=catalogVersion,
            onBack={catalogVersion=null},onSelected={selection ->catalogVersion=null;vm.selectCatalog(selection,catalogComponentIndex)})
        return
    }
    BackHandler { if (state.busy) vm.cancelAnalysis() else vm.leave() }
    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), bottomBar = {
        if (state.meal != null) Surface(color = Color.Transparent, tonalElevation = 0.dp,
            modifier = Modifier.glassBackdrop(material = WellnessGlassMaterial.Navigation)) {
            Column(Modifier.fillMaxWidth().imePadding().padding(16.dp)) {
                PrimaryButton(stringResource(R.string.save_meal), vm::confirm,
                    enabled = !state.busy && !state.saving && state.snapshot != null && state.error == null)
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = WellnessSpace.page),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = vm::leave, enabled = !state.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_draft)) }
                Text(stringResource(R.string.review_title), style = MaterialTheme.typography.titleLarge)
            }
            if (state.loading) { WellnessLoadingIndicator(); Text(stringResource(R.string.data_loading)) }
            state.meal?.let { meal ->
                val hasPhoto = application.container.photos.availableFile(meal.details.photoName) != null
                if (hasPhoto) MealPhoto(application.container.photos, meal.details.photoName, Modifier.fillMaxWidth().height(236.dp))
                Column(Modifier.fillMaxWidth().offset(y = if (hasPhoto) (-28).dp else 0.dp)
                    .matteSurface()
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(meal.source.label(), style = MaterialTheme.typography.labelLarge, color = AccentBrown)
                if (meal.details.userEdited) Text(stringResource(R.string.edited_values), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                meal.details.catalogSourceLabel?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=TextSecondary)}
                meal.details.catalogSnapshot?.let {CatalogReceipt(it)}
                if(meal.details.catalogPendingManual) Text(stringResource(R.string.catalog_unknown_manual),color=WarningColor)
                if(meal.source==NutritionSource.AiEstimate) {
                    Text(stringResource(R.string.catalog_match_hint),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                    if(catalogCandidates.isNotEmpty() && matchedNames==componentNames && !meal.details.catalogPendingManual) TamaqCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.catalog_match_title),style=MaterialTheme.typography.titleMedium)
                        catalogCandidates.filter {it.first in meal.details.components.indices}.forEach {(index,candidate) ->
                            WellnessOutlinedButton(onClick={catalogComponentIndex=index;catalogVersion=candidate.versionId},enabled=!state.busy,
                                modifier=Modifier.fillMaxWidth()) {Column {
                                Text("${meal.details.components[index].name} → ${candidate.name}")
                                Text(candidate.source.url,style=MaterialTheme.typography.bodySmall)
                            }}
                        }
                    }
                    if(catalogMatchFailed) {Text(stringResource(R.string.catalog_match_error),style=MaterialTheme.typography.bodySmall)
                        WellnessTextButton({matchRetry++}) {Text(stringResource(R.string.retry))}}
                }
                Text(meal.status.label(), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                WellnessTextField(state.name, vm::name, label = { Text(stringResource(R.string.name)) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                    isError = state.name.isBlank() || state.name.length > 120,
                    supportingText = if (state.name.isBlank() || state.name.length > 120) ({ Text(stringResource(R.string.name_error)) }) else null, colors = wellnessFieldColors())
                state.snapshot?.let { CaloriesCard(it.calories, label = stringResource(R.string.calorie_total)); MacroCards(it.protein, it.fat, it.carbs) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(dateLabel(java.time.Instant.ofEpochMilli(meal.timestamp).atZone(meal.zoneId?.let(java.time.ZoneId::of) ?: java.time.ZoneId.systemDefault()).toLocalDate()),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    WellnessTextField(state.time, vm::time, label = { Text(stringResource(R.string.time)) }, singleLine = true,
                        enabled = !state.busy, modifier = Modifier.width(120.dp),
                        isError = runCatching { java.time.LocalTime.parse(state.time) }.isFailure,
                        supportingText = if (runCatching { java.time.LocalTime.parse(state.time) }.isFailure) ({ Text(stringResource(R.string.time_error)) }) else null, colors = wellnessFieldColors())
                }
                Text(stringResource(R.string.meal_type), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealType.entries.forEach { type -> FilterChip(state.mealType == type, { vm.type(type) },
                        label = { Text(type.label()) }, enabled = !state.busy, colors=wellnessChipColors(),
                        border=FilterChipDefaults.filterChipBorder(!state.busy,state.mealType==type,borderColor=ControlOutline,selectedBorderColor=AccentBrown)) }
                }
                state.error?.let { error ->
                    TamaqCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.error_title), color = MaterialTheme.colorScheme.error)
                        Text(messageText(error))
                        if (state.meal == null) WellnessTextButton(onClick = { vm.load() }) { Text(stringResource(R.string.retry)) }
                    }
                }
                if (state.busy && meal.details.photoName != null) TamaqCard(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentBrown, trackColor = ChartTrack)
                    Text(stringResource(R.string.analysis_loading), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.analysis_wait), color = TextSecondary)
                    WellnessTextButton(onClick = vm::cancelAnalysis) { Text(stringResource(R.string.cancel_analysis)) }
                }
                meal.details.explanation?.let { Text(explanationText(it), style = MaterialTheme.typography.bodyMedium) }
                meal.details.question?.let { Text(stringResource(R.string.clarify, it)) }
                if (meal.details.photoName != null && meal.status != MealStatus.NeedsReview) {
                    val hasAccess = application.container.sessions.hasAnalysisAccess(application.container.owner)
                    if (!hasAccess) WellnessTextField(invite, { invite = it }, label = { Text(stringResource(R.string.invite)) },
                        visualTransformation = PasswordVisualTransformation(), enabled = !state.busy, modifier = Modifier.fillMaxWidth(), colors = wellnessFieldColors())
                    Text(stringResource(R.string.analysis_consent), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    PrimaryButton(stringResource(R.string.analyze), { vm.analyze(invite.trim()) }, enabled = !state.busy)
                }
                WellnessTextButton(onClick = { details = !details }) { Text(stringResource(R.string.photo_details)) }
                if (details) Text(stringResource(R.string.photo_policy), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                TamaqCard(Modifier.fillMaxWidth()) {
                    EatenPortionSelector(state.portion, vm::portion, !state.busy)
                    Text(stringResource(R.string.portion_local_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    val masses = meal.details.components.map { it.massGrams }
                    if (state.snapshot != null && masses.isNotEmpty() && masses.all { it != null })
                        Text(stringResource(R.string.mass_estimate, kotlin.math.round(meal.details.components.sumOf {
                            (it.massGrams ?: 0.0) * (it.consumedPortion ?: meal.details.portion) }).toInt()),
                            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Text(stringResource(R.string.ingredients), style = MaterialTheme.typography.titleLarge)
                state.components.forEachIndexed { index, c ->
                    key(index) {
                    var reveal by remember { mutableStateOf(false) }; LaunchedEffect(Unit) { reveal=true }
                    AnimatedVisibility(reveal,enter=fadeIn(tween(WellnessMotion.enter)) + expandVertically(tween(WellnessMotion.enter)),exit=fadeOut(tween(WellnessMotion.exit))) {
                    TamaqCard(Modifier.fillMaxWidth()) {
                        WellnessTextField(c.name, { vm.component(index, c.copy(name = it)) }, label = { Text(stringResource(R.string.component_number, index + 1)) },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth(), isError = c.name.isBlank() || c.name.length > 120,
                            supportingText = if (c.name.isBlank() || c.name.length > 120) ({ Text(stringResource(R.string.name_error)) }) else null, colors = wellnessFieldColors())
                        EatenPortionSelector(c.consumedPortion ?: state.portion, { vm.componentPortion(index, it) }, !state.busy,
                            title = stringResource(R.string.eaten_portion), semanticPrefix = stringResource(R.string.component_amount, index + 1), component = true)
                        NumericField(c.mass, { vm.component(index, c.copy(mass = it)) }, stringResource(R.string.mass), !state.busy, min = 0.001, max = 10000.0, optional = true,
                            fieldLabel = stringResource(R.string.estimated_mass_label))
                        Text(stringResource(R.string.mass_correction_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        NumericField(c.calories, { vm.component(index, c.copy(calories = it)) }, stringResource(R.string.calories_unit), !state.busy, max = 10000.0)
                        if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumericField(c.protein, { vm.component(index, c.copy(protein = it)) }, stringResource(R.string.protein), !state.busy)
                            NumericField(c.fat, { vm.component(index, c.copy(fat = it)) }, stringResource(R.string.fat), !state.busy)
                            NumericField(c.carbs, { vm.component(index, c.copy(carbs = it)) }, stringResource(R.string.carbs), !state.busy)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumericField(c.protein, { vm.component(index, c.copy(protein = it)) }, stringResource(R.string.protein), !state.busy, Modifier.weight(1f))
                            NumericField(c.fat, { vm.component(index, c.copy(fat = it)) }, stringResource(R.string.fat), !state.busy, Modifier.weight(1f))
                            NumericField(c.carbs, { vm.component(index, c.copy(carbs = it)) }, stringResource(R.string.carbs), !state.busy, Modifier.weight(1f))
                        }
                        WellnessTextButton(onClick = { vm.remove(index) }, enabled = !state.busy) { Text(stringResource(R.string.remove_component)) }
                    }
                }
                    } }
                WellnessOutlinedButton(onClick = vm::add, enabled = !state.busy && state.components.size < 20, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_component)) }
                if (vm.energyWarning()) Text(stringResource(R.string.energy_warning), color = TextSecondary)
                if (state.saving) Text(stringResource(R.string.saving), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.input_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (DemoMeals.available) WellnessTextButton(onClick = vm::demo, enabled = !state.busy) { Text(stringResource(R.string.demo_fill)) }
                WellnessTextButton(onClick = { deleteOriginal = false; delete = true }, enabled = !state.busy) { Text(stringResource(R.string.delete_draft_title)) }
                if (meal.originalUid != null) WellnessTextButton(onClick = { deleteOriginal = true; delete = true }, enabled = !state.busy) { Text(stringResource(R.string.meal_delete_title)) }
                }
            }
            if (state.meal == null && state.error != null) {
                Text(messageText(state.error!!))
                WellnessTextButton(onClick = { vm.load() }) { Text(stringResource(R.string.retry)) }
            }
        }
    }
    if (delete) ConfirmDialog(if (deleteOriginal) R.string.meal_delete_title else R.string.delete_draft_title,
        if (deleteOriginal) R.string.meal_delete_body else R.string.delete_draft_body, { delete = false },
        { delete = false; vm.delete(deleteOriginal) })
}

@Composable private fun NumericField(value: String, update: (String) -> Unit, label: String, enabled: Boolean,
    modifier: Modifier = Modifier, min: Double = 0.0, max: Double = 1000.0, optional: Boolean = false, fieldLabel: String = label) {
    val number = value.replace(',', '.').toDoubleOrNull()
    val valid = optional && value.isBlank() || number != null && number.isFinite() && number in min..max
    WellnessTextField(value, update, label = { Text(fieldLabel) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier.fillMaxWidth().semantics { contentDescription = label }, isError = !valid,
        supportingText = if (!valid) ({ Text(stringResource(R.string.number_range, decimal(min, 3), decimal(max, 3))) })
            else if (optional) ({ Text(stringResource(R.string.optional_field)) }) else null, colors = wellnessFieldColors())
}
