package com.tamaqai.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.domain.model.WeightEntry
import com.tamaqai.domain.usecase.ProgressSummary
import com.tamaqai.domain.usecase.DiaryTotals
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

fun decimal(value: Double, precision: Int = 1) = java.text.NumberFormat.getNumberInstance(com.tamaqai.ui.appLocale()).apply { maximumFractionDigits = precision }.format(value)

@Composable fun ProgressScreen(vm: DailyViewModel, bottomSpace: androidx.compose.ui.unit.Dp = 20.dp) {
    val state by vm.state.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableIntStateOf(30) }
    var edit by remember { mutableStateOf<WeightEntry?>(null) }
    var add by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<WeightEntry?>(null) }
    val end = LocalDate.now()
    val firstMealDay = state.meals.filter { DiaryTotals.isCounted(it) || it.details.catalogPendingManual }
        .minOfOrNull { DiaryTotals.dateOf(it) }
    val start = if (period == 0) listOfNotNull(firstMealDay, state.weights.firstOrNull()?.date, end).minOrNull()!!
        else end.minusDays(period - 1L)
    val weights = state.weights.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
    val days = ProgressSummary.observedDays(state.meals, start, end)
    val incompleteCount = state.incompleteCatalogMeals.count {
        val date = DiaryTotals.dateOf(it)
        !date.isBefore(start) && !date.isAfter(end)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start=20.dp, top=20.dp, end=20.dp, bottom=bottomSpace), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.progress), style = MaterialTheme.typography.headlineLarge) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.period_scroll_hint), color=TextSecondary, style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to R.string.days_7, 30 to R.string.days_30, 90 to R.string.days_90, 0 to R.string.period_all).forEach { (length, label) ->
                    FilterChip(selected = period == length, onClick = { period = length }, label = { Text(stringResource(label)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = wellnessChipColors(),
                        border = FilterChipDefaults.filterChipBorder(true, period == length, borderColor = ControlOutline, selectedBorderColor = AccentBrown))
                }
            }
            }
        }
        if (state.loading) item { WellnessLoadingIndicator() }
        item {
            TamaqCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.weight_title), style = MaterialTheme.typography.titleLarge)
                state.weights.lastOrNull { !it.date.isAfter(end) }?.let {
                    Text(stringResource(R.string.weight_value, decimal(it.kilograms)), style = MaterialTheme.typography.displaySmall)
                    Text(dateLabel(it.date), color = TextSecondary)
                }
                PrimaryButton(stringResource(R.string.add_weight), { add = true }, enabled = !state.loading && !state.busy)
            }
        }
        if (weights.isEmpty()) item { EmptyState(stringResource(R.string.weight_empty_title), stringResource(R.string.weight_empty_body)) }
        else item {
            TamaqCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.weight_chart), style = MaterialTheme.typography.titleLarge)
                DataChart(weights.map { it.date.toEpochDay() to it.kilograms }, start, end, false)
                if (weights.size == 1) Text(stringResource(R.string.single_weight_hint), color=TextSecondary, style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.weight_chart_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        item {
            TamaqCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.calories_chart), style = MaterialTheme.typography.titleLarge)
                IncompleteNutritionNotice(incompleteCount)
                if (days.isEmpty()) Text(stringResource(R.string.calories_empty), color = TextSecondary)
                else {
                    DataChart(days.map { it.date.toEpochDay() to it.calories.toDouble() }, start, end, true)
                    Text(stringResource(R.string.calories_average), color = TextSecondary)
                    Text(stringResource(R.string.calories_value, days.map { it.calories }.average().roundToInt()), style = MaterialTheme.typography.headlineMedium.copy(fontFamily=BodyFont))
                    Text(stringResource(R.string.protein_average), color = TextSecondary)
                    Text(stringResource(R.string.protein_average_value, decimal(days.map { it.protein }.average())), style = MaterialTheme.typography.headlineSmall.copy(fontFamily=BodyFont))
                    Text(stringResource(R.string.average_basis, days.size), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
        items(weights.asReversed(), key = WeightEntry::id) { entry ->
            TamaqCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.weight_value, decimal(entry.kilograms)), style = MaterialTheme.typography.titleLarge.copy(fontFamily=BodyFont))
                        Text(dateLabel(entry.date), color = TextSecondary)
                    }
                    IconButton(onClick = { edit = entry }, enabled = !state.busy) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit_weight)) }
                    IconButton(onClick = { delete = entry }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                }
            }
        }
        if (days.isNotEmpty()) {
            item { Text(stringResource(R.string.observed_days), style = MaterialTheme.typography.titleLarge) }
            items(days, key = { it.date }) { day ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${dateLabel(day.date)} · ${stringResource(R.string.calories_value, day.calories)} · ${stringResource(R.string.protein)} ${stringResource(R.string.grams, day.protein)}",
                        style = MaterialTheme.typography.bodySmall)
                    if (day.incompleteNutritionCount > 0) Text(stringResource(R.string.catalog_totals_partial_day),
                        style = MaterialTheme.typography.labelSmall, color = WarningColor)
                }
            }
        }
    }
    if (add || edit != null) WeightDialog(edit, { add = false; edit = null }) { vm.saveWeight(it); add = false; edit = null }
    delete?.let { entry -> ConfirmDialog(R.string.weight_delete_title, R.string.weight_delete_body,
        { delete = null }, { vm.deleteWeight(entry.id); delete = null }) }
}

