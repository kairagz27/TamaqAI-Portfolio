package com.tamaqai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamaqai.R
import com.tamaqai.domain.model.MealPlan
import com.tamaqai.ui.theme.*

@Composable fun HomeGreeting(name: String?, hour: Int, onMenu: () -> Unit, history: Boolean = false,
    menuFocusRequester: FocusRequester? = null) {
    val greeting = stringResource(when(hour) {
        in 5..11 -> R.string.home_morning
        in 12..17 -> R.string.home_afternoon
        in 18..22 -> R.string.home_evening
        else -> R.string.home_night
    })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconButton(onMenu, Modifier.size(48.dp).matteSurface(shape = CircleShape)
            .then(menuFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)) {
            Icon(Icons.Outlined.Menu, stringResource(R.string.menu), tint = TextPrimary, modifier = Modifier.size(23.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(R.string.app_name), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Text(if(history) stringResource(R.string.history) else name?.takeIf {it.isNotBlank()}?.let {
                stringResource(R.string.home_named_greeting, greeting, it.trim())
            } ?: greeting, Modifier.testTag("home-greeting"), style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The camera and manual path remain distinct; other existing sources stay in the add menu. */
@Composable fun HomeFoodActions(onScan: () -> Unit, onManual: () -> Unit, onOther: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if(pressed) .985f else 1f,
        androidx.compose.animation.core.tween(WellnessMotion.press), label = "scan food press")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(onScan, Modifier.fillMaxWidth().testTag("scan-food").heightIn(min = 56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }, interactionSource = interactions,
            shape = RoundedCornerShape(WellnessSpace.macroCard), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            colors = ButtonDefaults.buttonColors()) {
            Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(23.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.home_scan_food), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WellnessTextButton(onManual, Modifier.weight(1f).testTag("manual-food")) {
                Icon(Icons.Outlined.EditNote, null, Modifier.size(21.dp))
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.manual), style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onOther, Modifier.size(48.dp)) {
                Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.add), tint = TextSecondary)
            }
        }
    }
}

/** Uses the existing owner-scoped plan state. Rendering this card never generates a plan. */
@Composable fun HomePlanCard(plan: MealPlan?, pending: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("home-plan").matteSurface(shape = RoundedCornerShape(WellnessSpace.previewCard))
        .clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlanPlate(Modifier.size(58.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.meal_plan), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            val status = when {
                pending -> stringResource(R.string.home_plan_pending)
                plan == null -> stringResource(R.string.home_plan_empty)
                plan.meals.isEmpty() -> stringResource(R.string.home_plan_draft)
                plan.dirty -> stringResource(R.string.home_plan_local, plan.meals.size)
                else -> stringResource(R.string.home_plan_synced, plan.meals.size)
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if(plan?.demo == true) Text("DEMO", style = MaterialTheme.typography.labelSmall, color = WarningColor)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = TextSecondary)
    }
}

/** Small native tableware drawing; it is never presented as a user's food photograph. */
@Composable private fun PlanPlate(modifier: Modifier = Modifier) {
    val plate = CardSurface
    val rim = BorderSoft
    val leaf = CarbColor
    val food = FatColor
    val blue = ProteinColor
    Canvas(modifier.clearAndSetSemantics {}) {
        val radius = size.minDimension * .43f
        drawCircle(plate, radius)
        drawCircle(rim, radius * .76f, style = Stroke(1.dp.toPx()))
        drawOval(leaf, topLeft = Offset(size.width * .29f, size.height * .28f),
            size = androidx.compose.ui.geometry.Size(size.width * .17f, size.height * .32f))
        drawOval(leaf.copy(alpha = .8f), topLeft = Offset(size.width * .44f, size.height * .29f),
            size = androidx.compose.ui.geometry.Size(size.width * .11f, size.height * .25f))
        drawCircle(food, radius * .20f, Offset(size.width * .59f, size.height * .60f))
        drawCircle(blue, radius * .13f, Offset(size.width * .40f, size.height * .66f))
    }
}

@Composable fun HomeEmptyDiary(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CeramicIllustration(CeramicScene.Diary, Modifier.size(76.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.empty_diary_title), style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary)
                Text(stringResource(R.string.home_empty_hint), style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary)
            }
        }
        WellnessTextButton(onAdd, Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.empty_first_meal), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
        }
    }
}
