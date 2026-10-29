package com.tamaqai.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamaqai.R
import com.tamaqai.domain.model.PlanningWorkspace
import com.tamaqai.ui.appLocale
import com.tamaqai.ui.theme.TextSecondary

/** The diary preview reads the same local plan shown in the period editor. */
@Composable fun PlanningHomePreview(workspace:PlanningWorkspace,date:String,onOpen:()->Unit) {
    val entries=workspace.entries.filter {it.date==date}
    val language=appLocale().language
    WellnessCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.meal_plan),style=MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.mp_home_summary,workspace.dayCount,entries.size),color=TextSecondary)
        entries.take(2).forEach {entry ->
            Text(entry.recipe.displayName(language),style=MaterialTheme.typography.bodyMedium)
        }
        WellnessTextButton(onOpen,Modifier.heightIn(min=48.dp)) {Text(stringResource(R.string.mp_open_period))}
    }
}
