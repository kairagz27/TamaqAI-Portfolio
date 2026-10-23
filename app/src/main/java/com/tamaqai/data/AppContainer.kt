package com.tamaqai.data

import android.content.Context
import com.tamaqai.BuildConfig
import com.tamaqai.data.contracts.*
import com.tamaqai.data.local.*
import com.tamaqai.data.remote.*
import com.tamaqai.data.repository.*
import com.tamaqai.data.standalone.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.domain.usecase.AnalyzeMealPhotoUseCase
import kotlinx.coroutines.*
import java.time.Clock

/** Variant composition root. Offline mode never constructs a network/session implementation. */
class AppContainer(context:Context, existingSessions:SessionClient?=null,
    offline:Boolean=false, val clock:Clock=Clock.systemDefaultZone()) {
    val remoteSessions:SessionClient? = if(offline) null else existingSessions ?: SessionClient(SecureSessions(context,BuildConfig.BACKEND_URL),BuildConfig.BACKEND_URL)
    // Compatibility accessor for server-only screens/tests. Offline UI uses capability, never this accessor.
    val sessions:SessionClient get()=checkNotNull(remoteSessions) {"standalone_infrastructure_unavailable"}
    val owner=if(offline) "standalone" else sessions.sessions.owner
    private val applicationContext=context.applicationContext
    fun text(id:Int):String {
        val configuration=android.content.res.Configuration(applicationContext.resources.configuration)
        configuration.setLocale(com.tamaqai.ui.appLocale())
        return applicationContext.createConfigurationContext(configuration).getString(id)
    }
    private val database=AppDatabase.getInstance(context)
    val photos=PhotoStore(context)
    val settings=UserSettings(context,owner)
    val dailyRepository=DailyRepository(database.dailyDao(),owner)
    val mealRepository:MealRepository=MealRepositoryImpl(database.mealDao(),owner)
    val account:AccountStore=if(offline) LocalAccountStore(context,owner) else AccountRepository(context,sessions)
    val nutrition:NutritionStore=if(offline) LocalNutritionStore(context,owner,settings,clock) else NutritionRepository(context,sessions,settings)
    val plans:PlanStore=if(offline) LocalPlanStore(context,owner,mealRepository,clock) else PlanRepository(context,sessions,mealRepository)
    val catalog:CatalogStore=if(offline) LocalCatalogStore(context,database.catalogDao(),owner,clock) else CatalogRepository(sessions,database.catalogDao(),owner)
    val standalone:StandaloneDemoController?=if(offline) StandaloneDemoController(context,database,mealRepository,settings,
        account as LocalAccountStore,nutrition as LocalNutritionStore,plans as LocalPlanStore,catalog as LocalCatalogStore,photos,clock,::text) else null
    val analyzeMealPhotoUseCase=AnalyzeMealPhotoUseCase(if(offline) LocalSampleFoodRepository(context,::text)
        else BackendFoodAnalysisRepository(context.applicationContext,BuildConfig.BACKEND_URL,sessions,owner))
    val ready=CoroutineScope(SupervisorJob()+Dispatchers.IO).async {
        standalone?.initialize()
        mealRepository.recoverInterruptedAnalyses()
        photos.expire()
    }
}
