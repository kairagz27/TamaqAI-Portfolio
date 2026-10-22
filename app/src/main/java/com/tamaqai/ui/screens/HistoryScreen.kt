package com.tamaqai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.domain.model.*
import com.tamaqai.domain.usecase.DiaryTotals
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun HistoryScreen(vm: DailyViewModel, photos: PhotoStore, onEdit: (String)->Unit,
    onDay: (LocalDate)->Unit, bottomSpace: Dp = 20.dp) {
    val state by vm.state.collectAsStateWithLifecycle()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var dayText by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var repeat by remember { mutableStateOf<Meal?>(null) }
    val month = YearMonth.parse(monthText)
    val day = dayText?.let(LocalDate::parse)
    fun date(meal: Meal) = Instant.ofEpochMilli(meal.timestamp).atZone(meal.zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()).toLocalDate()
    val confirmed = state.meals.filter { it.status == MealStatus.Confirmed }
    val counts = confirmed.groupingBy(::date).eachCount()
    val filtered = confirmed.filter {
        val d = date(it)
        (query.isNotBlank() || YearMonth.from(d) == month && (day == null || d == day)) &&
            it.nutrition.name.contains(query.trim(), ignoreCase=true)
    }
    val incompleteCount = state.incompleteCatalogMeals.count {
        val recordDate = DiaryTotals.dateOf(it)
        (query.isNotBlank() || YearMonth.from(recordDate) == month && (day == null || recordDate == day)) &&
            it.nutrition.name.contains(query.trim(), ignoreCase = true)
    }
    // Keep calendar cells >=48dp at 360dp; align its heading/search with other screens.
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding=PaddingValues(12.dp,20.dp,12.dp,bottomSpace),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.history),Modifier.padding(horizontal=8.dp), style=MaterialTheme.typography.headlineLarge) }
        item { WellnessCard(Modifier.fillMaxWidth(),contentPadding=0.dp,contentSpacing=0.dp) {
            Row(Modifier.padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton({monthText=month.minusMonths(1).toString();dayText=null}) {Icon(Icons.Outlined.ChevronLeft,stringResource(R.string.previous_month))}
                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",com.tamaqai.ui.appLocale())), Modifier.weight(1f), style=MaterialTheme.typography.titleMedium)
                IconButton({monthText=month.plusMonths(1).toString();dayText=null}) {Icon(Icons.Outlined.ChevronRight,stringResource(R.string.next_month))}
            }
            Row(Modifier.fillMaxWidth()) { java.time.DayOfWeek.entries.map {it.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE,com.tamaqai.ui.appLocale())}.forEach { Text(it,Modifier.weight(1f),color=TextSecondary,textAlign=androidx.compose.ui.text.style.TextAlign.Center) } }
            val offset=month.atDay(1).dayOfWeek.value-1
            (0 until (offset+month.lengthOfMonth()+6)/7).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { column ->
                        val number=row*7+column-offset+1
                        if(number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(52.dp))
                        else {
                            val value=month.atDay(number); val count=counts[value] ?: 0
                            val description=stringResource(R.string.history_day_accessibility,dateLabel(value),count)
                            Surface(onClick={dayText=value.toString()},Modifier.weight(1f).heightIn(min=52.dp).semantics {contentDescription=description;selected=day==value},
                                shape=RoundedCornerShape(14.dp),color=if(day==value) SelectedSurface else androidx.compose.ui.graphics.Color.Transparent) {
                                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                    Text(number.toString(),color=if(day==value) AccentBrown else TextPrimary,style=MaterialTheme.typography.bodyMedium)
                                    if(count>0) Text(count.toString(),color=AccentBrown,style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.history_calendar_hint),Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
            if(day!=null) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                WellnessTextButton({dayText=null},Modifier.weight(1f)) {Text(stringResource(R.string.whole_month))}
                WellnessTextButton({onDay(day)},Modifier.weight(1f)) {Text(stringResource(R.string.open_day))}
            }
            Spacer(Modifier.height(12.dp))
        } }
        item { WellnessTextField(query,{query=it},label={Text(stringResource(R.string.history_search))},singleLine=true,
            leadingIcon={Icon(Icons.Outlined.Search,null)},modifier=Modifier.fillMaxWidth(), colors = wellnessFieldColors()) }
        if (incompleteCount > 0) item { IncompleteNutritionNotice(incompleteCount) }
        if(state.loading) item { WellnessLoadingIndicator() }
        if(!state.loading && filtered.isEmpty()) item { EmptyState(stringResource(R.string.history_empty_title),stringResource(R.string.history_empty_body),scene=CeramicScene.History) }
        items(filtered,key=Meal::uid) { meal ->
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(dateLabel(date(meal)),color=TextSecondary,style=MaterialTheme.typography.labelMedium)
                MealCard(meal,photos,{onEdit(meal.uid)},onRepeat={repeat=meal})
            }
        }
    }
    repeat?.let { meal -> CalendarDialog(day ?: LocalDate.now(),{repeat=null}) { target -> vm.repeat(meal.uid,target,onEdit);repeat=null } }
}
