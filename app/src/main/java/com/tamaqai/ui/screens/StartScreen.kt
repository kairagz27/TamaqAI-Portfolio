package com.tamaqai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamaqai.R
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*

@Composable fun StartScreen(onGuest:()->Unit) {
    var route by remember {mutableStateOf("welcome")}
    var register by remember {mutableStateOf(false)}
    var languages by remember {mutableStateOf(false)}
    androidx.activity.compose.BackHandler(route!="welcome") {route="welcome"}
    WellnessBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("TamaqAI",style=MaterialTheme.typography.titleLarge)
                WellnessTextButton({languages=true}) {Text(stringResource(R.string.language))}
            }
            when(route) {
                "welcome"->{
                    CeramicIllustration(CeramicScene.Welcome,Modifier.fillMaxWidth().height(144.dp))
                    Text(stringResource(R.string.welcome_title),style=MaterialTheme.typography.headlineLarge)
                    Text(stringResource(R.string.welcome_body),color=TextSecondary)
                    WellnessCard(Modifier.fillMaxWidth(),emphasized=true) {
                        PrimaryButton(stringResource(R.string.account_login),{register=false;route="methods"})
                        WellnessOutlinedButton({register=true;route="methods"},Modifier.fillMaxWidth()) {Text(stringResource(R.string.account_register))}
                        WellnessTextButton(onGuest) {Text(stringResource(R.string.continue_guest))}
                    }
                    Text(stringResource(R.string.welcome_privacy),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                }
                "methods"->WellnessCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auth_method),style=MaterialTheme.typography.headlineSmall)
                    PrimaryButton(stringResource(R.string.email_method),{route="email"})
                    WellnessOutlinedButton({route="phone"},Modifier.fillMaxWidth()) {Text(stringResource(R.string.phone_method))}
                    WellnessTextButton({route="welcome"}) {Text(stringResource(R.string.back))}
                }
                "email"->AccountPanel(if(register) AccountForm.Register else AccountForm.Login,onBack={route="methods"},onPhone={route="phone"})
                "phone"->PhonePanel(onBack={route="methods"})
            }
        }
    }
    if(languages) WellnessDialog(onDismissRequest={languages=false},title={Text(stringResource(R.string.language))},
        text={Column {LanguageOptions()}},confirmButton={WellnessTextButton({languages=false}) {Text(stringResource(R.string.close))}})
}
