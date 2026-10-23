package com.tamaqai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.BuildConfig
import com.tamaqai.R
import com.tamaqai.TamaqApplication
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun ProfileScreen(vm: DailyViewModel, bottomSpace: androidx.compose.ui.unit.Dp = 20.dp,onPhone:()->Unit={},onGoals:()->Unit={},onQuestionnaire:()->Unit={},onPlan:()->Unit={}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val standalone=(LocalContext.current.applicationContext as TamaqApplication).container.standalone
    var delete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.profile), style = MaterialTheme.typography.headlineLarge)
        AccountPanel(onPhone=onPhone)
        Text(stringResource(R.string.app_settings),style=MaterialTheme.typography.titleLarge)
        if (state.loading) WellnessLoadingIndicator() else WellnessOutlinedButton(onGoals,Modifier.fillMaxWidth()) {Text(stringResource(R.string.goals_title))}
        WellnessOutlinedButton(onQuestionnaire,Modifier.fillMaxWidth()) {Text(stringResource(R.string.questionnaire))}
        WellnessOutlinedButton(onPlan,Modifier.fillMaxWidth()) {Text(stringResource(R.string.meal_plan))}
        TamaqCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if(standalone!=null) R.string.standalone_local_only else R.string.privacy_body), color = TextSecondary)
            if(standalone==null) {
                Text(stringResource(R.string.photo_policy), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                WellnessOutlinedButton(onClick = { delete = true }, enabled = !state.busy) { Text(stringResource(R.string.delete_local_data)) }
            }
        }
        TamaqCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.about_app),style=MaterialTheme.typography.titleLarge)
            Text("TamaqAI · ${BuildConfig.VERSION_NAME}",color=TextSecondary)
            Text(stringResource(if(standalone!=null) R.string.standalone_local_only else R.string.account_local_diary_hint),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        }
        if(standalone!=null) StandaloneResetCard()
        if (BuildConfig.DEBUG && standalone==null) {
            var diagnostics by remember { mutableStateOf(false) }
            WellnessTextButton(onClick = { diagnostics = !diagnostics }) { Text(stringResource(R.string.debug_title)) }
            if (diagnostics) Text(stringResource(R.string.debug_backend,
                BuildConfig.BACKEND_URL.ifBlank { stringResource(R.string.debug_not_configured) }), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(bottomSpace))
    }
    if (delete) ConfirmDialog(R.string.delete_data_title, R.string.delete_data_body, { delete = false }, { delete = false; vm.clearData() })
}

@Composable fun SettingsScreen(vm:DailyViewModel,onGoals:()->Unit={}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val standalone=(LocalContext.current.applicationContext as TamaqApplication).container.standalone
    var delete by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings),style=MaterialTheme.typography.headlineLarge)
        WellnessOutlinedButton(onGoals,Modifier.fillMaxWidth()) {Text(stringResource(R.string.goals_title))}
        TamaqCard {
            Text(stringResource(R.string.privacy_title),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(if(standalone!=null) R.string.standalone_local_only else R.string.privacy_body),color=TextSecondary)
            if(standalone==null) WellnessOutlinedButton({delete=true},enabled=!state.busy) {Text(stringResource(R.string.delete_local_data))}
        }
        if(standalone!=null) StandaloneResetCard()
    }
    if(delete) ConfirmDialog(R.string.delete_data_title,R.string.delete_data_body,{delete=false},{delete=false;vm.clearData()})
}

/** Reset is limited to the standalone package; appearance and locale are preserved. */
@Composable private fun StandaloneResetCard() {
    val controller=(LocalContext.current.applicationContext as TamaqApplication).container.standalone ?: return
    val scope=rememberCoroutineScope()
    var confirm by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var failed by remember {mutableStateOf(false)}
    TamaqCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.standalone_reset_title),style=MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.standalone_reset_body),color=TextSecondary,style=MaterialTheme.typography.bodySmall)
        WellnessOutlinedButton({confirm=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.standalone_reset))}
        if(busy) WellnessLoadingIndicator()
        if(failed) Text(stringResource(R.string.standalone_reset_error),color=MaterialTheme.colorScheme.error)
    }
    if(confirm) WellnessDialog(onDismissRequest={if(!busy) confirm=false},
        title={Text(stringResource(R.string.standalone_reset_title))},
        text={Text(stringResource(R.string.standalone_reset_body))},
        confirmButton={WellnessTextButton({
            if(!busy) {
                busy=true;failed=false
                scope.launch {
                    try {controller.reset();confirm=false}
                    catch(cancel:CancellationException) {throw cancel}
                    catch(_:Exception) {failed=true;confirm=false}
                    finally {busy=false}
                }
            }
        },enabled=!busy) {Text(stringResource(R.string.standalone_reset))}},
        dismissButton={WellnessTextButton({confirm=false},enabled=!busy) {Text(stringResource(R.string.cancel))}})
}
