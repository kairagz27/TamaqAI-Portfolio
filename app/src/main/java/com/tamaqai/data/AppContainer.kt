package com.tamaqai.data

import android.content.Context
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.remote.BackendFoodAnalysisRepository
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.BuildConfig
import com.tamaqai.data.repository.MealRepositoryImpl
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.AnalyzeMealPhotoUseCase
import kotlinx.coroutines.*

class AppContainer(context: Context, val sessions: com.tamaqai.data.remote.SessionClient =
    com.tamaqai.data.remote.SessionClient(com.tamaqai.data.remote.SecureSessions(context,BuildConfig.BACKEND_URL),BuildConfig.BACKEND_URL)) {
    val owner = sessions.sessions.owner
    val account = com.tamaqai.data.remote.AccountRepository(context,sessions)
    private val applicationContext = context.applicationContext
    fun text(id: Int): String {
        val configuration = android.content.res.Configuration(applicationContext.resources.configuration)
        configuration.setLocale(com.tamaqai.ui.appLocale())
        return applicationContext.createConfigurationContext(configuration).getString(id)
    }
    private val database = AppDatabase.getInstance(context)
    val photos = PhotoStore(context)
    val settings = com.tamaqai.data.local.UserSettings(context,owner)
    val nutrition = com.tamaqai.data.remote.NutritionRepository(context,sessions,settings)
    val dailyRepository = com.tamaqai.data.repository.DailyRepository(database.dailyDao(),owner)

    val mealRepository: MealRepository by lazy {
        MealRepositoryImpl(database.mealDao(),owner)
    }
    val plans by lazy { com.tamaqai.data.remote.PlanRepository(context,sessions,mealRepository) }
    val catalog by lazy { com.tamaqai.data.remote.CatalogRepository(sessions,database.catalogDao(),owner) }

    val analyzeMealPhotoUseCase by lazy {
        AnalyzeMealPhotoUseCase(BackendFoodAnalysisRepository(context.applicationContext, BuildConfig.BACKEND_URL,sessions,owner))
    }

    val ready = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
        mealRepository.recoverInterruptedAnalyses()
        photos.expire()
    }
}
