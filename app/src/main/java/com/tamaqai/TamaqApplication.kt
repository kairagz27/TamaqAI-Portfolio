package com.tamaqai

import android.app.Application
import android.util.Log
import com.tamaqai.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class TamaqApplication : Application() {
    val preferences by lazy { com.tamaqai.data.local.AppPreferences(this) }
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val startedAt = System.currentTimeMillis()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            cacheDir.listFiles { file -> file.name.startsWith("tamaq_capture_") && file.lastModified() < startedAt }
                ?.forEach { file ->
                    if (file.delete()) Log.d("TamaqAI", "Removed an unfinished camera capture")
                }
        }
    }

    fun reloadIdentity() { container = AppContainer(this,container.sessions) }

    private companion object {
        const val TAG = "TamaqAI"
    }
}
