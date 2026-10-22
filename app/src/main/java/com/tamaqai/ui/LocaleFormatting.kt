package com.tamaqai.ui

import java.util.Locale
import androidx.appcompat.app.AppCompatDelegate

fun appLocale():Locale {
    val explicit=AppCompatDelegate.getApplicationLocales().get(0)
    val locale=explicit ?: Locale.getDefault()
    return if(locale.language in listOf("ru","kk","en")) locale else Locale.ENGLISH
}
fun displayNumber(value:String):String = value.replace(',','.').toDoubleOrNull()?.let {number ->
    java.text.NumberFormat.getNumberInstance(appLocale()).apply {maximumFractionDigits=2;isGroupingUsed=false}.format(number)
} ?: value
