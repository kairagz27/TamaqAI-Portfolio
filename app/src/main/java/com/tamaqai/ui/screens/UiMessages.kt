package com.tamaqai.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.tamaqai.R

// Errors carry stable resource keys. Unknown diagnostic text never reaches the UI.
private fun resource(code: String) = when (code) {
    "ui_error_001" -> R.string.ui_error_001
    "ui_error_002" -> R.string.ui_error_002
    "ui_error_003" -> R.string.ui_error_003
    "ui_error_004" -> R.string.ui_error_004
    "ui_error_005" -> R.string.ui_error_005
    "ui_error_006" -> R.string.ui_error_006
    "ui_error_007" -> R.string.ui_error_007
    "ui_error_008" -> R.string.ui_error_008
    "ui_error_009" -> R.string.ui_error_009
    "ui_error_010" -> R.string.ui_error_010
    "ui_error_011" -> R.string.ui_error_011
    "ui_error_012" -> R.string.ui_error_012
    "ui_error_013" -> R.string.ui_error_013
    "ui_error_014" -> R.string.ui_error_014
    "ui_error_015" -> R.string.ui_error_015
    "ui_error_016" -> R.string.ui_error_016
    "ui_error_017" -> R.string.ui_error_017
    "ui_error_018" -> R.string.ui_error_018
    "ui_error_019" -> R.string.ui_error_019
    "ui_error_020" -> R.string.ui_error_020
    "ui_error_021" -> R.string.ui_error_021
    "ui_error_022" -> R.string.ui_error_022
    "ui_error_023" -> R.string.ui_error_023
    "ui_error_024" -> R.string.ui_error_024
    "ui_error_025" -> R.string.ui_error_025
    "ui_error_026" -> R.string.ui_error_026
    "ui_error_027" -> R.string.ui_error_027
    "ui_error_028" -> R.string.ui_error_028
    "ui_error_029" -> R.string.ui_error_029
    "ui_error_030" -> R.string.ui_error_030
    "ui_error_031" -> R.string.ui_error_031
    "ui_error_032" -> R.string.ui_error_032
    "ui_error_033" -> R.string.ui_error_033
    "ui_error_034" -> R.string.ui_error_034
    "ui_error_035" -> R.string.ui_error_035
    "ui_error_036" -> R.string.ui_error_036
    "ui_error_037" -> R.string.ui_error_037
    "ui_error_038" -> R.string.ui_error_038
    "ui_error_039" -> R.string.ui_error_039
    "ui_error_040" -> R.string.ui_error_040
    "ui_error_041" -> R.string.ui_error_041
    "ui_error_042" -> R.string.ui_error_042
    "ui_error_043" -> R.string.ui_error_043
    "ui_error_044" -> R.string.ui_error_044
    "ui_error_045" -> R.string.ui_error_045
    "ui_error_046" -> R.string.ui_error_046
    "ui_error_047" -> R.string.ui_error_047
    "ui_error_048" -> R.string.ui_error_048
    "ui_error_049" -> R.string.ui_error_049
    "ui_error_050" -> R.string.ui_error_050
    "ui_error_051" -> R.string.ui_error_051
    "photo_error" -> R.string.photo_error
    "camera_error" -> R.string.camera_error
    "name_error" -> R.string.name_error
    "time_error" -> R.string.time_error
    "local_save_error" -> R.string.local_save_error
    "invalid_provider_result" -> R.string.invalid_provider_result
    else -> R.string.invalid_provider_result
}
fun messageString(context: Context, code: String): String = code.split('|').joinToString(" ") { context.getString(resource(it)) }
@Composable fun messageText(code: String) = messageString(LocalContext.current, code)
@Composable fun explanationText(text: String): String = if (text.startsWith("ui_error_")) messageText(text) else text
