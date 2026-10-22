package com.tamaqai.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

/** Device appearance is independent of diary ownership; existing per-owner settings stay intact. */
class AppPreferences(context: Context) {
    private val prefs=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    private val state=MutableStateFlow(runCatching {ThemeMode.valueOf(prefs.getString("theme","System")!!)}.getOrDefault(ThemeMode.System))
    val theme=state.asStateFlow()
    @android.annotation.SuppressLint("UseKtx")
    fun setTheme(mode: ThemeMode) {
        check(prefs.edit().putString("theme",mode.name).commit())
        state.value=mode
    }
    val guestContinued get()=prefs.getBoolean("guest_continued",false)
    @android.annotation.SuppressLint("UseKtx")
    fun continueGuest() {check(prefs.edit().putBoolean("guest_continued",true).commit())}
}