@Composable private fun DataChart(points: List<Pair<Long, Double>>, start: LocalDate, end: LocalDate, bars: Boolean) {
    val min = if (bars) 0.0 else points.minOf { it.second } - 1
    val max = points.maxOf { it.second }.coerceAtLeast(min + 1)
    val description = stringResource(R.string.chart_range, decimal(min), decimal(max))
    Text(description, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    Column(Modifier.width(54.dp).height(170.dp).padding(vertical=4.dp),verticalArrangement=Arrangement.SpaceBetween) {
        listOf(max,(min+max)/2,min).forEach { value -> Text(if(bars) value.roundToInt().toString() else decimal(value),style=MaterialTheme.typography.labelSmall,color=TextSecondary) }
    }
    val grid=BorderSoft
    val plot=AccentBrown
    Canvas(Modifier.weight(1f).height(170.dp).semantics { contentDescription = description }) {
        val pad = 10.dp.toPx()
        val plotHeight = size.height - 2 * pad
        fun point(entry: Pair<Long, Double>) = Offset(pad + (entry.first - start.toEpochDay()).toFloat() /
            (end.toEpochDay() - start.toEpochDay()).coerceAtLeast(1) * (size.width - 2 * pad),
            size.height - pad - ((entry.second - min) / (max - min)).toFloat() * plotHeight)
        repeat(4) { i -> val y = pad + i * plotHeight / 3; drawLine(grid, Offset(pad, y), Offset(size.width - pad, y), .7.dp.toPx()) }
        if (!bars) points.zipWithNext().filter { (a,b) -> b.first-a.first==1L }
            .forEach { (a, b) -> drawLine(plot, point(a), point(b), 1.8.dp.toPx()) }
        points.forEach { entry ->
            val p = point(entry)
            if (bars) drawLine(plot, Offset(p.x, size.height - pad), p, (size.width / 45).coerceIn(3.dp.toPx(), 12.dp.toPx()))
            else drawCircle(plot, 4.dp.toPx(), p)
        }
    }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val formatter = DateTimeFormatter.ofPattern(if (start.year != end.year) "dd.MM.yy" else "dd.MM")
        Text(start.format(formatter), style = MaterialTheme.typography.labelSmall)
        Text(end.format(formatter), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun WeightDialog(existing: WeightEntry?, dismiss: () -> Unit, save: (WeightEntry) -> Unit) {
    var weight by remember { mutableStateOf(existing?.kilograms?.toString() ?: "") }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var calendar by remember { mutableStateOf(false) }
    val number = weight.replace(',', '.').toDoubleOrNull()
    val valid = number != null && number.isFinite() && number in 1.0..500.0
    val dateValid = !date.isAfter(LocalDate.now())
    WellnessDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (existing == null) R.string.add_weight else R.string.edit_weight)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WellnessTextButton(onClick = { calendar = true }) { Text(dateLabel(date)) }
            if (!dateValid) Text(stringResource(R.string.weight_date_error), color = MaterialTheme.colorScheme.error)
            WellnessTextField(weight, { weight = it }, label = { Text(stringResource(R.string.weight_kg)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = weight.isNotBlank() && !valid,
                supportingText = if (weight.isNotBlank() && !valid) ({ Text(stringResource(R.string.weight_error)) }) else null, colors = wellnessFieldColors())
        } }, confirmButton = { WellnessTextButton(onClick = { save(WeightEntry(existing?.id ?: java.util.UUID.randomUUID().toString(), date, number!!)) }, enabled = valid && dateValid) { Text(stringResource(R.string.save)) } },
        dismissButton = { WellnessTextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
    if (calendar) CalendarDialog(date, { calendar = false }, { date = it })
}
