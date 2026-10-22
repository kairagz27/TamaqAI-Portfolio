package com.tamaqai.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.tamaqai.R
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.domain.model.*
import com.tamaqai.ui.screens.label
import com.tamaqai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

object Space { val page = WellnessSpace.page; val gap = WellnessSpace.gap; val small = WellnessSpace.small; val corner = WellnessSpace.card }

@Composable fun TamaqCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    WellnessCard(modifier, content = content)
}

@Composable fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interactions=remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if(pressed) .985f else 1f,androidx.compose.animation.core.tween(WellnessMotion.press),label="button press")
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer {scaleX=scale;scaleY=scale}, enabled = enabled,interactionSource=interactions,
        shape = RoundedCornerShape(WellnessSpace.control), contentPadding = PaddingValues(16.dp),
        colors = ButtonDefaults.buttonColors(disabledContainerColor = DisabledSurface, disabledContentColor = TextSecondary)) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable fun EmptyState(title: String, description: String, modifier: Modifier = Modifier,
    button: String? = null, onClick: () -> Unit = {}, scene: CeramicScene = CeramicScene.Diary) {
    WellnessCard(modifier.fillMaxWidth()) {
        CeramicIllustration(scene, Modifier.fillMaxWidth().height(104.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color = TextSecondary)
        button?.let { PrimaryButton(it, onClick) }
    }
}

@Composable fun CaloriesCard(calories: Int, goal: Int? = null, onGoals: (() -> Unit)? = null, label: String? = null,onDetails:(()->Unit)?=null) {
    val target = goal?.takeIf { it > 0 }
    val fontScale = LocalDensity.current.fontScale
    WellnessCard(Modifier.fillMaxWidth().then(if(onDetails!=null) Modifier.clickable(role=Role.Button,onClick=onDetails) else Modifier),
        contentPadding = WellnessSpace.gap, contentSpacing = WellnessSpace.compact) {
        if(target == null && onGoals == null && onDetails == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CalorieBalanceRing(calories, null, label) }
        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = fontScale > 1.15f && maxWidth < 350.dp
            if (stacked) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CalorieBalanceRing(calories, target, label)
                    CalorieGoalSummary(calories, target, onGoals, onDetails, Modifier.fillMaxWidth(), horizontal = true)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CalorieBalanceRing(calories, target, label)
                    CalorieGoalSummary(calories, target, onGoals, onDetails, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Only the visual arc animates; the diary amount is always the exact current sum. */
@Composable private fun CalorieBalanceRing(calories: Int, goal: Int?, label: String?) {
    val progress = goal?.let { calories.toFloat() / it } ?: 0f
    val animated by androidx.compose.animation.core.animateFloatAsState(progress.coerceIn(0f, 1f),
        androidx.compose.animation.core.tween(WellnessMotion.indicator), label = "calorie arc")
    val track = SecondarySurface
    val accent = if(goal != null && calories > goal) WarningColor else FatColor
    val locale = com.tamaqai.ui.appLocale()
    val formatted = remember(calories, locale) { java.text.NumberFormat.getIntegerInstance(locale).format(calories) }
    val digits = formatted.length.coerceAtLeast(1)
    val fontScale = LocalDensity.current.fontScale
    // Even unusually large daily totals remain fully visible at 130% font size.
    val numberSize = minOf(36f, 132f / (digits * .64f * fontScale)).sp
    Box(Modifier.size(WellnessSpace.calorieRing), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().testTag("calorie-progress").semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
        }) {
            val stroke = WellnessSpace.calorieStroke.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc,
                style = Stroke(stroke, cap = StrokeCap.Round))
            if(animated > 0f) drawArc(accent, -90f, 360f * animated, false, Offset(inset, inset), arc,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(22.dp), tint = FatColor)
            Text(formatted, Modifier.testTag("calorie-value"), style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = BodyFont, fontSize = numberSize, lineHeight = numberSize * 1.2f),
                color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label ?: stringResource(R.string.eaten), style = MaterialTheme.typography.labelMedium,
                color = TextSecondary)
            Text(stringResource(R.string.calories_unit), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable private fun CalorieGoalSummary(calories: Int, goal: Int?, onGoals: (() -> Unit)?,
    onDetails: (() -> Unit)?, modifier: Modifier, horizontal: Boolean = false) {
    Column(modifier, horizontalAlignment = if(horizontal) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(stringResource(if(goal != null) R.string.home_daily_goal else R.string.goal_not_set),
            Modifier.testTag("calorie-goal"), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        if(goal != null) {
            Text(stringResource(R.string.calories_value, goal), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(stringResource(if(calories > goal) R.string.exceeded else R.string.remaining,
                kotlin.math.abs(goal - calories)), Modifier.testTag("calorie-balance"),
                style = MaterialTheme.typography.bodyMedium,
                color = if(calories > goal) WarningColor else TextPrimary)
        } else if(onGoals != null) {
            WellnessTextButton(onGoals, Modifier.testTag("calorie-balance")) {
                Text(stringResource(R.string.setup_goals), style = MaterialTheme.typography.labelLarge)
            }
        }
        if(onDetails != null) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.home_details), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = TextSecondary)
        }
    }
}

@Composable fun MacroCards(protein: Int, fat: Int, carbs: Int, goals: DailyGoals = DailyGoals(),onDetails:((Int)->Unit)?=null) {
    val labels = listOf(R.string.protein, R.string.fat, R.string.carbs)
    val values = listOf(protein, fat, carbs)
    val targets = listOf(goals.protein, goals.fat, goals.carbs).map {it?.takeIf {target -> target > 0}}
    val indicators = listOf(ProteinColor, FatColor, CarbColor)
    val icons = listOf(Icons.Outlined.EggAlt, Icons.Outlined.WaterDrop, Icons.Outlined.Grass)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val color = indicators[index]
            val animated by androidx.compose.animation.core.animateFloatAsState(targets[index]?.let {(values[index].toFloat()/it).coerceIn(0f,1f)} ?: 0f,
                androidx.compose.animation.core.tween(WellnessMotion.indicator),label="macro progress")
            Column(Modifier.weight(1f).matteSurface(shape = RoundedCornerShape(WellnessSpace.macroCard))
                .then(if(onDetails!=null) Modifier.clickable(role=Role.Button) {onDetails(index+1)} else Modifier)
                .padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Icon(icons[index], null, Modifier.size(22.dp), tint = color)
                        if(onDetails != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = TextSecondary)
                    }
                    Text(stringResource(label), Modifier.heightIn(min = 37.dp), style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary, maxLines = 2)
                    Text(stringResource(R.string.grams, values[index]), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    targets[index]?.let { target ->
                        Text(stringResource(R.string.macro_goal, target), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        LinearProgressIndicator(progress = { animated },
                            color = color, trackColor = ChartTrack,
                            modifier = Modifier.fillMaxWidth().height(4.dp), drawStopIndicator = {})
                    } ?: Text(stringResource(R.string.home_macro_no_goal), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

fun dateLabel(date: LocalDate) = date.format(DateTimeFormatter.ofPattern(
    if (date.year == LocalDate.now().year) "d MMMM, EEEE" else "d MMMM yyyy, EEEE", com.tamaqai.ui.appLocale()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CalendarDialog(selected: LocalDate, onDismiss: () -> Unit, onDate: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    WellnessDialog(onDismissRequest = onDismiss, contentPadding = 0.dp,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false), confirmButton = {
        WellnessTextButton(onClick = { state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; onDismiss() }) {
            Text(stringResource(R.string.confirm))
        }
    }, dismissButton = { WellnessTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = { DatePicker(state, colors = DatePickerDefaults.colors(containerColor = Color.Transparent)) })
}

@Composable fun DateSelector(date: LocalDate, onDate: (LocalDate) -> Unit) {
    var calendar by remember { mutableStateOf(false) }
    val openCalendar = stringResource(R.string.calendar)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDate(date.minusDays(1)) }) { Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.previous_day)) }
            WellnessTextButton(onClick = { calendar = true }, modifier = Modifier.weight(1f)
                .semantics { contentDescription = dateLabel(date) + ". " + openCalendar }) {
                Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", com.tamaqai.ui.appLocale())),
                    Modifier.weight(1f, fill = false), color = TextPrimary, style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.ExpandMore, stringResource(R.string.calendar), Modifier.size(18.dp))
            }
            IconButton(onClick = { onDate(date.plusDays(1)) }) { Icon(Icons.Outlined.ChevronRight, stringResource(R.string.next_day)) }
    }
    if (calendar) CalendarDialog(date, { calendar = false }, onDate)
}

@Composable fun MealPhoto(photos: PhotoStore, name: String?, modifier: Modifier = Modifier) {
    val file = photos.availableFile(name)
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file?.absolutePath, file?.lastModified()) {
        value = file?.let { source -> withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull()
        } }
        file?.let { delay((PhotoStore.RETENTION_MS - (System.currentTimeMillis() - it.lastModified())).coerceAtLeast(0)); value = null }
    }
    if (bitmap != null) Image(bitmap!!, stringResource(R.string.photo), modifier.clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
    else Box(modifier.clip(RoundedCornerShape(20.dp)).background(SecondarySurface), contentAlignment = Alignment.Center) {
        Box(Modifier.size(54.dp).border(1.dp, BorderSoft, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Restaurant, stringResource(R.string.no_photo), tint = TextSecondary, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable fun MealCard(meal: Meal, photos: PhotoStore, onEdit: () -> Unit,
    onFavorite: (() -> Unit)? = null, onRepeat: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    WellnessCard(Modifier.fillMaxWidth(), contentPadding = 16.dp, contentSpacing = 8.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MealPhoto(photos, meal.details.photoName, Modifier.size(82.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(meal.nutrition.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val time = Instant.ofEpochMilli(meal.timestamp).atZone(meal.zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                Text("$time · ${meal.mealType.label()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text(if(meal.details.catalogPendingManual) stringResource(R.string.catalog_nutrition_missing)
                    else stringResource(R.string.calories_value, meal.nutrition.calories), fontWeight = FontWeight.SemiBold)
            }
        }
        if(!meal.details.catalogPendingManual) MacroSummary(meal.nutrition)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(meal.source.label(), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            if (meal.status != MealStatus.Confirmed) Text(meal.status.label(), style = MaterialTheme.typography.labelSmall,
                color = if (meal.status == MealStatus.Failed) ErrorColor else WarningColor)
        }
        if (onFavorite != null || onRepeat != null || onDelete != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            onFavorite?.let { IconButton(onClick = it) { Icon(Icons.Outlined.BookmarkAdd, stringResource(R.string.add_favorite), tint = AccentBrown) } }
            onRepeat?.let { IconButton(onClick = it) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.repeat_meal), tint = AccentBrown) } }
            onDelete?.let { IconButton(onClick = it) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), tint = TextSecondary) } }
        }
    }
}

@Composable fun MacroSummary(n: Nutrition) {
    Text("${stringResource(R.string.protein)} ${stringResource(R.string.grams, n.protein)} · " +
        "${stringResource(R.string.fat)} ${stringResource(R.string.grams, n.fat)} · ${stringResource(R.string.carbs)} ${stringResource(R.string.grams, n.carbs)}",
        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
}

@Composable fun ConfirmDialog(title: Int, body: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    WellnessDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) }, text = { Text(stringResource(body)) },
        confirmButton = { WellnessTextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = ErrorColor) } },
        dismissButton = { WellnessTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
