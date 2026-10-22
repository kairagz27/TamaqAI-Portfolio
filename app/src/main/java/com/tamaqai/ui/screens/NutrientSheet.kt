package com.tamaqai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamaqai.R
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NutrientSheet(index:Int,date:LocalDate,eaten:Int,goal:Int?,onDismiss:()->Unit,onGoals:()->Unit,
    incompleteNutritionCount:Int=0) {
    val titles=listOf(R.string.calorie_goal_label,R.string.protein,R.string.fat,R.string.carbs)
    val explanations=listOf(R.string.nutrient_calories_info,R.string.nutrient_protein_info,R.string.nutrient_fat_info,R.string.nutrient_carbs_info)
    val unit=stringResource(if(index==0) R.string.calories_unit else R.string.unit_grams)
    WellnessGlassBottomSheet(title=stringResource(titles[index]),onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(dateLabel(date),color=TextSecondary)
            IncompleteNutritionNotice(incompleteNutritionCount)
            Text(stringResource(R.string.nutrient_eaten,eaten)+" "+unit,style=MaterialTheme.typography.titleLarge.copy(fontFamily=BodyFont))
            if(goal!=null && goal>0) {
                Text(stringResource(R.string.nutrient_target,goal)+" "+unit)
                Text(stringResource(if(eaten>goal) R.string.nutrient_exceeded else R.string.nutrient_remaining,kotlin.math.abs(goal-eaten))+" "+unit,color=if(eaten>goal) WarningColor else AccentBrown)
                LinearProgressIndicator(progress={(eaten.toFloat()/goal).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),color=AccentBrown,trackColor=ChartTrack)
                Text(stringResource(R.string.progress_ring_label,(eaten.toDouble()/goal*100).toInt()),color=TextSecondary)
            } else Text(stringResource(R.string.goal_not_set),color=TextSecondary)
            Text(stringResource(explanations[index]),style=MaterialTheme.typography.bodyLarge)
            PrimaryButton(stringResource(R.string.setup_goals),onGoals)
            WellnessTextButton(LocalWellnessSheetClose.current ?: onDismiss) {Text(stringResource(R.string.cancel))}
            Spacer(Modifier.height(24.dp))
        }
    }
}
