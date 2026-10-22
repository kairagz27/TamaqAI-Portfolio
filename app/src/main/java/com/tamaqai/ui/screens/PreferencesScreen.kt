package com.tamaqai.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.BuildConfig
import com.tamaqai.R
import com.tamaqai.data.local.AppPreferences
import com.tamaqai.data.local.ThemeMode
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*

@Composable fun LanguageOptions() {
    val language=androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    listOf("ru" to R.string.language_ru,"kk" to R.string.language_kk,"en" to R.string.language_en).forEach {(tag,label) ->
        WellnessOutlinedButton({AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))},Modifier.fillMaxWidth()) {
            WellnessRadioButton(selected=language==tag,onClick=null)
            Text(stringResource(label),Modifier.weight(1f))
        }
    }
}
@Composable fun BackButton(onBack:()->Unit) {
    WellnessTextButton(onBack) { Text(stringResource(R.string.back)) }
}
@Composable fun PreferencesScreen(kind:String,preferences:AppPreferences,onBack:()->Unit={}) {
    val mode by preferences.theme.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        BackButton(onBack)
        Text(stringResource(when(kind){"appearance"->R.string.appearance;"language"->R.string.language;else->R.string.about_app}),style=MaterialTheme.typography.headlineLarge)
        WellnessCard(Modifier.fillMaxWidth()) {
            when(kind) {
                "appearance"->{
                    Text(stringResource(R.string.appearance_hint),color=TextSecondary)
                    listOf(ThemeMode.System to R.string.theme_system,ThemeMode.Light to R.string.theme_light,ThemeMode.Dark to R.string.theme_dark).forEach {(value,label) ->
                        WellnessOutlinedButton({preferences.setTheme(value)},Modifier.fillMaxWidth()) {
                            WellnessRadioButton(selected=mode==value,onClick=null);Text(stringResource(label),Modifier.weight(1f))
                        }
                    }
                }
                "language"->{Text(stringResource(R.string.language_hint),color=TextSecondary);LanguageOptions()}
                else->{Text("TamaqAI · ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleLarge);Text(stringResource(R.string.about_description))}
            }
        }
    }
}
